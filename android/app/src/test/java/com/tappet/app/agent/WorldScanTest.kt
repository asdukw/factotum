package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 世界书检索的单测。
 *
 * 这里钉的都是「哪条设定该出现、哪条不该」—— 这类错误在真机上表现为「模型好像没看设定」，
 * 极难从回复里反推，只能靠单测。概率用固定种子，保证 CI 不抖。
 */
class WorldScanTest {
  private fun entry(keys: List<String>, content: String = "内容", order: Int = 0) =
    WorldEntry(keys = keys, content = content, order = order)

  @Test
  fun primaryKeywordAloneIsEnoughWhenNoSecondary() {
    val entries = listOf(entry(listOf("纠察队"), "纠察队的规则"))

    val picked = WorldScan.select(entries, "纠察队在哪")

    assertEquals(1, picked.size)
  }

  @Test
  fun secondaryKeywordBlocksInsertionWhenAbsent() {
    // 「提到学校」不等于「在问校规」：主词命中但副词没中，整条不要
    val entries = listOf(entry(listOf("学府"), "校规细则").copy(secondary = listOf("校规", "宿舍")))

    assertTrue(WorldScan.select(entries, "我路过学府").isEmpty())
    assertEquals(1, WorldScan.select(entries, "学府的校规是什么").size)
  }

  @Test
  fun secondaryAllLogicRequiresEveryKeyword() {
    val entries =
      listOf(entry(listOf("学府"), "细则").copy(secondary = listOf("校规", "宿舍"), secondaryLogic = SecondaryLogic.ALL))

    assertTrue(WorldScan.select(entries, "学府的校规").isEmpty())
    assertEquals(1, WorldScan.select(entries, "学府的校规和宿舍").size)
  }

  @Test
  fun excludeKeywordWinsOverEverything() {
    val entries = listOf(entry(listOf("学府"), "校规细则").copy(exclude = listOf("别告诉我")))

    assertTrue(WorldScan.select(entries, "学府的事别告诉我").isEmpty())
  }

  @Test
  fun probabilityZeroNeverFiresAndHundredAlwaysDoes() {
    val never = listOf(entry(listOf("雨"), "下雨了").copy(probability = 0))
    val always = listOf(entry(listOf("雨"), "下雨了").copy(probability = 100))

    repeat(20) {
      assertTrue(WorldScan.select(never, "下雨了", Random(it)).isEmpty())
      assertEquals(1, WorldScan.select(always, "下雨了", Random(it)).size)
    }
  }

  @Test
  fun probabilityIsReproducibleWithASeed() {
    val entries = listOf(entry(listOf("雨"), "下雨了").copy(probability = 50))

    // 同一颗种子必须给同一个结论，否则 CI 会偶发红
    val first = WorldScan.select(entries, "下雨了", Random(7)).size
    val second = WorldScan.select(entries, "下雨了", Random(7)).size

    assertEquals(first, second)
  }

  @Test
  fun recursionPullsInEntriesMentionedByPickedOnesButOnlyOneLayer() {
    val a = entry(listOf("学生会"), "学生会由纠察队维持秩序。")
    val b = entry(listOf("纠察队"), "纠察队归学生会管。")
    // 只有 c 的正文提到「纠察队」，而从 a 出发扫一层只会带出 b，所以 c 不该出现
    val c = entry(listOf("校规"), "纠察队要背校规")

    val picked = WorldScan.select(listOf(a, b, c), "学生会的人来了")

    assertTrue("应命中学生会", a in picked)
    assertTrue("应顺着正文带出纠察队", b in picked)
    assertFalse("只扫一层，不能继续滚雪球", c in picked)
  }

  @Test
  fun constantEntriesAreNeverInTheMiddleOfTheChat() {
    val entries = listOf(entry(listOf("雨"), "常驻设定").copy(constant = true))

    assertTrue(WorldScan.select(entries, "下雨了").isEmpty())
    assertEquals("· 常驻设定", WorldScan.constantBlock(entries))
  }

  @Test
  fun entryOverTheEntryCapIsSkippedNotTruncated() {
    val entries = (1..8).map { entry(listOf("雨"), "第 $it 条", order = it) }

    val picked = WorldScan.select(entries, "下雨了")

    assertEquals(WorldScan.MAX_ENTRIES, picked.size)
    // 跳过的是排在后面的，不是把某一条切一半
    assertEquals("第 1 条", picked.first().content)
  }

  @Test
  fun budgetSkipsTooLongEntriesAndKeepsTheShorterOnes() {
    val long = entry(listOf("雨"), "长".repeat(WorldScan.MAX_CHARS), order = 0)
    val short = entry(listOf("雨"), "短", order = 1)

    val picked = WorldScan.select(listOf(long, short), "下雨了")

    // 第一条就把预算吃光了 → 只剩它；这正是「单条超预算只跳过它」的反面用例，
    // 说明预算按累计字符算，而不是按条数
    assertEquals(1, picked.size)
    assertEquals(long, picked.first())
  }

  @Test
  fun constantBlockTruncatesAndSaysSo() {
    val entries =
      listOf(
        entry(emptyList(), "甲".repeat(1_200), 0).copy(constant = true),
        entry(emptyList(), "乙".repeat(1_200), 1).copy(constant = true),
      )

    val block = WorldScan.constantBlock(entries)

    assertTrue(block.contains("甲"))
    assertFalse("第二条放不进去就不该出现", block.contains("乙"))
    assertTrue("必须说明被节选过", block.contains("因篇幅省略"))
  }

  @Test
  fun blankQuerySelectsNothing() {
    val entries = listOf(entry(listOf("雨"), "下雨了"))

    assertTrue(WorldScan.select(entries, "   ").isEmpty())
  }

  /** 内存版跟踪：单测里模拟「上一轮激活过」。 */
  private class FakeTracking : WorldTracking {
    private val marks = HashMap<String, Int>()

    override fun lastActivated(key: String): Int? = marks[key]

    override fun record(key: String, turn: Int) {
      marks[key] = turn
    }
  }

  @Test
  fun stickyEntryStaysForItsWindowAfterTheKeywordIsGone() {
    val tracking = FakeTracking()
    val entries = listOf(entry(listOf("学府"), "校规细则").copy(sticky = 2))

    assertEquals(1, WorldScan.select(entries, "学府在哪", Random(1), tracking = tracking, turn = 1).size)
    // 第 2 轮没提关键词，但还在粘性期内
    assertEquals(1, WorldScan.select(entries, "今天天气不错", Random(1), tracking = tracking, turn = 3).size)
    // 第 4 轮已经超出 2 轮窗口
    assertTrue(WorldScan.select(entries, "今天天气不错", Random(1), tracking = tracking, turn = 4).isEmpty())
  }

  @Test
  fun cooldownBlocksReactivationUntilItExpires() {
    val tracking = FakeTracking()
    val entries = listOf(entry(listOf("学府"), "校规").copy(cooldown = 2))

    assertEquals(1, WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 1).size)
    assertTrue("冷却期内同一句话不该再吸进来", WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 2).isEmpty())
    assertEquals(1, WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 4).size)
  }

  @Test
  fun stickyEntryDoesNotRefreshItsOwnTimer() {
    val tracking = FakeTracking()
    val entries = listOf(entry(listOf("学府"), "校规").copy(sticky = 3))

    WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 1)
    // 这一轮是被粘住的，不该把「上次激活」刷成 2；否则粘性永远续期
    assertEquals(1, WorldScan.select(entries, "无关的话", Random(1), tracking = tracking, turn = 2).size)
    assertTrue(WorldScan.select(entries, "无关的话", Random(1), tracking = tracking, turn = 5).isEmpty())
  }

  @Test
  fun stickyKeepsEntryVisibleWhileItIsCooling() {
    val tracking = FakeTracking()
    val entries = listOf(entry(listOf("学府"), "校规").copy(sticky = 3, cooldown = 5))

    WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 1)
    assertEquals(
      "冷却挡的是「再被关键词拉进来」，不是「已经在场的」",
      1,
      WorldScan.select(entries, "学府", Random(1), tracking = tracking, turn = 2).size,
    )
  }

  @Test
  fun matchWholeWordsKeepsCorpOutOfCorporate() {
    val entry = entry(listOf("corp"), "公司区的通行证").copy(matchWholeWords = true)

    assertTrue("整词匹配：corp 在句子里该命中", WorldScan.select(listOf(entry), "meet me at the corp gate", Random(1)).isNotEmpty())
    assertTrue(
      "整词匹配：corporate 不该被 corp 命中",
      WorldScan.select(listOf(entry), "corporate housing is far", Random(1)).isEmpty(),
    )
  }

  @Test
  fun chineseKeysIgnoreWholeWordMatching() {
    // 中文没有词边界：给「学府」加边界等于永远不命中，所以整词开关对中文不生效
    val entry = entry(listOf("学府"), "校规细则").copy(matchWholeWords = true)
    assertTrue(WorldScan.select(listOf(entry), "第一学府在哪", Random(1)).isNotEmpty())
  }

  @Test
  fun caseSensitiveCanBeTurnedOn() {
    val loose = entry(listOf("Alice"), "她住在钟楼").copy(caseSensitive = false)
    val strict = entry(listOf("Alice"), "她住在钟楼").copy(caseSensitive = true)

    assertTrue(WorldScan.select(listOf(loose), "alice 是谁", Random(1)).isNotEmpty())
    assertTrue(WorldScan.select(listOf(strict), "alice 是谁", Random(1)).isEmpty())
    assertTrue(WorldScan.select(listOf(strict), "Alice 是谁", Random(1)).isNotEmpty())
  }

  @Test
  fun useProbabilityFalseIgnoresThePercentage() {
    // 卡里写「必插」但带着 0% 的概率值：以前会被抽掉，现在就当 100%
    val entry = entry(listOf("学府"), "校规细则").copy(probability = 0, useProbability = false)
    repeat(20) { assertTrue(WorldScan.select(listOf(entry), "学府", Random(it)).isNotEmpty()) }
    // 反过来：开关打开时 0% 就是永不
    val never = entry(listOf("学府"), "校规细则").copy(probability = 0, useProbability = true)
    assertTrue(WorldScan.select(listOf(never), "学府", Random(1)).isEmpty())
  }
}

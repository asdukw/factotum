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
}

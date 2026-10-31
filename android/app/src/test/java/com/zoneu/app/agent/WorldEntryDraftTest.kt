package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书编辑器的纯数据规则。
 *
 * 这些规则决定「用户敲的一行文字」会变成什么条目 —— 拆错一个分隔符，那条设定就永远不命中，
 * 而界面上看不出任何异常。所以钉在这里，不靠手点。
 */
class WorldEntryDraftTest {
  @Test
  fun keysAreSplitOnEverySeparatorTheUiCanProduce() {
    val parsed = WorldEntryDraft.parseKeys("第一学府，纠察队、校规;宿舍 / 名媛|学生会\n雨")

    assertEquals(listOf("第一学府", "纠察队", "校规", "宿舍", "名媛", "学生会", "雨"), parsed)
  }

  @Test
  fun blankKeysAreDroppedAndDuplicatesCollapse() {
    // 「、  、雨、雨」这种手滑不该产生空关键词，也不该让同一条算两次
    assertEquals(listOf("雨", "雪"), WorldEntryDraft.parseKeys("、  、雨、雨、雪"))
  }

  @Test
  fun formattingAndParsingRoundTrip() {
    val keys = listOf("第一学府", "纠察队")

    assertEquals(keys, WorldEntryDraft.parseKeys(WorldEntryDraft.formatKeys(keys)))
  }

  @Test
  fun keyCountIsCappedSoOneEntryCannotFloodTheBudget() {
    val many = (1..60).joinToString("、") { "k$it" }

    assertEquals(WorldEntryDraft.MAX_KEYS, WorldEntryDraft.parseKeys(many).size)
  }

  @Test
  fun depthAndProbabilityAreClamped() {
    // 0 是 ST 的「插在历史末尾」，编辑器标签和运行时都用这个约定。
    assertEquals(0, WorldEntryDraft.clampDepth(0))
    assertEquals(12, WorldEntryDraft.clampDepth(99))
    assertEquals(0, WorldEntryDraft.clampProbability(-10))
    assertEquals(100, WorldEntryDraft.clampProbability(250))
  }

  @Test
  fun buildCarriesEveryFieldThrough() {
    val entry =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(
          primary = "学府、校规",
          content = "  九条校规。  ",
          secondary = "宿舍",
          exclude = "别说了",
          depth = 2,
          probability = 60,
          logic = SecondaryLogic.ALL,
        ),
      )

    assertEquals(listOf("学府", "校规"), entry.keys)
    assertEquals(listOf("宿舍"), entry.secondary)
    assertEquals(SecondaryLogic.ALL, entry.secondaryLogic)
    assertEquals(listOf("别说了"), entry.exclude)
    assertEquals("九条校规。", entry.content)
    assertEquals(2, entry.depth)
    assertEquals(60, entry.probability)
  }

  @Test
  fun savableNeedsContentAndEitherAKeywordOrConstant() {
    val noContent = WorldEntry(keys = listOf("雨"), content = " ")
    val noKeys = WorldEntry(keys = emptyList(), content = "设定")
    val constant = WorldEntry(keys = emptyList(), content = "设定", constant = true)
    val ok = WorldEntry(keys = listOf("雨"), content = "设定")

    assertFalse(WorldEntryDraft.isSavable(noContent))
    assertFalse(WorldEntryDraft.isSavable(noKeys))
    assertTrue("常驻条目不需要关键词", WorldEntryDraft.isSavable(constant))
    assertTrue(WorldEntryDraft.isSavable(ok))
  }

  @Test
  fun overlongContentIsTruncatedNotRejected() {
    val entry =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(primary = "雨", content = "长".repeat(WorldEntryDraft.MAX_CONTENT + 50)),
      )

    assertEquals(WorldEntryDraft.MAX_CONTENT, entry.content.length)
  }

  @Test
  fun advancedFieldsCarryThroughAndOmittedOnesKeepOriginal() {
    val old =
      WorldEntry(
        order = 7,
        enabled = false,
        preventRecursion = true,
        excludeRecursion = true,
        group = "scene",
        delay = 2,
        delayUntilRecursion = 1,
        ignoreBudget = true,
      )
    val built =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(original = old, primary = "雨", content = "正文", order = 9, group = "weather"),
      )
    assertEquals(9, built.order)
    assertEquals("weather", built.group)
    // 没在草稿里出现的字段必须沿用原条目，不能被默认值重置。
    assertFalse(built.enabled)
    assertTrue(built.preventRecursion)
    assertTrue(built.excludeRecursion)
    assertEquals(2, built.delay)
    assertEquals(1, built.delayUntilRecursion)
    assertTrue(built.ignoreBudget)
  }

  @Test
  fun unknownPositionsFallBackToDepth() {
    // ST 老版本有 2/3（作者注释之类的落点），我们只认三种，其余回到「按深度」
    assertEquals(WorldEntry.POSITION_DEPTH, WorldEntryDraft.clampPosition(2))
    assertEquals(WorldEntry.POSITION_DEPTH, WorldEntryDraft.clampPosition(99))
    assertEquals(WorldEntry.POSITION_BEFORE, WorldEntryDraft.clampPosition(0))
    assertEquals(WorldEntry.POSITION_AFTER, WorldEntryDraft.clampPosition(1))
  }

  @Test
  fun buildKeepsPositionRoleAndMatchFlags() {
    val entry =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(
          primary = "校规",
          content = "夜里不许留在教学楼。",
          position = WorldEntry.POSITION_BEFORE,
          role = WorldEntry.ROLE_USER,
          useProbability = false,
          matchWholeWords = true,
          caseSensitive = true,
        ),
      )

    assertEquals(WorldEntry.POSITION_BEFORE, entry.position)
    assertEquals(WorldEntry.ROLE_USER, entry.role)
    assertFalse(entry.useProbability)
    assertTrue(entry.matchWholeWords)
    assertTrue(entry.caseSensitive)
    // useProbability=false 时概率形同虚设，实际生效的永远是 100
    assertEquals(100, entry.copy(probability = 30).effectiveProbability)
  }

  @Test
  fun unchangedImportedDraftKeepsEveryFieldAndOversizedValues() {
    val old = WorldEntry(
      keys = listOf("a,b", "x"),
      content = "长".repeat(5000),
      order = 91,
      preventRecursion = true,
      excludeRecursion = true,
      sourceToken = "source-id",
      sticky = 99,
      depth = 0,
    )
    val draft = WorldEntryDraft.Draft(
      original = old,
      primary = WorldEntryDraft.formatKeys(old.keys),
      content = old.content,
      depth = old.depth,
      sticky = old.sticky,
    )
    assertEquals(old, WorldEntryDraft.build(draft))
    val edited = WorldEntryDraft.build(draft.copy(content = "新正文"))
    assertEquals(old.copy(content = "新正文"), edited)
  }
}

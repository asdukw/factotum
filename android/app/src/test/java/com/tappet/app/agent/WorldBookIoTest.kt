package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 世界书单独导出/导入：备份的常常只是那一截，不能只认整张卡。 */
class WorldBookIoTest {
  private val entry =
    WorldEntry(
      keys = listOf("下城区"),
      content = "下城区没有白天的路灯。",
      depth = 6,
      probability = 40,
      sticky = 1,
      position = WorldEntry.POSITION_AFTER,
      role = WorldEntry.ROLE_ASSISTANT,
    )

  @Test
  fun bareArrayRoundTrips() {
    val back = WorldBookIo.importText(WorldBookIo.exportText(listOf(entry)))

    assertEquals(listOf(entry), back)
  }

  @Test
  fun aWholeCardIsAccepted() {
    val card =
      CharacterCard(
        id = "c1",
        name = "卡",
        firstMes = "开场",
        world = listOf(entry),
      )

    val back = WorldBookIo.importText(CardExporter.toV3(card))

    assertEquals(listOf(entry), back)
  }

  @Test
  fun aPastedFragmentWithOnlyTheBookIsAccepted() {
    val text = """{"character_book":{"entries":${WorldBookIo.toJson(listOf(entry))}}}"""

    assertEquals(listOf(entry), WorldBookIo.importText(text))
  }

  @Test
  fun garbageGivesNothingInsteadOfThrowing() {
    // 导入是「尽量捞」，不是「要么全对要么全错」：解析失败就当没读到
    assertTrue(WorldBookIo.importText("这不是 JSON").isEmpty())
    assertTrue(WorldBookIo.importText("").isEmpty())
  }

  @Test
  fun entriesWithoutContentAreDropped() {
    val text = """[{"keys":["空"],"content":"   "},{"keys":["有"],"content":"正文"}]"""

    assertEquals(listOf("有"), WorldBookIo.importText(text).map { it.keys.first() })
  }

  @Test
  fun probabilityOffSurvivesSoWeDoNotStartRollingDice() {
    val off = entry.copy(useProbability = false, matchWholeWords = true, caseSensitive = true)

    val back = WorldBookIo.importText(WorldBookIo.exportText(listOf(off))).single()

    // 这三个开关只要写丢一个，行为就变了：会开始随机漏条目、或者对中文整词永不命中
    assertEquals(false, back.useProbability)
    assertEquals(true, back.matchWholeWords)
    assertEquals(true, back.caseSensitive)
  }
}

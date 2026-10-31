package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ST World Info 的高级字段导入：读错一个别名，条目就会在运行时静默失效。 */
class WorldEntryReaderTest {
  private fun read(text: String): WorldEntry {
    val raw = Json.parseToJsonElement(text).jsonObject
    return WorldEntryReader.entriesOf(JsonArray(listOf(raw))).single()
  }

  @Test
  fun advancedStFieldsAreProjected() {
    val entry =
      read(
        """{
          "keys":["雨"],"content":"正文","enabled":false,"order":7,
          "selective":false,"secondary_keys":["伞"],
          "group":"scene, weather","groupOverride":true,"groupWeight":250,"useGroupScoring":true,
          "delay":3,"delayUntilRecursion":2,"ignoreBudget":true,
          "triggers":["regenerate","swipe"],"scanDepth":6,
          "extensions":{
            "characterFilter":{"isExclude":true,"names":["A"],"tags":["奇幻"]},
            "vectorized":true
          }
        }""",
      )
    assertFalse(entry.enabled)
    assertEquals(7, entry.order)
    assertEquals(false, entry.selective)
    assertEquals("scene, weather", entry.group)
    assertTrue(entry.groupOverride)
    assertEquals(250, entry.groupWeight)
    assertTrue(entry.useGroupScoring)
    assertEquals(3, entry.delay)
    assertEquals(2, entry.delayUntilRecursion)
    assertTrue(entry.ignoreBudget)
    assertEquals(listOf("regenerate", "swipe"), entry.triggers)
    assertEquals(6, entry.scanDepth)
    assertEquals(listOf("A"), entry.characterFilterNames)
    assertEquals(listOf("奇幻"), entry.characterFilterTags)
    assertTrue(entry.characterFilterExclude)
    assertTrue(entry.vectorized)
  }

  @Test
  fun missingSelectiveStaysTriStateAndMissingPositionUsesAfterCharacter() {
    val entry = read("""{"keys":["雨"],"content":"正文"}""")
    assertNull(entry.selective)
    assertEquals(WorldEntry.POSITION_AFTER, entry.position)
  }

  @Test
  fun disableAliasAndOrderAliasAreHonored() {
    val entry = read("""{"key":"雨","content":"正文","disable":true,"order":12}""")
    assertFalse(entry.enabled)
    assertEquals(12, entry.order)
  }

  @Test
  fun exportRoundTripsTheNewFields() {
    val entry =
      WorldEntry(
        keys = listOf("雨"),
        content = "正文",
        group = "scene",
        groupOverride = true,
        groupWeight = 250,
        useGroupScoring = true,
        delay = 3,
        delayUntilRecursion = 2,
        ignoreBudget = true,
        triggers = listOf("regenerate"),
        characterFilterNames = listOf("A"),
        characterFilterTags = listOf("奇幻"),
        characterFilterExclude = true,
        scanDepth = 6,
        enabled = false,
        vectorized = true,
        selective = false,
      )
    val back = WorldEntryReader.entriesOf(JsonArray(listOf(WorldBookIo.entryJson(0, entry)))).single()
    assertEquals(entry.copy(sourceToken = ""), back)
  }

  @Test
  fun cardBookEntriesAcceptTheMapEnvelope() {
    val card =
      """
      {"spec":"chara_card_v3","data":{"name":"卡","description":"设定",
        "character_book":{"entries":{"0":{"keys":["雨"],"content":"甲"},"1":{"keys":["雪"],"content":"乙"}}}}}
      """.trimIndent()
    val parsed = CharacterImporter.fromBytes(card.toByteArray(), "x").card
    assertEquals(listOf("甲", "乙"), parsed.world.map { it.content })
  }

  @Test
  fun worldBookImportAcceptsTheMapEnvelope() {
    val text = """{"name":"书","entries":{"0":{"keys":["雨"],"content":"甲"},"1":{"keys":["雪"],"content":"乙"}}}"""
    assertEquals(listOf("甲", "乙"), WorldBookIo.importText(text).map { it.content })
  }
}

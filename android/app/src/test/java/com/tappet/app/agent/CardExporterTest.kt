package com.tappet.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出的文件能不能被自己（和别的卡工具）读回来。 */
class CardExporterTest {
  private fun sample() =
    CharacterCard(
      id = "c1",
      name = "测/试:卡",
      tagline = "一句话简介",
      tags = listOf("校园", "悬疑"),
      description = "人设正文",
      personality = "话少",
      scenario = "晚自习后的走廊",
      firstMes = "开场白一句",
      alternateGreetings = listOf("备用一", "备用二"),
      creator = "某作者",
      imported = true,
      world =
        listOf(
          WorldEntry(
            keys = listOf("学府", "校规"),
            content = "夜里不许留在教学楼。",
            order = 3,
            constant = true,
            depth = 2,
            secondary = listOf("晚自习"),
            secondaryLogic = SecondaryLogic.ALL,
            exclude = listOf("白天"),
            probability = 70,
            sticky = 2,
            cooldown = 1,
            position = WorldEntry.POSITION_BEFORE,
            role = WorldEntry.ROLE_USER,
            useProbability = false,
            matchWholeWords = true,
            caseSensitive = true,
          ),
        ),
    )

  private fun data(text: String): JsonObject =
    Json.parseToJsonElement(text).jsonObject["data"]!!.jsonObject

  @Test
  fun writesTheV3Shell() {
    val root = Json.parseToJsonElement(CardExporter.toV3(sample())).jsonObject

    assertEquals("chara_card_v3", root["spec"]!!.jsonPrimitive.content)
    assertEquals("3.0", root["spec_version"]!!.jsonPrimitive.content)
    assertEquals("测/试:卡", data(CardExporter.toV3(sample()))["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun dropsTheFieldsWeRefuseToImport() {
    val data = data(CardExporter.toV3(sample()))

    // 这三项导入时就被丢掉，导出必须是空串 —— 不能凭空造一份指令出来
    assertEquals("", data["system_prompt"]!!.jsonPrimitive.content)
    assertEquals("", data["post_history_instructions"]!!.jsonPrimitive.content)
    assertEquals("", data["mes_example"]!!.jsonPrimitive.content)
  }

  @Test
  fun keepsGreetingsAndWorldBook() {
    val data = data(CardExporter.toV3(sample()))
    val greetings = data["alternate_greetings"]!!.jsonArray.map { it.jsonPrimitive.content }
    val entries = data["character_book"]!!.jsonObject["entries"]!!.jsonArray

    assertEquals(listOf("备用一", "备用二"), greetings)
    assertEquals(1, entries.size)
  }

  @Test
  fun aRoundTripThroughTheImporterGivesTheSameWorldEntries() {
    val back = CharacterImporter.fromBytes(CardExporter.toV3(sample()).toByteArray(), "x").card

    assertEquals(1, back.world.size)
    // 编排参数一个都不能丢：position / role / 概率 / 粘性 / 冷却 / 整词 / 大小写 / 副词 / 排除
    assertEquals(sample().world.first(), back.world.first())
  }

  @Test
  fun fileNameStripsCharactersTheFilesystemRejects() {
    assertEquals("测_试_卡.json", CardExporter.fileName(sample()))
  }

  @Test
  fun blankNameStillGetsAFileName() {
    assertTrue(CardExporter.fileName(sample().copy(name = "  ")).endsWith(".json"))
  }
}

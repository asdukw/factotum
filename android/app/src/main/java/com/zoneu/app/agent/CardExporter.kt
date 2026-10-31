package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 原始字段仅在主动导出时恢复，运行编排不读取归档。 */
object CardExporter {
  const val SPEC = "chara_card_v3"
  const val SPEC_VERSION = "3.0"

  private val pretty = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  /** 一张卡 → v3 JSON 文本。 */
  fun toV3(card: CharacterCard): String = pretty.encodeToString(JsonObject.serializer(), CardRoundTrip.card(card))

  /** 建议文件名：把名字里不能进文件系统的字符换掉（导出的文件常被拖到别的地方去）。 */
  fun fileName(card: CharacterCard): String {
    val safe =
      card.name
        .trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .take(40)
        .ifBlank { "character" }
    return "$safe.json"
  }

  internal fun project(card: CharacterCard): JsonObject = buildJsonObject {
    put("name", card.name)
    put("description", card.description)
    put("personality", card.personality)
    put("scenario", card.scenario)
    put("first_mes", card.firstMes)
    putJsonArray("alternate_greetings") { card.alternateGreetings.forEach { add(it) } }
    // 无原始来源的卡只能补空串；有原始文档时由差异合并恢复
    put("mes_example", "")
    put("system_prompt", "")
    put("post_history_instructions", "")
    put("creator_notes", card.tagline)
    put("creator", card.creator)
    put("character_version", "")
    putJsonArray("tags") { card.tags.forEach { add(it) } }
    putJsonArray("group_only_greetings") {}
    putJsonObject("extensions") {}
  }
}

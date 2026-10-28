package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把一张卡导出成 v3 卡 JSON。
 *
 * 为什么要有它：现在只能导入，导不出去。用户导进来一张卡、改了两条世界设定、加了开场白，
 * 这些东西只活在 App 的私有目录里 —— 换机、换 App、想分享给别人，都没有出口。
 *
 * 三条有意为之的取舍：
 * 1. `system_prompt` / `post_history_instructions` / `mes_example` 写**空串**。这三项导入时
 *    就被丢掉了（外部卡不自带指令，是我们定下的红线），导出时不能凭空造出来；写空串而不是
 *    省略，是因为别的工具把「缺这个键」当成格式不完整。
 * 2. 不写 `assets`：我们不读它，写了等于声称支持。
 * 3. `character_book` 用 [WorldBookIo] 拼，保证「单导世界书」和「导整卡」写出来的条目一致。
 */
object CardExporter {
  const val SPEC = "chara_card_v3"
  const val SPEC_VERSION = "3.0"

  private val pretty = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  /** 一张卡 → v3 JSON 文本。 */
  fun toV3(card: CharacterCard): String = pretty.encodeToString(JsonObject.serializer(), cardJson(card))

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

  private fun cardJson(card: CharacterCard): JsonObject = buildJsonObject {
    put("spec", SPEC)
    put("spec_version", SPEC_VERSION)
    putJsonObject("data") {
      put("name", card.name)
      put("description", card.description)
      put("personality", card.personality)
      put("scenario", card.scenario)
      put("first_mes", card.firstMes)
      putJsonArray("alternate_greetings") { card.alternateGreetings.forEach { add(it) } }
      // 下面三项刻意留空：导入时就被丢掉了，导出不能凭空造
      put("mes_example", "")
      put("system_prompt", "")
      put("post_history_instructions", "")
      put("creator_notes", card.tagline)
      put("creator", card.creator)
      put("character_version", "")
      putJsonArray("tags") { card.tags.forEach { add(it) } }
      putJsonObject("character_book") {
        put("name", "${card.name} 的世界书")
        put("entries", WorldBookIo.toJson(card.world))
        putJsonObject("extensions") {}
      }
      putJsonObject("extensions") {}
    }
  }
}

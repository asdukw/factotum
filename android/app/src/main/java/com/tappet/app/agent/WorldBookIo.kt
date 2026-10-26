package com.tappet.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 世界书的导入与导出。
 *
 * 导出写的是 SillyTavern 的 `character_book.entries` 形态（v3 卡里就是它），因此导出的文件
 * 既能自己读回来，也能直接喂给别的卡工具。
 *
 * 导入反过来认三种输入：整张 v3/v2 卡、只带 `character_book` 的片段、以及只有 entries 数组的
 * 文件 —— 备份的时候人常常只存那一截，只认整卡等于「备份了却导不回来」。
 *
 * 编排参数写进 `extensions`，键名跟 ST 一致（`depth` / `role` / `probability` / `sticky` /
 * `cooldown` / `match_whole_words` / `case_sensitive`），刻意不用我们自己的驼峰拼法：
 * 这个文件是要拿去别处用的。
 */
object WorldBookIo {
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val pretty = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
  }

  /** 一组条目 → ST 的 entries 数组。 */
  fun toJson(entries: List<WorldEntry>): JsonArray = buildJsonArray {
    entries.forEachIndexed { index, entry -> add(entryJson(index, entry)) }
  }

  /** 独立文件：只有 entries 数组，没有卡的外壳。 */
  fun exportText(entries: List<WorldEntry>): String =
    pretty.encodeToString(JsonArray.serializer(), toJson(entries))

  /**
   * 从一段 JSON 里读出条目。
   *
   * 解不出来的部分直接忽略（导入是「尽量捞」，不是「要么全对要么全错」）：社区文件里常有
   * 注释、缺逗号、以及我们不认识的字段，宁可少读一条，也不要把整张卡打回去。
   */
  fun importText(text: String): List<WorldEntry> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
    // 三种外壳摊平成 entries 数组，解析本身交给 [CharacterImporter.worldEntriesOf]：
    // 那里的字段兼容（keys/key、v2 的顶层字段、extensions 两套拼法）是真机上验证过的
    val entries =
      when (root) {
        is JsonArray -> root

        is JsonObject -> {
          val data = root["data"]?.jsonObject ?: root
          val book = data["character_book"]?.jsonObject ?: data["world"]?.jsonObject ?: data
          (book["entries"] ?: book["items"]) as? JsonArray ?: root["entries"] as? JsonArray
        }

        else -> null
      }
    return entries?.let { WorldEntryReader.entriesOf(it) }.orEmpty()
  }

  /** 单条的 JSON 形态；[CardExporter] 拼整张卡时也用它，保证两处字段永远一致。 */
  internal fun entryJson(index: Int, entry: WorldEntry): JsonObject = buildJsonObject {
    put("id", index)
    putJsonArray("keys") { entry.keys.forEach { add(it) } }
    putJsonArray("secondary_keys") { entry.secondary.forEach { add(it) } }
    put("content", entry.content)
    // 导出的条目一律 enabled：导出的就是「现在真的在用的这些」
    put("enabled", true)
    put("insertion_order", entry.order)
    put("case_sensitive", entry.caseSensitive)
    put("selective", entry.secondary.isNotEmpty())
    put("constant", entry.constant)
    put("position", entry.position)
    put("comment", "")
    putJsonObject("extensions") {
      put("position", entry.position)
      put("depth", entry.depth)
      put("role", entry.role)
      put("probability", entry.probability)
      put("useProbability", entry.useProbability)
      put("sticky", entry.sticky)
      put("cooldown", entry.cooldown)
      // ST 的四种副词语义里我们只实现 AND_ANY(0) 与 AND_ALL(3)
      put("selectiveLogic", if (entry.secondaryLogic == SecondaryLogic.ALL) 3 else 0)
      putJsonArray("exclude_keys") { entry.exclude.forEach { add(it) } }
      put("match_whole_words", entry.matchWholeWords)
      put("case_sensitive", entry.caseSensitive)
    }
  }
}

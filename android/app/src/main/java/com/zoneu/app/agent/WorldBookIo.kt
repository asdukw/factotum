package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

  /** 带来源书头与未投影条目的独立导出。 */
  fun exportText(card: CharacterCard): String =
    pretty.encodeToString(JsonObject.serializer(), CardRoundTrip.book(card))

  /** 解析并追加来源；同一份书再次导入不会复活本地已删除的条目。 */
  fun appendText(card: CharacterCard, text: String): CharacterCard {
    require(text.toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON) { "世界书 JSON 超过 8 MiB 上限" }
    val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
      ?: throw CharacterImporter.ImportException("世界书不是合法的 JSON")
    val book = CardRoundTrip.findBook(root)
      ?: throw CharacterImporter.ImportException("这个文件里没有世界书 entries")
    return CardRoundTrip.appendBook(card, book)
  }

  /**
   * 从一段 JSON 里读出条目。
   *
   * 解不出来的部分直接忽略（导入是「尽量捞」，不是「要么全对要么全错」）：社区文件里常有
   * 注释、缺逗号、以及我们不认识的字段，宁可少读一条，也不要把整张卡打回去。
   */
  fun importText(text: String): List<WorldEntry> {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > CardInput.MAX_JSON) return emptyList()
    val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
    // 三种外壳摊平成 entries 数组，字段投影统一交给 WorldEntryReader：
    // 那里的字段兼容（keys/key、v2 的顶层字段、extensions 两套拼法）是真机上验证过的
    return entriesOf(root).let { WorldEntryReader.entriesOf(it) }
  }

  private fun entriesOf(root: JsonElement): JsonArray =
    when (root) {
      is JsonArray -> root

      is JsonObject -> {
        val data = (root["data"] as? JsonObject) ?: root
        val book = (data["character_book"] as? JsonObject) ?: (data["world"] as? JsonObject) ?: data
        // 兼容 ST 原生世界书的 map 形式 entries（uid → 条目）。
        listOf("entries", "items").firstNotNullOfOrNull { array(book[it]) }
          ?: array(root["entries"])
          ?: JsonArray(emptyList())
      }

      else -> JsonArray(emptyList())
    }

  private fun array(value: JsonElement?): JsonArray? =
    when (value) {
      is JsonArray -> value
      is JsonObject -> JsonArray(value.values.toList())
      else -> null
    }

  /** 单条的 JSON 形态；[CardExporter] 拼整张卡时也用它，保证两处字段永远一致。 */
  internal fun entryJson(index: Int, entry: WorldEntry): JsonObject = buildJsonObject {
    put("id", index)
    putJsonArray("keys") { entry.keys.forEach { add(it) } }
    putJsonArray("secondary_keys") { entry.secondary.forEach { add(it) } }
    put("content", entry.content)
    put("enabled", entry.enabled)
    put("use_regex", false)
    put("insertion_order", entry.order)
    put("case_sensitive", entry.caseSensitive)
    // 显式记录来源的 selective；来源没写时不导出 false，否则 round-trip 会把三态压成二态。
    entry.selective?.let { put("selective", it) }
    if (entry.selective == null && entry.secondary.isNotEmpty()) put("selective", true)
    put("constant", entry.constant)
    put("position", if (entry.position == WorldEntry.POSITION_BEFORE) "before_char" else "after_char")
    put("comment", "")
    put("group", entry.group)
    // 递归开关：ST 的字段名是驼峰、写在条目顶层；extensions 里再放一份下划线写法，两边的阅读器都能认。
    put("preventRecursion", entry.preventRecursion)
    put("excludeRecursion", entry.excludeRecursion)
    put("delay", entry.delay)
    put("delayUntilRecursion", entry.delayUntilRecursion)
    putJsonArray("triggers") { entry.triggers.forEach { add(it) } }
    putJsonObject("extensions") {
      put("position", entry.position)
      put("depth", entry.depth)
      put("role", entry.role)
      put("probability", entry.probability)
      put("useProbability", entry.useProbability)
      put("sticky", entry.sticky)
      put("cooldown", entry.cooldown)
      put("group", entry.group)
      put("groupOverride", entry.groupOverride)
      put("groupWeight", entry.groupWeight)
      put("useGroupScoring", entry.useGroupScoring)
      put("delay", entry.delay)
      put("delayUntilRecursion", entry.delayUntilRecursion)
      put("ignoreBudget", entry.ignoreBudget)
      put("scanDepth", entry.scanDepth)
      put("vectorized", entry.vectorized)
      // ST 的四种副词语义里我们只实现 AND_ANY(0) 与 AND_ALL(3)
      put("selectiveLogic", entry.secondaryLogic.raw())
      putJsonArray("exclude_keys") { entry.exclude.forEach { add(it) } }
      put("match_whole_words", entry.matchWholeWords)
      put("case_sensitive", entry.caseSensitive)
      putJsonArray("triggers") { entry.triggers.forEach { add(it) } }
      if (entry.characterFilterNames.isNotEmpty() || entry.characterFilterTags.isNotEmpty()) {
        putJsonObject("characterFilter") {
          put("isExclude", entry.characterFilterExclude)
          putJsonArray("names") { entry.characterFilterNames.forEach { add(it) } }
          putJsonArray("tags") { entry.characterFilterTags.forEach { add(it) } }
        }
      }
      // 与上面顶层那对同义（下划线写法）：我们的阅读器顶层与 extensions 两处都读，
      // 别的工具认哪种都不至于丢。
      put("prevent_recursion", entry.preventRecursion)
      put("exclude_recursion", entry.excludeRecursion)
    }
  }
}

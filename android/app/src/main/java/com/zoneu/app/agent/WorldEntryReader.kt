package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把一张卡里的 `character_book.entries` 读成我们的 [WorldEntry]。
 *
 * 从 [CharacterImporter] 里拆出来的原因有两条：
 * 1. [WorldBookIo] 导入「只有世界书的文件」也要走同一套字段兼容，而那条路不能借
 *    `fromBytes` —— 它要求有 description/personality，纯世界书文件会被整张拒掉；
 * 2. 字段兼容的坑集中在二十行里（两套拼法、两处位置），放一个文件里改起来才不容易漏。
 */
object WorldEntryReader {
  /** 一组 entries → 我们的条目。没正文的、`enabled: false` 的都不要。 */
  fun entriesOf(entries: JsonArray): List<WorldEntry> =
    entries.mapNotNull { element -> element as? JsonObject }.mapNotNull(::toEntry)

  private fun toEntry(entry: JsonObject): WorldEntry? {
    val content = entry["content"]?.string()?.trim().orEmpty()
    if (content.isEmpty()) return null
    if (entry["enabled"]?.jsonPrimitive?.booleanOrNull == false) return null
    val extensions = entry["extensions"] as? JsonObject
    return WorldEntry(
      keys = readKeys(entry, "keys", "key"),
      content = content,
      order = entry["insertion_order"]?.jsonPrimitive?.intOrNull ?: 0,
      // `constant` 在 v2 规范里是「每轮都注入」
      constant = entry["constant"]?.jsonPrimitive?.booleanOrNull == true,
      // 编排参数在 v3 卡里放在 extensions，v2 卡偶尔放顶层 —— 两处都认
      depth = extInt(extensions, entry, 4, "depth"),
      secondary = readKeys(entry, "secondary_keys", "secondaryKeys", "keysecondary"),
      // 副词与排除词也是两处都认：ST 写顶层，我们自己导出的写在 extensions
      secondaryLogic = SecondaryLogic.fromRaw(extInt(extensions, entry, 0, "selectiveLogic", "selective_logic")),
      // ST 把这两个开关写在条目顶层，但也有导出工具塞进 extensions —— 两处都读，
      // 与 selectiveLogic / exclude 同一套做法（见 `WorldEntryReader` 的说明）。
      preventRecursion = extBool(extensions, entry, false, "preventRecursion", "prevent_recursion"),
      excludeRecursion = extBool(extensions, entry, false, "excludeRecursion", "exclude_recursion"),
      exclude =
        (
          readKeys(entry, "exclude_keys", "excludeKeys") +
            readKeys(extensions ?: JsonObject(emptyMap()), "exclude_keys", "excludeKeys")
        ).distinct(),
      probability = extInt(extensions, entry, 100, "probability"),
      sticky = extInt(extensions, entry, 0, "sticky"),
      cooldown = extInt(extensions, entry, 0, "cooldown"),
      position = extInt(extensions, entry, WorldEntry.POSITION_DEPTH, "position"),
      role = extInt(extensions, entry, WorldEntry.ROLE_SYSTEM, "role"),
      useProbability = extBool(extensions, entry, true, "useProbability", "use_probability"),
      matchWholeWords = extBool(extensions, entry, false, "matchWholeWords", "match_whole_words"),
      caseSensitive = extBool(extensions, entry, false, "caseSensitive", "case_sensitive"),
    )
  }

  /**
   * 读一个布尔编排参数：先看 extensions，再看条目顶层，都没有就用 [fallback]。
   *
   * 收多个键名是因为同一个字段有两套拼法：ST 原生写 `match_whole_words` / `case_sensitive`，
   * 社区卡与旧版导出常见驼峰。只认一种，另一种卡上的开关就等于没开，界面上还看不出来。
   */
  private fun extBool(extensions: JsonObject?, entry: JsonObject, fallback: Boolean, vararg names: String): Boolean =
    names.firstNotNullOfOrNull { name ->
      extensions?.get(name)?.jsonPrimitive?.booleanOrNull ?: entry[name]?.jsonPrimitive?.booleanOrNull
    } ?: fallback

  /** 读一个整型编排参数：先看 extensions，再看条目顶层，都没有就用 [fallback]。 */
  private fun extInt(extensions: JsonObject?, entry: JsonObject, fallback: Int, vararg names: String): Int =
    names.firstNotNullOfOrNull { name ->
      extensions?.get(name)?.jsonPrimitive?.intOrNull ?: entry[name]?.jsonPrimitive?.intOrNull
    } ?: fallback

  /** 读一组关键词：数组取每项，单字符串当一条；空串丢掉（真卡里偶有空元素）。 */
  private fun readKeys(entry: JsonObject, vararg names: String): List<String> =
    names.firstNotNullOfOrNull { name ->
      when (val value = entry[name]) {
        is JsonArray -> value.mapNotNull { it.string() }
        null -> null
        else -> listOfNotNull(value.string())
      }
    }
      ?.filter { it.isNotBlank() }
      ?: emptyList()

  /** 只认字符串原语：社区卡里的字段偶尔是对象或数字，那种情况按「没有」处理。 */
  private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

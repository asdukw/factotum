package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Source projection and sparse external-field merging; never uses external IDs as asset identity. */
internal object WorldBookFormatFields {
  private val aliases = listOf(
    listOf("keys", "key"),
    listOf("secondary_keys", "secondaryKeys", "keysecondary"),
    listOf("insertion_order", "order"),
    listOf("selectiveLogic", "selective_logic"),
    listOf("exclude_keys", "excludeKeys"),
    listOf("preventRecursion", "prevent_recursion"),
    listOf("excludeRecursion", "exclude_recursion"),
    listOf("useProbability", "use_probability"),
    listOf("matchWholeWords", "match_whole_words"),
    listOf("caseSensitive", "case_sensitive"),
  )

  fun read(raw: JsonElement, key: String?, index: Int, notes: MutableList<String>): WorldBookEntry {
    val obj = raw as? JsonObject
    val unsupported = if (obj == null) listOf("invalid_entry") else unsupported(obj)
    val enabled = obj?.let { enabled(it, notes, "entries[$index]") } ?: false
    val rule = if (obj == null) WorldEntry() else project(obj)
    val entry = WorldBookEntry(enabled = enabled, rule = rule, unsupported = unsupported, externalKey = key, raw = raw)
    unsupported.forEach { notes += "entries[$index]:unsupported:$it" }
    return entry.copy(baseline = projection(entry))
  }

  fun enabled(raw: JsonObject, notes: MutableList<String>, location: String): Boolean {
    val flags = listOf("enabled", "disable", "disabled").mapNotNull { key ->
      if (key !in raw) return@mapNotNull null
      val value = (raw[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
      if (value == null) notes += "$location:invalid_enabled_alias:$key"
      if (key == "enabled") value ?: false else value?.not() ?: false
    }
    if (flags.distinct().size > 1) notes += "$location:enabled_alias_conflict"
    return flags.all { it }
  }

  fun syncEnabled(target: MutableMap<String, JsonElement>, enabled: Boolean) {
    target["enabled"] = JsonPrimitive(enabled)
    listOf("disable", "disabled").filter { it in target }.forEach { target[it] = JsonPrimitive(!enabled) }
  }

  fun write(entry: WorldBookEntry, index: Int): JsonElement {
    val now = projection(entry)
    if (entry.raw != null && entry.baseline == now) return entry.raw
    val raw = entry.raw as? JsonObject ?: return fresh(entry, index)
    val before = entry.baseline ?: JsonObject(emptyMap())
    val result = raw.toMutableMap()
    now.filterKeys { it !in setOf("id", "comment", "extensions", "use_regex") }.forEach { (key, value) ->
      if (before[key] != value) {
        result[key] = value
        if (key == "enabled") syncEnabled(result, entry.enabled) else syncAliases(result, key, value)
      }
    }
    val beforeExt = before["extensions"] as? JsonObject
    val changes = (now["extensions"] as JsonObject).filter { (key, value) -> beforeExt?.get(key) != value }
    mergeExtensions(raw, changes, result)
    return JsonObject(result)
  }

  private fun mergeExtensions(
    raw: JsonObject,
    changes: Map<String, JsonElement>,
    result: MutableMap<String, JsonElement>,
  ) {
    if (changes.isEmpty()) return
    val ext = (raw["extensions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val preserveScalarExtension = "extensions" in raw && raw["extensions"] !is JsonObject
    changes.forEach { (key, value) ->
      if (preserveScalarExtension) result[key] = value
      ext[key] = value
      syncAliases(ext, key, value)
      syncTopLevelRule(raw, result, key, value)
    }
    if (!preserveScalarExtension) result["extensions"] = JsonObject(ext)
  }

  private fun syncTopLevelRule(raw: JsonObject, result: MutableMap<String, JsonElement>, key: String, value: JsonElement) {
    // Top-level position is the standard string; retain a numeric ST alias when present.
    if (key == "position") {
      if ((raw["position"] as? JsonPrimitive)?.intOrNull != null) result["position"] = value
    } else {
      syncAliases(result, key, value)
    }
  }

  private fun projection(entry: WorldBookEntry): JsonObject = JsonObject(
    WorldBookIo.entryJson(0, entry.rule) + ("enabled" to JsonPrimitive(entry.enabled)),
  )

  private fun fresh(entry: WorldBookEntry, index: Int): JsonObject = JsonObject(
    WorldBookIo.entryJson(index, entry.rule) + mapOf(
      "enabled" to JsonPrimitive(entry.enabled),
      "use_regex" to JsonPrimitive("use_regex" in entry.unsupported),
    ),
  )

  private fun syncAliases(target: MutableMap<String, JsonElement>, key: String, value: JsonElement) {
    val names = aliases.firstOrNull { key in it } ?: listOf(key)
    names.filter { it in target }.forEach { target[it] = value }
  }

  private fun project(raw: JsonObject): WorldEntry {
    // Reuse the established alias projection, bypassing its runtime-only disabled/empty/regex filter.
    val source = raw.toMutableMap()
    source["enabled"] = JsonPrimitive(true)
    source["use_regex"] = JsonPrimitive(false)
    source["content"] = JsonPrimitive("projection")
    if ("insertion_order" !in source) raw["order"]?.let { source["insertion_order"] = it }
    // ST's exclusion list is one rule field; repeated external aliases are not separate rules.
    val numericPosition = (raw["position"] as? JsonPrimitive)?.intOrNull
    val extensions = raw["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    val extensionPosition = (extensions["position"] as? JsonPrimitive)?.content
    val standardPosition = when (extensionPosition) {
      "before_char" -> WorldEntry.POSITION_BEFORE
      "after_char" -> WorldEntry.POSITION_AFTER
      else -> null
    }
    if (standardPosition != null) {
      source["extensions"] = JsonObject(extensions + ("position" to JsonPrimitive(standardPosition)))
    } else if (numericPosition != null && "position" !in extensions) {
      source["extensions"] = JsonObject(extensions + ("position" to JsonPrimitive(numericPosition)))
    }
    val projected = WorldEntryReader.entriesOf(JsonArray(listOf(JsonObject(source)))).single()
    val content = (raw["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return projected.copy(content = content, sourceToken = "")
  }

  private fun unsupported(raw: JsonObject): List<String> = buildList {
    val ext = raw["extensions"] as? JsonObject
    val scopes = listOfNotNull(raw, ext)
    if (scopes.any { scope ->
        "use_regex" in scope && (scope["use_regex"] as? JsonPrimitive)?.booleanOrNull != false
      }
    ) {
      add("use_regex")
    }
    val content = (raw["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val decorators = scopes.any { scope ->
      listOf("decorator", "decorators").any { nonempty(scope[it]) }
    }
    if (decorators || decorated(content)) add("decorator")
    if (scopes.any { scope -> scope["position"]?.let { !supportedPosition(it) } == true }) add("position")
    if (scopes.any { scope -> scope["role"]?.let { ((it as? JsonPrimitive)?.intOrNull ?: -1) !in 0..2 } == true }) add("role")
    if (raw["content"] != null && (raw["content"] as? JsonPrimitive)?.isString != true) add("invalid_content")
  }

  fun decorated(content: String): Boolean = content.lineSequence().any { it.trimStart().startsWith("@@") }

  private fun supportedPosition(value: JsonElement): Boolean {
    val primitive = value as? JsonPrimitive ?: return false
    return primitive.content in setOf("before_char", "after_char", "0", "1", "4")
  }

  private fun nonempty(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> false
    is JsonArray -> value.isNotEmpty()
    is JsonObject -> value.isNotEmpty()
    is JsonPrimitive -> value.content !in setOf("", "false")
  }
}

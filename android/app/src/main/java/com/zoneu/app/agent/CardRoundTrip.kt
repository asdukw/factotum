package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.util.UUID

/** 原始文档与白名单编辑值的差异合并，不参与 prompt 渲染。 */
object CardRoundTrip {
  fun findBook(root: JsonElement?): JsonObject? {
    if (root is JsonArray) return JsonObject(mapOf("entries" to root))
    val obj = root as? JsonObject ?: return null
    val data = obj["data"] as? JsonObject ?: obj
    val book = data["character_book"] as? JsonObject ?: data["world"] as? JsonObject ?: data
    // ST 原生世界书导出把 entries 写成「uid → 条目」的对象；卡内一般是数组，两种都收。
    val entries = entriesArray(book["entries"]) ?: entriesArray(book["items"]) ?: return null
    return JsonObject(book + ("entries" to entries))
  }

  private fun entriesArray(value: JsonElement?): JsonArray? =
    when (value) {
      is JsonArray -> value
      is JsonObject -> JsonArray(value.values.toList())
      else -> null
    }

  fun attach(card: CharacterCard, raw: JsonObject, notes: List<CardCompatibilityNote>): CharacterCard {
    val metadata = CardImportMetadata(
      raw = raw,
      baseline = CardExporter.project(card),
      report = notes,
      contentHash = cardHash(cardCanonical(raw).toString()),
    )
    val initial = card.copy(world = emptyList(), importMetadata = metadata)
    return findBook(raw)?.let { appendBook(initial, it) } ?: initial
  }

  fun appendBook(card: CharacterCard, book: JsonObject): CharacterCard {
    val old = card.importMetadata ?: CardImportMetadata(report = card.compatibilityNotes())
    val fingerprint = fingerprint(book)
    if (old.sources.any { it.fingerprint == fingerprint }) return card
    val entries = book["entries"] as? JsonArray ?: JsonArray(emptyList())
    val world = card.world.toMutableList()
    val notes = old.report.toMutableList()
    val slots = entries.mapIndexed { index, raw ->
      val projected = WorldEntryReader.entriesOf(JsonArray(listOf(raw))).firstOrNull()
      val path = "character_book.entries[$index]"
      if (projected == null) {
        notes += CardCompatibilityNote("inactive_entry", path, CardHandling.PRESERVED, inactiveReason(raw))
        WorldSourceSlot(raw)
      } else {
        val existing = if (old.sources.isEmpty() && card.world.isEmpty()) {
          null
        } else {
          world.firstOrNull {
            it.copy(sourceToken = "") == projected
          }
        }
        val token = existing?.sourceToken?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        if (existing == null) {
          world += projected.copy(sourceToken = token)
        } else if (existing.sourceToken.isEmpty()) {
          world[world.indexOf(existing)] = existing.copy(sourceToken = token)
        }
        WorldSourceSlot(raw, token, WorldBookIo.entryJson(0, projected), existing != null)
      }
    }
    notes += CardCompatibilityNote(
      "world_entries",
      "character_book",
      CardHandling.ACTIVE,
      "已读取受支持的运行字段；未启用的原始条目仍保留供导出。",
      world.size - card.world.size,
    )
    notes += CardCompatibilityNote(
      "book_settings",
      "character_book",
      CardHandling.PRESERVED,
      "书级配置及未知扩展保留，扫描预算仍使用应用设置。",
    )
    if (old.sources.isNotEmpty()) {
      notes += CardCompatibilityNote(
        "multiple_books",
        "character_book",
        CardHandling.CONVERTED,
        "多来源条目合并；仅主书头用于标准导出，其余书头和去重条目放入归档扩展。",
      )
    }
    return card.copy(
      world = world,
      importMetadata = old.copy(
        sources = old.sources + WorldBookSource(fingerprint, book, slots),
        report = notes,
      ),
    )
  }

  fun card(card: CharacterCard): JsonObject {
    val metadata = card.importMetadata
    val raw = metadata?.raw
    val originalData = raw?.get("data") as? JsonObject ?: raw ?: JsonObject(emptyMap())
    val projected = CardExporter.project(card)
    val data = originalData.toMutableMap()
    val before = metadata?.baseline
    projected.forEach { (key, value) ->
      if (key == "creator_notes") return@forEach
      // 旧版本存的 baseline 没有新字段：原文档里有值就保留，别用默认值覆盖作者数据。
      val write =
        if (before == null) {
          key !in data
        } else {
          key !in data || (key in before && before[key] != value)
        }
      if (write) data[key] = value
    }
    if ("creator_notes" !in data) data["creator_notes"] = JsonPrimitive(card.tagline)
    normalizeCard(data, projected)
    syncCardAliases(data, projected, metadata?.baseline)
    if (card.world.isNotEmpty() || metadata?.sources?.isNotEmpty() == true) {
      data["character_book"] = book(card)
      if ("world" in data) data["world"] = data.getValue("character_book")
    }
    val root = if (raw?.get("data") is JsonObject) raw.toMutableMap() else mutableMapOf()
    root["spec"] = JsonPrimitive(CardExporter.SPEC)
    root["spec_version"] = JsonPrimitive(CardExporter.SPEC_VERSION)
    root["data"] = JsonObject(data)
    return JsonObject(root)
  }

  fun book(card: CharacterCard): JsonObject {
    val sources = card.importMetadata?.sources.orEmpty()
    val header = sources.firstOrNull()?.book?.toMutableMap()
      ?: mutableMapOf<String, JsonElement>("name" to JsonPrimitive("${card.name} 的世界书"))
    val active = card.world.associateBy { it.sourceToken }
    val current = card.world.iterator()
    val entries = mutableListOf<JsonElement>()
    sources.flatMap { it.slots }.filter { !it.duplicate }.forEach { slot ->
      if (slot.token == null) {
        (slot.raw as? JsonObject)?.let { entries += normalizeEntry(it) }
      } else if (slot.token in active && current.hasNext()) {
        entries += mergedEntry(current.next(), sources, entries.size)
      }
    }
    while (current.hasNext()) entries += mergedEntry(current.next(), sources, entries.size)
    header["entries"] = JsonArray(entries)
    if ("items" in header) header["items"] = JsonArray(entries)
    header["extensions"] = archiveSources(header["extensions"] as? JsonObject, sources, active.keys)
    return JsonObject(header)
  }

  private fun archiveSources(
    extensions: JsonObject?,
    sources: List<WorldBookSource>,
    active: Set<String>,
  ): JsonObject {
    val archives = sources.mapIndexedNotNull { index, source ->
      val duplicates = source.slots.filter { it.duplicate && it.token in active }.map { it.raw }
      if (index == 0 && duplicates.isEmpty()) {
        null
      } else {
        JsonObject(
          mapOf(
            "header" to JsonObject(source.book - "entries" - "items"),
            "entries" to JsonArray(duplicates),
          ),
        )
      }
    }
    val ext = extensions?.toMutableMap() ?: mutableMapOf()
    if (archives.isNotEmpty()) {
      var key = "com.zoneu.card_sources"
      var suffix = 1
      while (key in ext) key = "com.zoneu.card_sources.${suffix++}"
      ext[key] = JsonArray(archives)
    }
    return JsonObject(ext)
  }

  private fun syncCardAliases(
    data: MutableMap<String, JsonElement>,
    projected: JsonObject,
    baseline: JsonObject?,
  ) {
    mapOf("first_mes" to "first_message", "alternate_greetings" to "alternateGreetings").forEach { (key, alias) ->
      if (baseline?.get(key) != projected[key] && alias in data) data[alias] = projected.getValue(key)
    }
  }

  private fun mergedEntry(entry: WorldEntry, sources: List<WorldBookSource>, index: Int): JsonObject {
    val slot = sources.asSequence().flatMap { it.slots.asSequence() }
      .firstOrNull { !it.duplicate && it.token == entry.sourceToken && entry.sourceToken.isNotEmpty() }
    val raw = slot?.raw as? JsonObject ?: return WorldBookIo.entryJson(index, entry)
    val now = WorldBookIo.entryJson(0, entry)
    val merged = raw.toMutableMap()
    now.filterKeys { it != "id" && it != "comment" && it != "extensions" }.forEach { (key, value) ->
      if (slot.baseline[key] != value) {
        merged[key] = value
        updateAliases(merged, key, value)
      }
    }
    val ext = (raw["extensions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val beforeExt = slot.baseline["extensions"] as JsonObject
    (now["extensions"] as JsonObject).forEach { (key, value) ->
      if (beforeExt[key] != value) {
        ext[key] = value
        updateAliases(ext, key, value)
        if (key != "position") updateAliases(merged, key, value)
      }
    }
    merged["extensions"] = JsonObject(ext)
    return normalizeEntry(JsonObject(merged))
  }

  private val aliases = listOf(
    listOf("keys", "key"),
    listOf("secondary_keys", "secondaryKeys", "keysecondary"),
    listOf("selectiveLogic", "selective_logic"),
    listOf("exclude_keys", "excludeKeys"),
    listOf("preventRecursion", "prevent_recursion"),
    listOf("excludeRecursion", "exclude_recursion"),
    listOf("useProbability", "use_probability"),
    listOf("matchWholeWords", "match_whole_words"),
    listOf("caseSensitive", "case_sensitive"),
  )

  private fun updateAliases(target: MutableMap<String, JsonElement>, key: String, value: JsonElement) {
    aliases.firstOrNull { key in it }?.filter { it in target }?.forEach { target[it] = value }
    if (key in target) target[key] = value
  }

  private fun normalizeEntry(raw: JsonObject): JsonObject {
    val result = raw.toMutableMap()
    if (result["keys"] !is JsonArray) {
      val value = result["keys"] ?: result["key"]
      val keys = (value as? JsonPrimitive)?.takeIf { it.isString }?.let { listOf(it) }.orEmpty()
      result["keys"] = JsonArray(keys)
    }
    val keys = result["keys"] as JsonArray
    result["keys"] = JsonArray(keys.filter { (it as? JsonPrimitive)?.isString == true })
    if ((result["content"] as? JsonPrimitive)?.isString != true) result["content"] = JsonPrimitive("")
    result["enabled"] = JsonPrimitive((result["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
    result["use_regex"] = JsonPrimitive((result["use_regex"] as? JsonPrimitive)?.booleanOrNull ?: false)
    result["insertion_order"] = JsonPrimitive((result["insertion_order"] as? JsonPrimitive)?.intOrNull ?: 0)
    if (result["extensions"] !is JsonObject) result["extensions"] = JsonObject(emptyMap())
    val position = result["position"] as? JsonPrimitive
    if (position != null && position.content !in listOf("before_char", "after_char")) {
      val ext = (result["extensions"] as JsonObject).toMutableMap()
      ext.putIfAbsent("position", position)
      result["extensions"] = JsonObject(ext)
      result["position"] = JsonPrimitive(if (position.content == "0") "before_char" else "after_char")
    }
    return JsonObject(result)
  }

  private fun normalizeCard(data: MutableMap<String, JsonElement>, projected: JsonObject) {
    projected.forEach { (key, fallback) ->
      val value = data[key]
      val valid = when (fallback) {
        is JsonArray -> value is JsonArray && value.all { (it as? JsonPrimitive)?.isString == true }
        is JsonObject -> value is JsonObject
        else -> (value as? JsonPrimitive)?.isString == true
      }
      if (!valid) data[key] = fallback
    }
  }

  private fun inactiveReason(raw: JsonElement): String {
    val obj = raw as? JsonObject ?: return "非对象条目不参与运行；损坏结构仅在私有原始记录中保留。"
    return when {
      (obj["use_regex"] as? JsonPrimitive)?.booleanOrNull == true -> "尚不支持正则匹配，不按普通关键词执行。"
      (obj["enabled"] as? JsonPrimitive)?.booleanOrNull == false -> "禁用条目不参与运行。"
      else -> "空白或无效正文不参与运行。"
    }
  }

  private fun fingerprint(book: JsonObject): String = MessageDigest.getInstance("SHA-256")
    .digest(canonical(book).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
  }
}

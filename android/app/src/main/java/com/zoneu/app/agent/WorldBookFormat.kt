package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** JSON-value archive plus an independent management model; importing never deduplicates. */
object WorldBookFormat {
  private val json = Json { isLenient = true }
  private val pretty = Json { prettyPrint = true }

  fun importText(text: String, name: String = "导入的世界书"): WorldBook {
    worldCheck(text.toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON, "WORLD_FIELD_TOO_LARGE")
    val root = try {
      json.parseToJsonElement(text)
    } catch (_: IllegalArgumentException) {
      throw WorldBookException("WORLD_INVALID_JSON")
    }
    val location = locate(root) ?: throw WorldBookException("WORLD_UNSUPPORTED_FORMAT")
    val notes = mutableListOf<String>()
    val header = location.header
    val book = WorldBook(
      name = readText(header["name"])?.takeIf { it.isNotBlank() } ?: name,
      description = readText(header["description"]).orEmpty(),
      tags = (header["tags"] as? JsonArray)?.mapNotNull(::readText).orEmpty(),
      category = readText(header["category"]).orEmpty(),
      enabled = WorldBookFormatFields.enabled(header, notes, "book"),
      scanDepth = readInt(header["scan_depth"]),
      tokenBudget = readInt(header["token_budget"]),
      recursiveScanning = readBool(header["recursive_scanning"]),
      entries = when (val entries = location.entries) {
        is JsonArray -> entries.mapIndexed { index, value -> WorldBookFormatFields.read(value, null, index, notes) }

        is JsonObject -> entries.entries.mapIndexed { index, entry ->
          WorldBookFormatFields.read(entry.value, entry.key, index, notes)
        }

        else -> emptyList()
      },
    )
    return book.copy(
      archive = WorldBookArchive(
        root = root,
        path = location.path,
        entriesKey = location.key,
        mapEntries = location.entries is JsonObject,
        fingerprint = cardHash(cardCanonical(root).toString()),
        baseline = WorldBookEdits.header(book),
        notes = notes,
      ),
    )
  }

  fun exportText(book: WorldBook): String {
    val archive = book.archive
    val rendered = container(book)
    val root = if (archive == null) rendered else replace(archive.root, archive.path, rendered)
    return pretty.encodeToString(JsonElement.serializer(), root)
  }

  /** Explicit sharing only. Normal CardExporter output never follows shared-book references. */
  fun portableCard(card: CharacterCard, books: List<WorldBook>): String = WorldBookFormatPortable.export(card, books)

  internal fun standalone(book: WorldBook): JsonObject {
    val value = container(book)
    return if (value is JsonObject) value else JsonObject(mapOf("entries" to value))
  }

  private fun container(book: WorldBook): JsonElement {
    val archive = book.archive
    if (archive == null) {
      val entries = JsonArray(book.entries.mapIndexed { index, entry -> WorldBookFormatFields.write(entry, index) })
      return JsonObject(WorldBookEdits.header(book) + ("entries" to entries))
    }
    val original = at(archive.root, archive.path)
    val entries = writeEntries(book, archive.mapEntries)
    val before = archive.baseline
    val now = WorldBookEdits.header(book)
    if (original is JsonArray && before == now) return entries
    val result = (original as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    now.forEach { (key, value) ->
      if (before[key] != value) {
        result[key] = value
        if (key == "enabled") WorldBookFormatFields.syncEnabled(result, book.enabled)
      }
    }
    // 用户清空的已知书级字段要从导出里真正删掉，不能一边显示「清除」一边保留旧键。
    (before.keys - now.keys).forEach { result.remove(it) }
    val key = archive.entriesKey.ifEmpty { "entries" }
    result[key] = entries
    val alias = if (key == "entries") "items" else "entries"
    if (original is JsonObject && original[alias] == original[key] && alias in original) result[alias] = entries
    return JsonObject(result)
  }

  private fun writeEntries(book: WorldBook, map: Boolean): JsonElement {
    if (!map) return JsonArray(book.entries.mapIndexed { index, entry -> WorldBookFormatFields.write(entry, index) })
    val reserved = book.entries.mapNotNull { it.externalKey }.toMutableSet()
    val values = linkedMapOf<String, JsonElement>()
    var serial = 0
    book.entries.forEachIndexed { index, entry ->
      val originalKey = entry.externalKey
      val key = if (originalKey != null && originalKey !in values) {
        originalKey
      } else {
        var candidate: String
        do {
          candidate = (serial++).toString()
        } while (!reserved.add(candidate))
        candidate
      }
      values[key] = WorldBookFormatFields.write(entry, index)
    }
    return JsonObject(values)
  }

  private data class Location(val header: JsonObject, val entries: JsonElement, val path: List<String>, val key: String)

  private fun locate(value: JsonElement, path: List<String> = emptyList()): Location? {
    if (value is JsonArray) return Location(JsonObject(emptyMap()), value, path, "")
    val obj = value as? JsonObject ?: return null
    if (path.size > 8) return null
    // A book's own entries win over unknown metadata that happens to contain another book.
    for (key in listOf("entries", "items")) {
      val entries = obj[key]
      if (entries is JsonArray || entries is JsonObject) return Location(obj, entries, path, key)
    }
    for (key in listOf("character_book", "world", "book", "data")) {
      obj[key]?.let { child -> locate(child, path + key)?.let { return it } }
    }
    return null
  }

  private fun at(root: JsonElement, path: List<String>): JsonElement = path.fold(root) { value, key ->
    (value as? JsonObject)?.get(key) ?: throw WorldBookException("WORLD_INVALID_ARCHIVE")
  }

  private fun replace(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
    if (path.isEmpty()) return value
    val obj = root as? JsonObject ?: throw WorldBookException("WORLD_INVALID_ARCHIVE")
    val key = path.first()
    val child = obj[key] ?: throw WorldBookException("WORLD_INVALID_ARCHIVE")
    return JsonObject(obj + (key to replace(child, path.drop(1), value)))
  }

  private fun readText(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun readInt(value: JsonElement?): Int? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

  private fun readBool(value: JsonElement?): Boolean? =
    (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}

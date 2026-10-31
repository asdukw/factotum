package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** Pure sparse management edits. Revisions, approvals and persistence belong to the repository. */
object WorldBookEdits {
  private val headerFields = setOf("name", "description", "tags", "category", "enabled")

  fun apply(
    book: WorldBook,
    fields: JsonObject? = null,
    entryOps: JsonArray? = null,
    newEntryId: () -> String = { UUID.randomUUID().toString() },
  ): WorldBook {
    val request = buildJsonObject {
      fields?.let { put("fields", it) }
      entryOps?.let { put("entry_ops", it) }
    }
    worldCheck(request.toString().toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON, "WORLD_FIELD_TOO_LARGE")
    worldCheck((entryOps?.size ?: 0) <= 200, "WORLD_FIELD_TOO_LARGE")
    worldCheck(book.entries.map { it.id }.distinct().size == book.entries.size, "WORLD_ENTRY_CONFLICT")
    val next = editHeader(book, fields ?: JsonObject(emptyMap()))
    val entries = editEntries(book.entries, entryOps ?: JsonArray(emptyList()), newEntryId)
    val result = next.copy(entries = entries)
    return if (result == book) book else result
  }

  private fun editEntries(
    source: List<WorldBookEntry>,
    operations: JsonArray,
    newEntryId: () -> String,
  ): List<WorldBookEntry> {
    val entries = source.toMutableList()
    val original = source.associateBy { it.id }
    val touched = mutableSetOf<String>()
    operations.forEach { value ->
      val op = value as? JsonObject ?: invalid()
      val operation = string(op["op"] ?: invalid())
      if (operation == "add") {
        worldCheck(op.keys == setOf("op", "fields"), "WORLD_INVALID_FIELDS")
        val id = newEntryId()
        worldCheck(worldId(id) && id !in original && entries.none { it.id == id }, "WORLD_ENTRY_CONFLICT")
        entries += editEntry(WorldBookEntry(id = id), op["fields"] as? JsonObject ?: invalid())
      } else {
        editExisting(entries, original, touched, operation, op)
      }
    }
    return entries
  }

  private fun editExisting(
    entries: MutableList<WorldBookEntry>,
    original: Map<String, WorldBookEntry>,
    touched: MutableSet<String>,
    operation: String,
    op: JsonObject,
  ) {
    worldCheck(operation in setOf("update", "remove", "move"), "WORLD_INVALID_FIELDS")
    val extra = when (operation) {
      "update" -> setOf("fields")
      "move" -> setOf("index")
      else -> emptySet()
    }
    worldCheck(op.keys == setOf("op", "entry_id") + extra, "WORLD_INVALID_FIELDS")
    val id = string(op["entry_id"] ?: invalid())
    val before = original[id] ?: throw WorldBookException("WORLD_ENTRY_NOT_FOUND")
    // An edit and a move of the same original entry are also conflicting operations.
    worldCheck(touched.add(id), "WORLD_ENTRY_CONFLICT")
    val index = entries.indexOfFirst { it.id == id }
    when (operation) {
      "update" -> entries[index] = editEntry(before, op["fields"] as? JsonObject ?: invalid())
      "remove" -> entries.removeAt(index)
      else -> move(entries, index, op["index"])
    }
  }

  fun publicEntry(entry: WorldBookEntry): JsonObject = buildJsonObject {
    put("entry_id", entry.id)
    put("enabled", entry.enabled)
    put("unsupported", JsonArray(entry.unsupported.map(::JsonPrimitive)))
    val rule = entry.rule
    put("keys", JsonArray(rule.keys.map(::JsonPrimitive)))
    put("content", rule.content)
    put("order", rule.order)
    put("constant", rule.constant)
    put("depth", rule.depth)
    put("secondary", JsonArray(rule.secondary.map(::JsonPrimitive)))
    put("secondary_logic", rule.secondaryLogic.name)
    put("exclude", JsonArray(rule.exclude.map(::JsonPrimitive)))
    put("probability", rule.probability)
    put("sticky", rule.sticky)
    put("cooldown", rule.cooldown)
    put("position", rule.position)
    put("role", rule.role)
    put("use_probability", rule.useProbability)
    put("match_whole_words", rule.matchWholeWords)
    put("case_sensitive", rule.caseSensitive)
    put("prevent_recursion", rule.preventRecursion)
    put("exclude_recursion", rule.excludeRecursion)
  }

  fun publicBook(book: WorldBook): JsonObject = buildJsonObject {
    put("book_id", book.id)
    put("revision", book.revision)
    header(book).forEach { (key, value) -> put(key, value) }
    put("entries", JsonArray(book.entries.map(::publicEntry)))
  }

  internal fun header(book: WorldBook): JsonObject = buildJsonObject {
    put("name", book.name)
    put("description", book.description)
    put("tags", JsonArray(book.tags.map(::JsonPrimitive)))
    put("category", book.category)
    put("enabled", book.enabled)
  }

  private fun editHeader(book: WorldBook, fields: JsonObject): WorldBook {
    worldCheck(fields.keys.all { it in headerFields }, "WORLD_INVALID_FIELDS")
    val name = fields["name"]?.let(::string) ?: book.name
    if ("name" in fields) worldCheck(name.isNotBlank(), "WORLD_INVALID_FIELDS")
    val tags = fields["tags"]?.let { value ->
      val array = value as? JsonArray ?: invalid()
      worldCheck(array.size <= 100, "WORLD_FIELD_TOO_LARGE")
      array.map(::string)
    } ?: book.tags
    return book.copy(
      name = name,
      description = fields["description"]?.let(::string) ?: book.description,
      tags = tags,
      category = fields["category"]?.let(::string) ?: book.category,
      enabled = fields["enabled"]?.let(::boolean) ?: book.enabled,
    )
  }

  private fun editEntry(entry: WorldBookEntry, fields: JsonObject): WorldBookEntry {
    worldCheck(fields.isNotEmpty(), "WORLD_INVALID_FIELDS")
    val enabled = fields["enabled"]?.let(::boolean) ?: entry.enabled
    // Shared assets allow long content, without normalizing imported legacy values.
    val content = fields["content"]?.let(::string) ?: entry.rule.content
    val ruleFields = JsonObject(fields - "enabled" - "content")
    val rule = if (ruleFields.isEmpty()) {
      entry.rule
    } else {
      val card = CharacterCard(id = "world-edit", name = "world", world = listOf(entry.rule))
      val id = CardPatch.entryId(card.id, "edit", 0, entry.rule)
      val op = buildJsonObject {
        put("op", "update")
        put("entry_id", id)
        put("fields", ruleFields)
      }
      try {
        CardPatch.apply(card, "edit", null, JsonArray(listOf(op))).card.world.single()
          .copy(sourceToken = entry.rule.sourceToken)
      } catch (error: CardFieldTooLargeException) {
        throw WorldBookException("WORLD_FIELD_TOO_LARGE").apply { initCause(error) }
      } catch (error: CardOperationException) {
        throw WorldBookException(error.code.replaceFirst("CARD_", "WORLD_")).apply { initCause(error) }
      }
    }
    val unsupported = if (content != entry.rule.content && WorldBookFormatFields.decorated(content)) {
      (entry.unsupported + "decorator").distinct()
    } else {
      entry.unsupported
    }
    val result = entry.copy(enabled = enabled, rule = rule.copy(content = content), unsupported = unsupported)
    // Unsupported source capabilities remain immutable, including across enable toggles.
    return if (result == entry) entry else result
  }

  private fun move(entries: MutableList<WorldBookEntry>, from: Int, value: JsonElement?) {
    val index = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid()
    worldCheck(index in entries.indices, "WORLD_INVALID_FIELDS")
    val entry = entries.removeAt(from)
    entries.add(index, entry)
  }

  private fun string(value: JsonElement): String {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    worldCheck(text.length <= 100_000, "WORLD_FIELD_TOO_LARGE")
    return text
  }

  private fun boolean(value: JsonElement): Boolean =
    (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()

  private fun invalid(): Nothing = throw WorldBookException("WORLD_INVALID_FIELDS")
}

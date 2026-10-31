package com.zoneu.app.ui.settings

import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookEdits
import com.zoneu.app.agent.WorldBookEntry
import com.zoneu.app.agent.WorldBookException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only changed form values become a patch; untouched imported values are never normalized. */
internal data class WorldBookDraft(
  val original: WorldBook,
  val name: String = original.name,
  val description: String = original.description,
  val category: String = original.category,
  val tags: String = original.tags.joinToString("\n"),
  val enabled: Boolean = original.enabled,
) {
  fun build(): WorldBook = WorldBookEdits.apply(
    original,
    buildJsonObject {
      if (name != original.name) put("name", name)
      if (description != original.description) put("description", description)
      if (category != original.category) put("category", category)
      if (tags != original.tags.joinToString("\n")) put("tags", JsonArray(tags.lines().filter { it.isNotBlank() }.map(::JsonPrimitive)))
      if (enabled != original.enabled) put("enabled", enabled)
    },
  )
}

internal data class WorldEntryForm(
  val entry: WorldBookEntry,
  val original: JsonObject = WorldBookEdits.publicEntry(entry),
  val values: Map<String, String> = original.filterKeys { it !in setOf("entry_id", "unsupported") }.mapValues { (_, value) ->
    if (value is JsonArray) value.joinToString("\n") { (it as JsonPrimitive).content } else (value as JsonPrimitive).content
  },
) {
  fun changed(key: String, value: String): WorldEntryForm = copy(values = values + (key to value))

  fun patch(): JsonObject = buildJsonObject {
    for ((key, value) in values) {
      val old = original.getValue(key)
      val before = if (old is JsonArray) old.joinToString("\n") { (it as JsonPrimitive).content } else (old as JsonPrimitive).content
      if (before == value) continue
      val next = when {
        old is JsonArray -> JsonArray(value.lines().filter { it.isNotBlank() }.map(::JsonPrimitive))
        (old as JsonPrimitive).isString -> JsonPrimitive(value)
        old.booleanOrNull != null -> JsonPrimitive(value.toBooleanStrictOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))
        else -> JsonPrimitive(value.toIntOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))
      }
      put(key, next)
    }
  }
}

internal fun worldEntryEdit(book: WorldBook, form: WorldEntryForm, added: Boolean): WorldBook {
  val fields = if (added) JsonObject(form.original.filterKeys { it !in setOf("entry_id", "unsupported") } + form.patch()) else form.patch()
  if (!added && fields.isEmpty()) return book
  val operation = buildJsonObject {
    put("op", if (added) "add" else "update")
    if (!added) put("entry_id", form.entry.id)
    put("fields", fields)
  }
  return WorldBookEdits.apply(book, entryOps = JsonArray(listOf(operation)))
}

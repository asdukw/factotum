package com.zoneu.app.ui.settings

import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookEdits
import com.zoneu.app.agent.WorldBookEntry
import com.zoneu.app.agent.WorldBookException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
  val scanDepth: String = original.scanDepth?.toString().orEmpty(),
  val tokenBudget: String = original.tokenBudget?.toString().orEmpty(),
  val recursiveScanning: Boolean = original.recursiveScanning != false,
) {
  fun build(): WorldBook = WorldBookEdits.apply(
    original,
    buildJsonObject {
      if (name != original.name) put("name", name)
      if (description != original.description) put("description", description)
      if (category != original.category) put("category", category)
      if (tags != original.tags.joinToString("\n")) put("tags", JsonArray(tags.lines().filter { it.isNotBlank() }.map(::JsonPrimitive)))
      if (enabled != original.enabled) put("enabled", enabled)
      runtimePatch().forEach { (key, value) -> put(key, value) }
    },
  )

  /** 书级运行设置：空串表示清除，非数字直接报错，不静默写默认。 */
  private fun runtimePatch(): JsonObject = buildJsonObject {
    val depth = scanDepth.trim()
    if (depth.isNotEmpty()) {
      val parsed = depth.toIntOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS")
      if (parsed != original.scanDepth) put("scan_depth", parsed)
    } else if (original.scanDepth != null) {
      put("scan_depth", JsonNull)
    }
    val budget = tokenBudget.trim()
    if (budget.isNotEmpty()) {
      val parsed = budget.toIntOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS")
      if (parsed != original.tokenBudget) put("token_budget", parsed)
    } else if (original.tokenBudget != null) {
      put("token_budget", JsonNull)
    }
    if (recursiveScanning != (original.recursiveScanning != false)) put("recursive_scanning", recursiveScanning)
  }
}

internal data class WorldEntryForm(
  val entry: WorldBookEntry,
  val original: JsonObject = WorldBookEdits.publicEntry(entry),
  val values: Map<String, String> = original.filterKeys { it !in setOf("entry_id", "unsupported") }.mapValues { (_, value) ->
    when (value) {
      is JsonNull -> ""
      is JsonArray -> value.joinToString("\n") { (it as JsonPrimitive).content }
      else -> (value as JsonPrimitive).content
    }
  },
) {
  fun changed(key: String, value: String): WorldEntryForm = copy(values = values + (key to value))

  fun patch(): JsonObject = buildJsonObject {
    for ((key, value) in values) {
      val old = original.getValue(key)
      val before =
        when (old) {
          is JsonNull -> ""
          is JsonArray -> old.joinToString("\n") { (it as JsonPrimitive).content }
          else -> (old as JsonPrimitive).content
        }
      if (before == value) continue
      put(key, convert(key, old, value))
    }
  }

  /** 文本 → JSON 值。数组按行拆；JsonNull 保留三态（空 = 清回不指定）。 */
  private fun convert(key: String, old: JsonElement, value: String): JsonElement =
    when {
      old is JsonArray -> JsonArray(value.lines().filter { it.isNotBlank() }.map(::JsonPrimitive))

      old is JsonNull -> when {
        value.isBlank() || value.equals("null", ignoreCase = true) -> JsonNull
        key == "selective" -> JsonPrimitive(value.toBooleanStrictOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))
        else -> JsonPrimitive(value.toIntOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))
      }

      (old as JsonPrimitive).isString -> JsonPrimitive(value)

      old.booleanOrNull != null -> JsonPrimitive(value.toBooleanStrictOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))

      else -> JsonPrimitive(value.toIntOrNull() ?: throw WorldBookException("WORLD_INVALID_FIELDS"))
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

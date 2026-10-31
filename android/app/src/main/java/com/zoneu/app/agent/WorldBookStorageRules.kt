package com.zoneu.app.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.UUID

/** Pure normalization is shared by writes, replay fingerprints and focused JVM tests. */
internal object WorldBookStorageRules {
  val json = Json { encodeDefaults = true }

  fun revision(): String = UUID.randomUUID().toString()

  fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(element.map(::canonical))
    else -> element
  }

  inline fun <reified T> encode(value: T): String = json.encodeToString(value)

  inline fun <reified T> decode(value: String): T = json.decodeFromString(value)

  /** Caller parameters are an additional gate, never a substitute for the actual write fingerprint. */
  fun parameterHash(parameters: String, actual: String): String {
    val value = parameters.ifBlank { actual }
    val normalized = runCatching { canonical(json.parseToJsonElement(value)).toString() }.getOrDefault(value)
    return hash(normalized)
  }

  fun sameContent(current: WorldBook, draft: WorldBook): Boolean =
    current == draft.copy(revision = current.revision)

  fun cas(actual: String, expected: String) = worldCheck(actual == expected, "WORLD_REVISION_CONFLICT")

  fun createDraft(book: WorldBook): WorldBook = book.copy(
    id = "",
    revision = "",
    entries = book.entries.map { it.copy(id = "") },
  )

  fun uuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

  /** Same book runs once, but every source is retained. Conflicting pins must be explicitly resolved. */
  fun bindings(books: List<WorldBookBinding>): List<WorldBookBinding> = books.groupBy { it.bookId }.map { (_, group) ->
    val first = group.first()
    worldCheck(worldId(first.bookId) && group.all { worldId(it.revision) })
    worldCheck(group.all { it.revision == first.revision }, "WORLD_BINDING_CONFLICT")
    worldCheck(group.all { row -> row.sources.all { it.isNotBlank() && it.length <= 512 } })
    first.copy(
      sources = group.flatMap { it.sources }.distinct().sorted(),
      override = when {
        group.any { it.override == WorldOverride.DISABLE } -> WorldOverride.DISABLE
        group.any { it.override == WorldOverride.ENABLE } -> WorldOverride.ENABLE
        else -> WorldOverride.INHERIT
      },
      epoch = "",
    )
  }

  /** Epoch belongs to a binding's semantics, never to an untrusted draft or a read. */
  fun manifest(current: WorldManifest?, draft: WorldManifest): WorldManifest {
    val before = current?.books.orEmpty().associateBy { it.bookId }
    val books = bindings(draft.books).map { binding ->
      val previous = before[binding.bookId]
      val unchanged = previous?.copy(epoch = "") == binding
      binding.copy(epoch = if (unchanged) previous!!.epoch else revision())
    }
    return draft.copy(revision = current?.revision ?: "empty", books = books)
  }

  fun settledState(current: WorldRuntimeState, plan: WorldRequestPlan): WorldRuntimeState {
    // 重写/续写属于同一个交换：复用当前轮，不推进 sticky/cooldown 时钟。
    if (!plan.advanceTurn) {
      worldCheck(plan.turn == current.turn, "WORLD_TURN_CONFLICT")
      return current
    }
    worldCheck(plan.turn == current.turn + 1, "WORLD_TURN_CONFLICT")
    val selected = plan.injections.map { it.key }.toSet()
    worldCheck(plan.activated.distinct().size == plan.activated.size && plan.activated.all { it in selected }, "WORLD_PLAN_INVALID")
    return WorldRuntimeState(plan.turn, current.activated + plan.activated.associateWith { plan.turn })
  }
}

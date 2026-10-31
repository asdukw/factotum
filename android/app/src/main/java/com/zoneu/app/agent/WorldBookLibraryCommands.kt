package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Normalized, fully validated request; all IDs/proofs remain host-owned. */
internal data class WorldBookCommand(
  val tool: String,
  val args: JsonObject,
  val operation: String,
  val bookId: String? = null,
  val expected: String? = null,
  val scope: String? = null,
  val targetId: String? = null,
  val fields: JsonObject? = null,
  val entryOps: JsonArray? = null,
  val bookIds: List<String>? = null,
  val changes: JsonArray? = null,
) {
  // Must match the plugin's cancel payload byte-for-byte: it canonicalizes
  // {operation: <tool name>, arguments: <original args>}. Using the short
  // internal operation name here made every cancellation a conflict.
  val parameters: String = WorldBookStorageRules.canonical(
    buildJsonObject {
      put("operation", tool)
      put("arguments", args)
    },
  ).toString()
  val fingerprint: String = worldHash(parameters)

  companion object {
    fun parse(tool: String, args: JsonObject): WorldBookCommand {
      worldCheck(args.toString().toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON, "WORLD_FIELD_TOO_LARGE")
      val allowed = allowedFields(tool)
      worldCheck(args.keys.all { it in allowed })
      if (tool == "update_world_book_bindings") return bindingCommand(tool, args)
      val id = if (tool == "create_world_book") null else worldRequired(args, "book_id").also { worldCheck(worldId(it)) }
      val expected = if (id == null) null else worldRequired(args, "expected_revision")
      val fields = args["fields"]?.let { it as? JsonObject ?: invalid() }
      val entries = args["entry_ops"]?.let { it as? JsonArray ?: invalid() }
      if (tool == "create_world_book") worldCheck(fields != null && worldString(fields, "name", 512)?.isNotBlank() == true)
      return WorldBookCommand(tool, args, tool.removeSuffix("_world_book"), id, expected, fields = fields, entryOps = entries)
    }

    private fun allowedFields(tool: String): Set<String> = when (tool) {
      "create_world_book" -> setOf("fields", "entry_ops")
      "update_world_book" -> setOf("book_id", "expected_revision", "fields", "entry_ops")
      "copy_world_book", "delete_world_book" -> setOf("book_id", "expected_revision")
      "update_world_book_bindings" -> setOf("scope", "target_id", "expected_revision", "book_ids", "changes")
      else -> throw WorldBookException("WORLD_UNKNOWN_OPERATION")
    }

    private fun bindingCommand(tool: String, args: JsonObject): WorldBookCommand {
      val scope = worldRequired(args, "scope")
      worldCheck(scope in setOf("character", "session"))
      val target = worldRequired(args, "target_id")
      worldCheck(worldId(target))
      val expected = worldRequired(args, "expected_revision")
      val ids = if (scope == "character") {
        worldCheck("changes" !in args && "book_ids" in args)
        val array = args["book_ids"] as? JsonArray ?: invalid()
        worldCheck(array.size <= 200)
        array.map { value ->
          val id = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
          worldCheck(worldId(id))
          id
        }.also { worldCheck(it.distinct().size == it.size, "WORLD_BINDING_CONFLICT") }
      } else {
        null
      }
      val changes = if (scope == "session") {
        worldCheck("book_ids" !in args && "changes" in args)
        (args["changes"] as? JsonArray ?: invalid()).also { worldCheck(it.size <= 200) }
      } else {
        null
      }
      return WorldBookCommand(
        tool,
        args,
        if (scope == "character") "role-bindings" else "manifest",
        expected = expected,
        scope = scope,
        targetId = target,
        bookIds = ids,
        changes = changes,
      )
    }

    private fun invalid(): Nothing = throw WorldBookException("WORLD_INVALID_ARGUMENT")
  }
}

/** Pure mutation planning. No UUID randomness, migration, macros or timing writes in preflight. */
internal suspend fun applyWorldBindingChanges(
  before: WorldManifest,
  changes: JsonArray,
  request: String,
  read: suspend (String, String) -> WorldBook,
): WorldManifest {
  val books = before.books.toMutableList()
  val touched = mutableSetOf<String>()
  for (value in changes) {
    val change = value as? JsonObject ?: throw WorldBookException("WORLD_INVALID_ARGUMENT")
    val op = worldRequired(change, "op")
    worldCheck(op in setOf("add", "remove", "enable", "disable", "inherit", "upgrade"))
    val needsRevision = op == "add" || op == "upgrade"
    worldCheck(change.keys == if (needsRevision) setOf("op", "book_id", "revision") else setOf("op", "book_id"))
    val id = worldRequired(change, "book_id")
    worldCheck(worldId(id) && touched.add(id), "WORLD_BINDING_CONFLICT")
    val index = books.indexOfFirst { it.bookId == id }
    if (op == "add") {
      worldCheck(index < 0, "WORLD_BINDING_CONFLICT")
      val revision = worldRequired(change, "revision")
      read(id, revision)
      books += WorldBookBinding(id, revision, listOf("session:${before.sessionId}"), epoch = worldOperationId(request, id))
      continue
    }
    worldCheck(index >= 0, "WORLD_BINDING_NOT_FOUND")
    if (op == "remove") {
      books.removeAt(index)
    } else {
      books[index] = editWorldBinding(books[index], op, change, request, read)
    }
  }
  return before.copy(books = books)
}

private suspend fun editWorldBinding(
  old: WorldBookBinding,
  op: String,
  change: JsonObject,
  request: String,
  read: suspend (String, String) -> WorldBook,
): WorldBookBinding {
  val next = if (op == "upgrade") {
    val revision = worldRequired(change, "revision")
    read(old.bookId, revision)
    old.copy(revision = revision)
  } else {
    old.copy(
      override = when (op) {
        "enable" -> WorldOverride.ENABLE
        "disable" -> WorldOverride.DISABLE
        else -> WorldOverride.INHERIT
      },
    )
  }
  return if (old == next) old else next.copy(epoch = worldOperationId(request, old.bookId))
}

/** A bounded process-local approval binding. Loss fails closed; cancellation cannot be overwritten by preview. */
internal class WorldBookPreviews {
  private data class Binding(val parameters: String?, val fingerprint: String = "", val mode: String = "", val cancelled: Boolean = false)

  private val entries = linkedMapOf<String, Binding>()

  @Synchronized
  fun register(request: String, parameters: String) {
    val old = entries[request]
    worldCheck(old?.parameters == null || old.parameters == parameters, "WORLD_REQUEST_CONFLICT")
    if (old == null && entries.size >= 256) entries.remove(entries.keys.first())
    entries[request] = (old ?: Binding(parameters)).copy(parameters = parameters)
  }

  @Synchronized
  fun bind(request: String, fingerprint: String, mode: String) {
    val old = entries[request] ?: throw WorldBookException("WORLD_PREVIEW_REQUIRED")
    entries[request] = old.copy(fingerprint = fingerprint, mode = mode)
  }

  @Synchronized
  fun check(request: String, parameters: String, fingerprint: String, supplied: String): String {
    val old = entries[request] ?: throw WorldBookException("WORLD_PREVIEW_REQUIRED")
    worldCheck(
      old.parameters == parameters && old.fingerprint == fingerprint && supplied == fingerprint && old.mode.isNotBlank(),
      "WORLD_PREVIEW_CONFLICT",
    )
    return old.mode
  }

  @Synchronized
  fun cancelled(request: String): Boolean = entries[request]?.cancelled == true

  @Synchronized
  fun cancel(request: String): String? {
    val old = entries[request]
    if (old == null && entries.size >= 256) entries.remove(entries.keys.first())
    entries[request] = (old ?: Binding(null)).copy(cancelled = true)
    return old?.parameters
  }
}

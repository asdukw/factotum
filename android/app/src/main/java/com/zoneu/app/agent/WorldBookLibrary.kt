package com.zoneu.app.agent

import android.content.Context
import com.zoneu.app.data.session.zoneuSessionStore
import com.zoneu.app.device.DeviceActionResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

/** Authenticated management boundary. Preview is read-only, and approval proof never comes from the model. */
internal class WorldBookLibrary(
  private val repository: WorldBookLibraryStore,
  private val previews: WorldBookPreviews,
  private val requireCharacter: (String) -> Unit,
  private val requireSession: suspend (String) -> Unit,
  private val explain: suspend (String, String) -> JsonObject,
) {
  suspend fun handle(name: String, requestId: String?, args: JsonObject, fingerprint: String? = null): DeviceActionResult = operation {
    when (name) {
      "list_world_books" -> success(WorldBookQuery.list(repository.list(), args))

      "read_world_book" -> {
        val book = repository.read(worldRequired(args, "book_id"), worldString(args, "revision"))
        val usage = if (worldString(args, "section") == "usage") repository.usage(book.id) else null
        success(WorldBookQuery.read(book, args, usage))
      }

      "read_world_book_bindings" -> {
        worldCheck(args.keys == setOf("scope", "target_id"))
        success(readBindings(worldRequired(args, "scope"), worldRequired(args, "target_id")))
      }

      "explain_world_activation" -> {
        worldCheck(args.keys.all { it in setOf("session_id", "text") })
        val id = worldRequired(args, "session_id")
        val explanation = explain(id, worldString(args, "text", 100_000).orEmpty())
        success(
          JsonObject(
            explanation + mapOf(
              "kind" to JsonPrimitive("zoneu.world-book.explanation.v1"),
              "ok" to JsonPrimitive(true),
              "untrusted" to JsonPrimitive(true),
            ),
          ),
        )
      }

      "preview_world_book_action" -> {
        val tool = worldRequired(args, "operation")
        val command = WorldBookCommand.parse(tool, JsonObject(args - "operation"))
        validateTarget(command)
        preview(requiredRequest(requestId), command)
      }

      "cancel_world_book_action" -> {
        worldCheck(args.keys == setOf("parameters"))
        cancel(requiredRequest(requestId), worldRequired(args, "parameters", CardInput.MAX_JSON + 4096))
      }

      else -> {
        val command = WorldBookCommand.parse(name, args)
        validateTarget(command)
        execute(requiredRequest(requestId), command, fingerprint.orEmpty())
      }
    }
  }

  private suspend fun preview(request: String, command: WorldBookCommand): DeviceActionResult {
    previews.register(request, command.parameters)
    return repository.transaction {
      val prior = repository.receipt(request, command.parameters)
      val proposal = if (prior == null) propose(request, command) else null
      val mode = when {
        prior != null -> "replay"
        proposal!!.unchanged -> "unchanged"
        else -> "write"
      }
      // A cancelled operation may be proven already committed, but can never become a fresh write.
      worldCheck(prior != null || !previews.cancelled(request), "WORLD_REQUEST_CANCELLED")
      if (prior != null) replay(command, prior)
      previews.bind(request, command.fingerprint, mode)
      success(
        buildJsonObject {
          put("kind", "zoneu.world-book.preview.v1")
          put("ok", true)
          put("operation", command.tool)
          put("fingerprint", command.fingerprint)
          put("mode", mode)
          command.bookId?.let { put("book_id", it) }
          command.expected?.let { put("expected_revision", it) }
          command.scope?.let { put("scope", it) }
          command.targetId?.let { put("target_id", it) }
          put("field_changes", proposal?.fields ?: JsonArray(emptyList()))
          put("counts", proposal?.counts ?: worldCounts())
        },
      )
    }
  }

  private suspend fun execute(request: String, command: WorldBookCommand, supplied: String): DeviceActionResult {
    val mode = previews.check(request, command.parameters, command.fingerprint, supplied)
    // This outer transaction keeps the no-op check and replay-only check in the same transaction domain.
    return repository.transaction {
      val prior = repository.receipt(request, command.parameters)
      if (prior != null) return@transaction success(replay(command, prior))
      worldCheck(mode != "replay", "WORLD_PREVIEW_CONFLICT")
      worldCheck(!previews.cancelled(request), "WORLD_REQUEST_CANCELLED")
      val proposal = propose(request, command)
      if (mode == "unchanged") {
        worldCheck(proposal.unchanged, "WORLD_PREVIEW_CONFLICT")
        return@transaction success(unchanged(command, proposal))
      }
      val payload = when (command.tool) {
        "create_world_book" -> bookResult(repository.create(proposal.book!!, request, command.parameters), "created")

        "update_world_book" -> {
          if (proposal.unchanged) {
            unchanged(command, proposal)
          } else {
            bookResult(repository.update(proposal.book!!, command.expected!!, request, command.parameters), "updated")
          }
        }

        "copy_world_book" -> bookResult(repository.copy(command.bookId!!, command.expected!!, request, command.parameters), "copied")

        "delete_world_book" -> {
          repository.delete(command.bookId!!, command.expected!!, request, command.parameters)
          val receipt = repository.receipt(request, command.parameters) ?: throw WorldBookException("WORLD_RECEIPT_MISSING")
          bookResult(command.bookId, receipt.revision, "deleted")
        }

        "update_world_book_bindings" -> {
          if (proposal.unchanged) {
            unchanged(command, proposal)
          } else if (command.scope == "character") {
            val saved = repository.setRoleBindings(proposal.role!!, command.expected!!, request, command.parameters)
            bindingResult(saved, "updated")
          } else {
            val saved = repository.setManifest(proposal.manifest!!, command.expected!!, request, command.parameters)
            bindingResult(saved, "updated")
          }
        }

        else -> throw WorldBookException("WORLD_UNKNOWN_OPERATION")
      }
      success(payload)
    }
  }

  private suspend fun cancel(request: String, parameters: String): DeviceActionResult {
    val registered = previews.cancel(request)
    // The plugin canonicalizes the same JSON in JS; comparing parsed hashes tolerates
    // escaping/key-order differences that a raw string compare would turn into a conflict.
    worldCheck(registered == null || worldSameParameters(registered, parameters), "WORLD_REQUEST_CONFLICT")
    val cancelled = repository.cancelOperation(request, parameters)
    return success(
      buildJsonObject {
        put("kind", "zoneu.world-book.cancel.v1")
        put("ok", true)
        put("cancelled", cancelled)
      },
    )
  }

  private suspend fun propose(request: String, command: WorldBookCommand): WorldBookProposal {
    if (command.tool == "update_world_book_bindings") return proposeBindings(request, command)
    val before = if (command.tool == "create_world_book") null else repository.read(command.bookId!!)
    if (before != null) worldCheck(before.revision == command.expected, "WORLD_REVISION_CONFLICT")
    val usage = before?.let { repository.usage(it.id) } ?: WorldUsage()
    if (command.tool == "delete_world_book") worldCheck(!usage.referenced, "WORLD_REFERENCED")
    val initial = before ?: WorldBook(id = worldOperationId(request, "book"), name = "")
    val after = worldEditedBook(initial, command, request)
    worldCheck(after.name.isNotBlank() && after.name.length <= 512 && after.entries.size <= 10_000)
    val counts = worldEntryCounts(before, after, usage)
    return WorldBookProposal(
      book = after,
      unchanged = command.tool == "update_world_book" && after == before,
      fields = worldHeaderChanges(before, after),
      counts = counts,
    )
  }

  private suspend fun proposeBindings(request: String, command: WorldBookCommand): WorldBookProposal {
    val target = command.targetId!!
    if (command.scope == "character") {
      val before = repository.roleBindings(target)
      worldCheck(before.revision == command.expected, "WORLD_REVISION_CONFLICT")
      val ids = command.bookIds!!
      ids.forEach { repository.read(it) }
      val after = before.copy(bookIds = ids)
      WorldBookQuery.bounded(bindingResult(after, "updated"))
      return WorldBookProposal(
        role = after,
        unchanged = before == after,
        counts = worldCounts(
          added = ids.count { it !in before.bookIds },
          updated = ids.count { it in before.bookIds && ids.indexOf(it) != before.bookIds.indexOf(it) },
          removed = before.bookIds.count { it !in ids },
        ),
      )
    }
    val before = repository.manifest(target) ?: WorldManifest(target)
    worldCheck(before.revision == command.expected, "WORLD_REVISION_CONFLICT")
    val after = applyWorldBindingChanges(before, command.changes!!, request) { id, revision -> repository.read(id, revision) }
    WorldBookQuery.bounded(bindingResult(after, "updated"))
    return WorldBookProposal(
      manifest = after,
      unchanged = after == before,
      counts = worldCounts(
        added = after.books.count { book -> before.books.none { it.bookId == book.bookId } },
        updated = after.books.count { book -> before.books.find { it.bookId == book.bookId }?.let { it != book } == true },
        removed = before.books.count { book -> after.books.none { it.bookId == book.bookId } },
      ),
    )
  }

  private suspend fun readBindings(scope: String, target: String): JsonObject {
    worldCheck(worldId(target))
    return when (scope) {
      "character" -> {
        requireCharacter(target)
        bindingResult(repository.roleBindings(target))
      }

      "session" -> {
        requireSession(target)
        bindingResult(repository.manifest(target) ?: WorldManifest(target))
      }

      else -> throw WorldBookException("WORLD_INVALID_ARGUMENT")
    }
  }

  private suspend fun validateTarget(command: WorldBookCommand) {
    if (command.scope == "character") requireCharacter(command.targetId!!)
    if (command.scope == "session") requireSession(command.targetId!!)
  }

  private suspend fun replay(command: WorldBookCommand, prior: WorldOperationReceipt): JsonObject {
    worldCheck(prior.operation == command.operation, "WORLD_REQUEST_CONFLICT")
    val payload = when (command.operation) {
      "delete" -> bookResult(prior.targetId, prior.revision, "deleted")

      "role-bindings" -> bindingResult(repository.roleBindings(prior.targetId), "updated")

      "manifest" -> bindingResult(repository.manifest(prior.targetId) ?: throw WorldBookException("WORLD_NOT_FOUND"), "updated")

      else -> replayedBook(
        prior,
        when (command.operation) {
          "create" -> "created"
          "copy" -> "copied"
          else -> "updated"
        },
      )
    }
    return JsonObject(payload + mapOf("replay" to JsonPrimitive(true), "applied_revision" to JsonPrimitive(prior.revision)))
  }

  /** A committed receipt stays replayable after a later deletion of its target. */
  private suspend fun replayedBook(prior: WorldOperationReceipt, disposition: String): JsonObject = try {
    bookResult(repository.read(prior.targetId), disposition)
  } catch (error: WorldBookException) {
    if (error.code != "WORLD_NOT_FOUND") throw error
    JsonObject(bookResult(prior.targetId, prior.revision, disposition) + ("deleted" to JsonPrimitive(true)))
  }

  private fun unchanged(command: WorldBookCommand, proposal: WorldBookProposal): JsonObject = when (command.scope) {
    "character" -> bindingResult(proposal.role!!, "unchanged")
    "session" -> bindingResult(proposal.manifest!!, "unchanged")
    else -> bookResult(proposal.book!!, "unchanged")
  }

  private fun success(payload: JsonObject): DeviceActionResult =
    DeviceActionResult(true, "世界书操作完成", WorldBookQuery.bounded(payload).toString())

  private suspend fun operation(block: suspend () -> DeviceActionResult): DeviceActionResult = try {
    block()
  } catch (error: WorldBookException) {
    DeviceActionResult(false, "世界书操作未完成", code = error.code)
  } catch (_: IOException) {
    DeviceActionResult(false, "世界书存储不可用", code = "WORLD_STORAGE_FAILED")
  } catch (_: IllegalArgumentException) {
    DeviceActionResult(false, "世界书参数无效", code = "WORLD_INVALID_ARGUMENT")
  } catch (_: IllegalStateException) {
    DeviceActionResult(false, "世界书存储不可用", code = "WORLD_STORAGE_FAILED")
  }

  companion object {
    private val bindings = WorldBookPreviews()

    fun forContext(context: Context): WorldBookLibrary = WorldBookLibrary(
      WorldBookLibraryRepository(context.worldBookRepository()),
      bindings,
      requireCharacter = { id ->
        worldCheck(CharacterStore.cards(context).any { it.id == id }, "WORLD_CHARACTER_NOT_FOUND")
      },
      requireSession = { id ->
        val session = context.zoneuSessionStore().session(id) ?: throw WorldBookException("WORLD_SESSION_NOT_FOUND")
        worldCheck(session.isRoleplay, "WORLD_NOT_ROLEPLAY")
      },
      explain = { id, text -> WorldRuntime.explain(context, id, text) },
    )
  }
}

internal data class WorldBookProposal(
  val book: WorldBook? = null,
  val role: WorldRoleBindings? = null,
  val manifest: WorldManifest? = null,
  val unchanged: Boolean = false,
  val fields: JsonArray = JsonArray(emptyList()),
  val counts: JsonObject = worldCounts(),
)

internal fun worldCounts(
  added: Int = 0,
  updated: Int = 0,
  removed: Int = 0,
  usage: WorldUsage = WorldUsage(),
): JsonObject = buildJsonObject {
  put("added", added)
  put("updated", updated)
  put("removed", removed)
  put("character_references", usage.characterIds.size)
  put("session_references", usage.sessionIds.size)
}

internal fun worldOperationId(request: String, slot: String): String =
  UUID.nameUUIDFromBytes("world:$request:$slot".toByteArray(Charsets.UTF_8)).toString()

private fun requiredRequest(request: String?): String {
  worldCheck(request != null && worldId(request), "WORLD_INVALID_REQUEST")
  return request!!
}

internal fun worldSameParameters(registered: String, supplied: String): Boolean =
  registered == supplied ||
    WorldBookStorageRules.parameterHash(registered, registered) == WorldBookStorageRules.parameterHash(supplied, supplied)

private fun bookResult(book: WorldBook, disposition: String): JsonObject = bookResult(book.id, book.revision, disposition)

private fun bookResult(id: String, revision: String, disposition: String) = buildJsonObject {
  put("kind", "zoneu.world-book.result.v1")
  put("ok", true)
  put("book_id", id)
  put("revision", revision)
  put("disposition", disposition)
  put("replay", false)
}

private fun bindingResult(value: WorldRoleBindings, disposition: String? = null) = bindingResult(
  "character",
  value.characterId,
  value.revision,
  value.bookIds.map { buildJsonObject { put("book_id", it) } },
  disposition,
)

private fun bindingResult(value: WorldManifest, disposition: String? = null) = bindingResult(
  "session",
  value.sessionId,
  value.revision,
  value.books.map { binding ->
    buildJsonObject {
      put("book_id", binding.bookId)
      put("revision", binding.revision)
      put("sources", JsonArray(binding.sources.map(::JsonPrimitive)))
      put("override", binding.override.name.lowercase())
    }
  },
  disposition,
)

private fun bindingResult(scope: String, id: String, revision: String, items: List<JsonObject>, disposition: String?) = buildJsonObject {
  put("kind", "zoneu.world-book.bindings.v1")
  put("ok", true)
  put("untrusted", true)
  put("scope", scope)
  put("target_id", id)
  put("revision", revision)
  put("items", JsonArray(items))
  put("complete", true)
  disposition?.let { put("disposition", it) }
  put("replay", false)
}

private fun worldEntryCounts(before: WorldBook?, after: WorldBook, usage: WorldUsage): JsonObject {
  val previous = before?.entries.orEmpty().withIndex().associate { it.value.id to it }
  val current = after.entries.map { it.id }.toSet()
  val updated = after.entries.withIndex().count { (index, entry) ->
    previous[entry.id]?.let { it.value != entry || it.index != index } == true
  }
  return worldCounts(
    added = after.entries.count { it.id !in previous },
    updated = updated,
    removed = previous.keys.count { it !in current },
    usage = usage,
  )
}

private fun worldHeaderChanges(before: WorldBook?, after: WorldBook): JsonArray {
  val previous = before?.let(WorldBookEdits::header)
  return JsonArray(
    WorldBookEdits.header(after).filter { (key, value) -> previous?.get(key) != value }.map { (key, value) ->
      buildJsonObject {
        put("field", key)
        put("before_length", previous?.get(key)?.toString()?.length ?: 0)
        put("after_length", value.toString().length)
      }
    },
  )
}

private fun worldEditedBook(initial: WorldBook, command: WorldBookCommand, request: String): WorldBook {
  if (command.tool !in setOf("create_world_book", "update_world_book")) return initial
  var entryIndex = 0
  return WorldBookEdits.apply(initial, command.fields, command.entryOps) {
    worldOperationId(request, "entry-${entryIndex++}")
  }.validated()
}

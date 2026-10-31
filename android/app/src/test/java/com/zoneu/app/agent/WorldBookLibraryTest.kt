package com.zoneu.app.agent

import com.zoneu.app.device.DeviceActionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the actual management coordinator; the fake replaces persistence, not preview/execute decisions. */
class WorldBookLibraryTest {
  private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private val update =
    args("""{"book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1","fields":{"description":"new"}}""")

  private fun initial() = WorldBook(id = "00000000-0000-4000-8000-000000000001", revision = "r1", name = "Synthetic", description = "old")

  private fun library(store: MemoryWorldLibraryStore) = WorldBookLibrary(
    store,
    WorldBookPreviews(),
    {},
    {},
    {
      _,
      _,
      ->
      JsonObject(emptyMap())
    },
  )

  private fun payload(result: DeviceActionResult): JsonObject {
    assertTrue(result.code.orEmpty(), result.ok)
    return Json.parseToJsonElement(result.payload!!).jsonObject
  }

  private suspend fun preview(library: WorldBookLibrary, request: String, tool: String, args: JsonObject): JsonObject = payload(
    library.handle("preview_world_book_action", request, JsonObject(args + ("operation" to JsonPrimitive(tool)))),
  )

  @Test
  fun readOnlyPreviewAndDeniedOrMissingProofNeverCallMutation() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val preview = preview(library, "request", "update_world_book", update)
    assertEquals("write", preview.getValue("mode").jsonPrimitive.content)
    assertEquals(0, store.mutations)
    assertTrue(store.receipts.isEmpty())
    assertEquals(initial(), store.current)
    assertEquals("WORLD_PREVIEW_CONFLICT", library.handle("update_world_book", "request", update).code)
    assertEquals("WORLD_PREVIEW_REQUIRED", library.handle("update_world_book", "other", update, "forged").code)
    assertEquals(0, store.mutations)
  }

  @Test
  fun executionRechecksRevisionAndFingerprintAndNeverSilentlyRebases() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val preview = preview(library, "request", "update_world_book", update)
    val fingerprint = preview.getValue("fingerprint").jsonPrimitive.content
    val changed =
      args("""{"book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1","fields":{"description":"different"}}""")
    assertEquals("WORLD_PREVIEW_CONFLICT", library.handle("update_world_book", "request", changed, fingerprint).code)
    store.current = initial().copy(name = "later", revision = "r2")
    assertEquals("WORLD_REVISION_CONFLICT", library.handle("update_world_book", "request", update, fingerprint).code)
    assertEquals(0, store.mutations)
  }

  @Test
  fun actualSaveAndReplayReadCurrentObjectWithoutCallingMutatorAgain() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val first = preview(library, "request", "update_world_book", update)
    val saved = payload(library.handle("update_world_book", "request", update, first.getValue("fingerprint").jsonPrimitive.content))
    assertEquals("updated", saved.getValue("disposition").jsonPrimitive.content)
    assertEquals(1, store.mutations)
    assertEquals("new", store.current!!.description)
    store.current = store.current!!.copy(name = "later edit", revision = "later-revision")
    val repeat = preview(library, "request", "update_world_book", update)
    assertEquals("replay", repeat.getValue("mode").jsonPrimitive.content)
    val replay = payload(library.handle("update_world_book", "request", update, repeat.getValue("fingerprint").jsonPrimitive.content))
    assertEquals(JsonPrimitive(true), replay["replay"])
    assertEquals(JsonPrimitive("later-revision"), replay["revision"])
    assertEquals(1, store.mutations)
    assertEquals("later edit", store.current!!.name)
    // Loss of replay evidence can never turn an allow-without-approval path into a fresh write.
    store.receipts.clear()
    assertEquals(
      "WORLD_PREVIEW_CONFLICT",
      library.handle(
        "update_world_book",
        "request",
        update,
        repeat.getValue("fingerprint").jsonPrimitive.content,
      ).code,
    )
    assertEquals(1, store.mutations)
  }

  @Test
  fun noOpDoesNotCallRepositoryMutationOrCreateReceipt() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val unchanged = args("""{"book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1","fields":{"description":"old"}}""")
    val preview = preview(library, "request", "update_world_book", unchanged)
    assertEquals("unchanged", preview.getValue("mode").jsonPrimitive.content)
    val result = payload(library.handle("update_world_book", "request", unchanged, preview.getValue("fingerprint").jsonPrimitive.content))
    assertEquals(JsonPrimitive("unchanged"), result["disposition"])
    assertEquals(0, store.mutations)
    assertTrue(store.receipts.isEmpty())
    assertEquals(initial(), store.current)
  }

  @Test
  fun cancelAcceptsThePluginCanonicalPayloadEvenWhenItExceedsTheDefaultArgumentCap() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val long =
      args(
        """{"book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1","fields":{"description":"${"长".repeat(300)}"}}""",
      )
    val preview = preview(library, "request", "update_world_book", long)
    // Shape sent by world-tools.mjs: JSON.stringify(canonical({operation: tool, arguments})).
    val plugin =
      JsonObject(
        mapOf("operation" to JsonPrimitive("update_world_book"), "arguments" to long),
      ).toString()
    assertTrue(plugin.length > 240)
    val cancelled =
      payload(
        library.handle("cancel_world_book_action", "request", JsonObject(mapOf("parameters" to JsonPrimitive(plugin)))),
      )
    assertEquals(JsonPrimitive(true), cancelled["cancelled"])
    assertEquals(
      "WORLD_REQUEST_CANCELLED",
      library.handle("update_world_book", "request", long, preview.getValue("fingerprint").jsonPrimitive.content).code,
    )
    assertEquals(0, store.mutations)
  }

  @Test
  fun replaySurvivesALaterDeletionOfTheCommittedTarget() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val first = preview(library, "request", "update_world_book", update)
    payload(library.handle("update_world_book", "request", update, first.getValue("fingerprint").jsonPrimitive.content))
    store.current = null
    val replay = preview(library, "request", "update_world_book", update)
    assertEquals("replay", replay.getValue("mode").jsonPrimitive.content)
    val result = payload(library.handle("update_world_book", "request", update, replay.getValue("fingerprint").jsonPrimitive.content))
    assertEquals(JsonPrimitive("updated"), result["disposition"])
    assertEquals(JsonPrimitive(true), result["replay"])
    assertEquals(JsonPrimitive(true), result["deleted"])
    assertEquals(JsonPrimitive(initial().id), result["book_id"])
    assertEquals(1, store.mutations)
  }

  @Test
  fun operationIdentityUsesTheWireToolNameNotTheInternalShortName() {
    assertTrue(WorldBookCommand.parse("update_world_book", update).parameters.contains("\"operation\":\"update_world_book\""))
    val bindings =
      args(
        """{"scope":"character","target_id":"00000000-0000-4000-8000-000000000002","expected_revision":"empty","book_ids":[]}""",
      )
    assertTrue(WorldBookCommand.parse("update_world_book_bindings", bindings).parameters.contains("update_world_book_bindings"))
  }

  @Test
  fun cancellationBlocksLateApprovalButCannotUndoCommittedReceipt() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val preview = preview(library, "request", "update_world_book", update)
    assertEquals(
      JsonPrimitive(true),
      payload(
        library.handle(
          "cancel_world_book_action",
          "request",
          JsonObject(
            mapOf(
              "parameters" to JsonPrimitive(
                WorldBookCommand.parse(
                  "update_world_book",
                  update,
                ).parameters,
              ),
            ),
          ),
        ),
      )["cancelled"],
    )
    assertEquals(
      "WORLD_REQUEST_CANCELLED",
      library.handle(
        "update_world_book",
        "request",
        update,
        preview.getValue("fingerprint").jsonPrimitive.content,
      ).code,
    )
    assertEquals(0, store.mutations)
    val second = preview(library, "other", "update_world_book", update)
    payload(library.handle("update_world_book", "other", update, second.getValue("fingerprint").jsonPrimitive.content))
    assertEquals(
      JsonPrimitive(false),
      payload(
        library.handle(
          "cancel_world_book_action",
          "other",
          JsonObject(
            mapOf(
              "parameters" to JsonPrimitive(
                WorldBookCommand.parse(
                  "update_world_book",
                  update,
                ).parameters,
              ),
            ),
          ),
        ),
      )["cancelled"],
    )
    assertEquals(
      JsonPrimitive(true),
      payload(
        library.handle(
          "update_world_book",
          "other",
          update,
          second.getValue("fingerprint").jsonPrimitive.content,
        ),
      )["replay"],
    )
    assertEquals(1, store.mutations)
  }

  @Test
  fun creationPreflightIsDeterministicAndDeleteChecksDisabledReferences() = runTest {
    val store = MemoryWorldLibraryStore(initial())
    val library = library(store)
    val creation = args("""{"fields":{"name":"Synthetic"},"entry_ops":[{"op":"add","fields":{"enabled":false,"content":""}}]}""")
    assertEquals(preview(library, "create", "create_world_book", creation), preview(library, "create", "create_world_book", creation))
    assertEquals(0, store.mutations)
    store.refs = WorldUsage(sessionIds = listOf("disabled-session"))
    val result = library.handle(
      "preview_world_book_action",
      "delete",
      args("""{"operation":"delete_world_book","book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1"}"""),
    )
    assertEquals("WORLD_REFERENCED", result.code)
    assertEquals(0, store.mutations)
  }
}

private class MemoryWorldLibraryStore(var current: WorldBook?) : WorldBookLibraryStore {
  var mutations = 0
  var refs = WorldUsage()
  val receipts = mutableMapOf<String, WorldOperationReceipt>()
  private val cancellations = mutableMapOf<String, String>()
  private val roles = mutableMapOf<String, WorldRoleBindings>()
  private val manifests = mutableMapOf<String, WorldManifest>()

  override suspend fun <T> transaction(block: suspend () -> T): T = block()

  override suspend fun list(): List<WorldBook> = listOfNotNull(current)

  override suspend fun read(id: String, revision: String?): WorldBook =
    current?.takeIf { it.id == id && (revision == null || revision == it.revision) } ?: throw WorldBookException("WORLD_NOT_FOUND")

  override suspend fun usage(id: String): WorldUsage = refs

  override suspend fun receipt(request: String, parameters: String): WorldOperationReceipt? {
    val hash = WorldBookStorageRules.parameterHash(parameters, parameters)
    val prior = receipts[request]
    worldCheck(prior == null || prior.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
    worldCheck(cancellations[request] == null || cancellations[request] == hash, "WORLD_REQUEST_CONFLICT")
    if (prior == null) worldCheck(request !in cancellations, "WORLD_REQUEST_CANCELLED")
    return prior
  }

  override suspend fun cancelOperation(request: String, parameters: String): Boolean {
    if (receipts[request] != null) return false
    cancellations[request] = WorldBookStorageRules.parameterHash(parameters, parameters)
    return true
  }

  override suspend fun create(book: WorldBook, request: String, parameters: String): WorldBook {
    current = book.copy(revision = "created")
    commit(request, "create", parameters, book.id, "created")
    return current!!
  }

  override suspend fun update(book: WorldBook, expected: String, request: String, parameters: String): WorldBook {
    worldCheck(current?.revision == expected, "WORLD_REVISION_CONFLICT")
    current = book.copy(revision = "updated")
    commit(request, "update", parameters, book.id, "updated")
    return current!!
  }

  override suspend fun copy(id: String, expected: String, request: String, parameters: String): WorldBook = error("unused")

  override suspend fun delete(id: String, expected: String, request: String, parameters: String) {
    current = null
    commit(request, "delete", parameters, id, "deleted")
  }

  override suspend fun roleBindings(id: String): WorldRoleBindings = roles[id] ?: WorldRoleBindings(id)

  override suspend fun manifest(id: String): WorldManifest? = manifests[id]

  override suspend fun setRoleBindings(
    bindings: WorldRoleBindings,
    expected: String,
    request: String,
    parameters: String,
  ): WorldRoleBindings {
    val result = bindings.copy(revision = "bound")
    roles[bindings.characterId] = result
    commit(request, "role-bindings", parameters, bindings.characterId, result.revision)
    return result
  }

  override suspend fun setManifest(manifest: WorldManifest, expected: String, request: String, parameters: String): WorldManifest {
    val result = manifest.copy(revision = "bound")
    manifests[manifest.sessionId] = result
    commit(request, "manifest", parameters, manifest.sessionId, result.revision)
    return result
  }

  private fun commit(request: String, operation: String, parameters: String, id: String, revision: String) {
    mutations++
    receipts[request] =
      WorldOperationReceipt(request, operation, WorldBookStorageRules.parameterHash(parameters, parameters), id, revision)
  }
}

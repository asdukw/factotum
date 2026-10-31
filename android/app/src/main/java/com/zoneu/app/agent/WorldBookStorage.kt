package com.zoneu.app.agent

import com.zoneu.app.data.db.WorldOperationReceiptEntity
import com.zoneu.app.data.db.ZoneuDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal val worldStorageJson = WorldBookStorageRules.json

internal fun worldHash(value: String): String = WorldBookStorageRules.hash(value)

internal fun worldCanonical(value: JsonElement): JsonElement = WorldBookStorageRules.canonical(value)

internal fun worldCas(current: String, expected: String) = WorldBookStorageRules.cas(current, expected)

internal fun worldUuid(value: String): Boolean = WorldBookStorageRules.uuid(value)

internal fun worldNormalizeManifest(
  draft: WorldManifest,
  current: WorldManifest?,
): WorldManifest = WorldBookStorageRules.manifest(
  current,
  draft,
)

internal fun worldSettleState(
  state: WorldRuntimeState,
  plan: WorldRequestPlan,
): WorldRuntimeState = WorldBookStorageRules.settledState(
  state,
  plan,
)

internal data class WorldMutationHash(val parameters: String, val actual: String)

/** Caller arguments never replace the fingerprint of the actual write. */
internal fun worldParameters(operation: String, parameters: String, fallback: String): WorldMutationHash = WorldMutationHash(
  WorldBookStorageRules.parameterHash(parameters, fallback),
  worldHash("$operation\n${WorldBookStorageRules.parameterHash("", fallback)}"),
)

internal fun WorldBook.validated(): WorldBook {
  worldCheck(worldUuid(id) && entries.all { worldUuid(it.id) })
  worldCheck(entries.map { it.id }.distinct().size == entries.size, "WORLD_ENTRY_CONFLICT")
  worldCheck(name.isNotBlank() && name.length <= 512 && entries.size <= 10_000)
  worldCheck(archive == null || archive.fingerprint.isNotBlank())
  return this
}

internal suspend fun ZoneuDatabase.worldReplay(
  requestId: String,
  operation: String,
  hash: WorldMutationHash,
): WorldOperationReceiptEntity? {
  worldCheck(worldId(requestId))
  val receipt = worldBooks().receipt(requestId)
  worldCheck(
    receipt == null || (receipt.operation == operation && receipt.parameterHash == hash.parameters && receipt.actualHash == hash.actual),
    "WORLD_REQUEST_CONFLICT",
  )
  if (receipt == null) {
    WorldBookPendingCancellation.check(this, requestId, hash.parameters)
    val cancelled = worldBooks().cancellation(requestId)
    worldCheck(cancelled == null || cancelled.parameterHash == hash.parameters, "WORLD_REQUEST_CONFLICT")
    worldCheck(cancelled == null, "WORLD_REQUEST_CANCELLED")
  }
  return receipt
}

internal suspend fun ZoneuDatabase.worldReceipt(
  requestId: String,
  operation: String,
  hash: WorldMutationHash,
  id: String,
  revision: String,
) {
  worldBooks().insertReceipt(WorldOperationReceiptEntity(requestId, operation, hash.parameters, hash.actual, id, revision))
  // This is the last suspension of a mutator: cancellation arriving during a DAO write rolls it all back.
  WorldBookPendingCancellation.check(this, requestId, hash.parameters)
}

internal fun worldPlanHash(plan: WorldRequestPlan): String =
  worldHash(worldCanonical(worldStorageJson.encodeToJsonElement(WorldRequestPlan.serializer(), plan.copy(status = "prepared"))).toString())

/** Only short hashes are held in memory. The database cancellation row is the durable authority after restart. */
internal object WorldBookPendingCancellation {
  private val pending = java.util.WeakHashMap<ZoneuDatabase, MutableMap<String, String>>()

  @Synchronized
  fun mark(database: ZoneuDatabase, requestId: String, hash: String) {
    val requests = pending.getOrPut(database) { mutableMapOf() }
    val previous = requests[requestId]
    worldCheck(previous == null || previous == hash, "WORLD_REQUEST_CONFLICT")
    requests[requestId] = hash
  }

  @Synchronized
  fun check(database: ZoneuDatabase, requestId: String, hash: String) {
    val cancelled = pending[database]?.get(requestId)
    worldCheck(cancelled == null || cancelled == hash, "WORLD_REQUEST_CONFLICT")
    worldCheck(cancelled == null, "WORLD_REQUEST_CANCELLED")
  }

  @Synchronized
  fun clear(database: ZoneuDatabase, requestId: String) {
    pending[database]?.remove(requestId)
  }
}

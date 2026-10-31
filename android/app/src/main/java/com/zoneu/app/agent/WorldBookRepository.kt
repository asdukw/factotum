package com.zoneu.app.agent

import android.content.Context
import androidx.room.withTransaction
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.WorldOperationCancellationEntity
import com.zoneu.app.data.db.ZoneuDatabase
import java.util.UUID

/** One SQLCipher transaction domain; never takes a card-file lock. */
class WorldBookRepository(val database: ZoneuDatabase) {
  private val assets = WorldBookStorageAssets(database)
  private val bindings = WorldBookStorageBindings(database)
  private val plans = WorldBookStoragePlans(database)

  suspend fun list(): List<WorldBook> = database.withTransaction { assets.list() }

  suspend fun read(id: String, revision: String? = null): WorldBook = database.withTransaction { assets.read(id, revision) }

  suspend fun create(book: WorldBook, requestId: String = UUID.randomUUID().toString(), parameters: String = ""): WorldBook =
    database.withTransaction { assets.create(book, requestId, parameters) }

  suspend fun update(
    book: WorldBook,
    expectedRevision: String,
    requestId: String = UUID.randomUUID().toString(),
    parameters: String = "",
  ): WorldBook = database.withTransaction { assets.update(book, expectedRevision, requestId, parameters) }

  suspend fun copy(
    id: String,
    expectedRevision: String,
    requestId: String = UUID.randomUUID().toString(),
    parameters: String = "",
  ): WorldBook = database.withTransaction { assets.copy(id, expectedRevision, requestId, parameters) }

  suspend fun delete(
    id: String,
    expectedRevision: String,
    requestId: String = UUID.randomUUID().toString(),
    parameters: String = "",
  ) = database.withTransaction { assets.delete(id, expectedRevision, requestId, parameters) }

  suspend fun usage(id: String): WorldUsage = database.withTransaction { bindings.usage(id) }

  suspend fun roleBindings(characterId: String): WorldRoleBindings = bindings.roleBindings(characterId)

  /**
   * Card files live outside Room, so deleting a card cannot cascade its defaults.
   * Without this the dead reference keeps every bound book undeletable forever.
   */
  suspend fun forgetCharacter(characterId: String): Unit = database.withTransaction {
    worldCheck(worldUuid(characterId), "WORLD_INVALID_ARGUMENT")
    database.worldBooks().clearRoleBindings(characterId)
  }

  suspend fun setRoleBindings(
    bindings: WorldRoleBindings,
    expectedRevision: String,
    requestId: String = UUID.randomUUID().toString(),
    parameters: String = "",
  ): WorldRoleBindings = database.withTransaction {
    this@WorldBookRepository.bindings.setRoleBindings(bindings, expectedRevision, requestId, parameters)
  }

  suspend fun manifest(sessionId: String): WorldManifest? = bindings.manifest(sessionId)

  suspend fun setManifest(
    manifest: WorldManifest,
    expectedRevision: String,
    requestId: String = UUID.randomUUID().toString(),
    parameters: String = "",
  ): WorldManifest = database.withTransaction { bindings.setManifest(manifest, expectedRevision, requestId, parameters) }

  suspend fun runtimeState(sessionId: String): WorldRuntimeState = plans.runtimeState(sessionId)

  suspend fun plan(requestId: String): WorldRequestPlan? = plans.plan(requestId)

  suspend fun preparedPlan(sessionId: String): WorldRequestPlan? = plans.preparedPlan(sessionId)

  suspend fun savePlan(plan: WorldRequestPlan): WorldRequestPlan = database.withTransaction { plans.savePlan(plan) }

  suspend fun settlePlan(requestId: String, success: Boolean, evidence: String): WorldRequestPlan =
    database.withTransaction { plans.settlePlan(requestId, success, evidence) }

  /** Read-only approval preflight, including a durable cancellation identity check. */
  suspend fun receipt(requestId: String, parameters: String): WorldOperationReceipt? = database.withTransaction {
    worldCheck(worldId(requestId) && parameters.isNotBlank())
    val hash = WorldBookStorageRules.parameterHash(parameters, parameters)
    val dao = database.worldBooks()
    val row = dao.receipt(requestId)
    val cancelled = dao.cancellation(requestId)
    worldCheck(row == null || row.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
    worldCheck(cancelled == null || cancelled.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
    worldCheck(row != null || cancelled == null, "WORLD_REQUEST_CANCELLED")
    row?.let { WorldOperationReceipt(it.requestId, it.operation, it.parameterHash, it.targetId, it.revision) }
  }

  /** Already committed wins; otherwise cancellation survives process death and blocks every later write. */
  suspend fun cancelOperation(requestId: String, parameters: String): Boolean {
    worldCheck(worldId(requestId) && parameters.isNotBlank())
    val hash = WorldBookStorageRules.parameterHash(parameters, parameters)
    WorldBookPendingCancellation.mark(database, requestId, hash)
    try {
      return database.withTransaction {
        val dao = database.worldBooks()
        val committed = dao.receipt(requestId)
        val cancelled = dao.cancellation(requestId)
        worldCheck(committed == null || committed.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
        worldCheck(cancelled == null || cancelled.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
        if (committed != null) return@withTransaction false
        if (cancelled == null) {
          dao.insertCancellation(WorldOperationCancellationEntity(requestId, hash))
        }
        true
      }
    } finally {
      WorldBookPendingCancellation.clear(database, requestId)
    }
  }
}

data class WorldOperationReceipt(
  val requestId: String,
  val operation: String,
  val parameterHash: String,
  val targetId: String,
  val revision: String,
)

fun Context.worldBookRepository(): WorldBookRepository = WorldBookRepository((applicationContext as ZoneuApplication).database)

/** This runs inside session insertion, not after a session becomes visible. */
internal suspend fun WorldBookRepository.initializeSession(sessionId: String, characterIds: List<String>, worldParentId: String?) =
  WorldBookStorageBindings(database).initializeSession(sessionId, characterIds, worldParentId)

package com.zoneu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WorldBookDao : WorldAssetDao, WorldRoleBindingDao, WorldManifestDao, WorldPlanDao, WorldOperationDao

interface WorldAssetDao {
  @Query("SELECT * FROM world_book WHERE id = :id")
  suspend fun head(id: String): WorldBookHeadEntity?

  @Query("SELECT * FROM world_book WHERE deleted = 0 ORDER BY id")
  suspend fun heads(): List<WorldBookHeadEntity>

  @Query("SELECT * FROM world_book WHERE fingerprint = :fingerprint")
  suspend fun fingerprint(fingerprint: String): WorldBookHeadEntity?

  @Query("SELECT * FROM world_revision WHERE bookId = :id AND revision = :revision")
  suspend fun revision(id: String, revision: String): WorldBookRevisionEntity?

  @Insert
  suspend fun insertHead(head: WorldBookHeadEntity)

  @Insert
  suspend fun insertRevision(revision: WorldBookRevisionEntity)

  @Query("UPDATE world_book SET revision = :revision, deleted = :deleted WHERE id = :id AND revision = :expected AND deleted = 0")
  suspend fun advanceHead(id: String, expected: String, revision: String, deleted: Boolean = false): Int

  @Query("SELECT * FROM world_entry_identity WHERE id = :id")
  suspend fun entryIdentity(id: String): WorldBookEntryIdentityEntity?

  @Insert
  suspend fun insertEntryIdentities(entries: List<WorldBookEntryIdentityEntity>)
}

interface WorldOperationDao {
  @Query("SELECT * FROM world_operation_receipt WHERE requestId = :id")
  suspend fun receipt(id: String): WorldOperationReceiptEntity?

  @Insert
  suspend fun insertReceipt(receipt: WorldOperationReceiptEntity)

  @Query("SELECT * FROM world_operation_cancellation WHERE requestId = :id")
  suspend fun cancellation(id: String): WorldOperationCancellationEntity?

  @Insert
  suspend fun insertCancellation(cancellation: WorldOperationCancellationEntity)
}

interface WorldRoleBindingDao {
  @Query("SELECT * FROM world_role_bindings WHERE characterId = :id")
  suspend fun roleBindings(id: String): WorldRoleBindingsEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun putRoleBindings(bindings: WorldRoleBindingsEntity)

  @Query("DELETE FROM world_role_reference WHERE characterId = :id")
  suspend fun clearRoleReferences(id: String)

  @Query("DELETE FROM world_role_bindings WHERE characterId = :id")
  suspend fun clearRoleBindings(id: String)

  @Insert
  suspend fun insertRoleReferences(references: List<WorldRoleReferenceEntity>)

  @Query("SELECT characterId FROM world_role_reference WHERE bookId = :id ORDER BY characterId")
  suspend fun roleUsage(id: String): List<String>
}

interface WorldManifestDao {
  @Query("SELECT * FROM world_manifest WHERE sessionId = :id")
  suspend fun manifest(id: String): WorldManifestEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun putManifest(manifest: WorldManifestEntity)

  @Query("DELETE FROM world_session_reference WHERE sessionId = :id")
  suspend fun clearSessionReferences(id: String)

  @Insert
  suspend fun insertSessionReferences(references: List<WorldSessionReferenceEntity>)

  @Query("SELECT sessionId FROM world_session_reference WHERE bookId = :id ORDER BY sessionId")
  suspend fun sessionUsage(id: String): List<String>
}

interface WorldPlanDao {
  @Query("SELECT * FROM world_runtime_state WHERE sessionId = :id")
  suspend fun runtimeState(id: String): WorldRuntimeStateEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun putRuntimeState(state: WorldRuntimeStateEntity)

  @Query("SELECT * FROM world_request_plan WHERE requestId = :id")
  suspend fun plan(id: String): WorldRequestPlanEntity?

  @Query("SELECT * FROM world_request_plan WHERE sessionId = :id AND status = 'prepared' LIMIT 1")
  suspend fun preparedPlan(id: String): WorldRequestPlanEntity?

  @Insert
  suspend fun insertPlan(plan: WorldRequestPlanEntity)

  @Query(
    "UPDATE world_request_plan SET status = :status, payload = :payload, evidenceHash = :evidence " +
      "WHERE requestId = :id AND status = 'prepared'",
  )
  suspend fun settlePlan(id: String, status: String, payload: String, evidence: String): Int

  /** 已结算的计划只用于回执与诊断；保留最近 [keep] 条，避免长会话把整份 prompt 越堆越多。 */
  @Query(
    "DELETE FROM world_request_plan WHERE sessionId = :sessionId AND requestId NOT IN " +
      "(SELECT requestId FROM world_request_plan WHERE sessionId = :sessionId ORDER BY rowid DESC LIMIT :keep)",
  )
  suspend fun prunePlans(sessionId: String, keep: Int)
}

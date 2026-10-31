package com.zoneu.app.agent

import com.zoneu.app.data.db.SessionKind
import com.zoneu.app.data.db.WorldRequestPlanEntity
import com.zoneu.app.data.db.WorldRuntimeStateEntity
import com.zoneu.app.data.db.ZoneuDatabase
import kotlinx.serialization.encodeToString

internal class WorldBookStoragePlans(private val database: ZoneuDatabase) {
  private val dao = database.worldBooks()

  suspend fun runtimeState(sessionId: String): WorldRuntimeState =
    dao.runtimeState(sessionId)?.let { worldStorageJson.decodeFromString(it.payload) } ?: WorldRuntimeState()

  suspend fun plan(requestId: String): WorldRequestPlan? =
    dao.plan(requestId)?.let { worldStorageJson.decodeFromString(it.payload) }

  suspend fun preparedPlan(sessionId: String): WorldRequestPlan? =
    dao.preparedPlan(sessionId)?.let { worldStorageJson.decodeFromString(it.payload) }

  suspend fun savePlan(plan: WorldRequestPlan): WorldRequestPlan {
    worldCheck(worldId(plan.requestId) && worldId(plan.sessionId))
    val hash = worldPlanHash(plan)
    dao.plan(plan.requestId)?.let {
      worldCheck(it.parameterHash == hash, "WORLD_REQUEST_CONFLICT")
      return worldStorageJson.decodeFromString(it.payload)
    }
    val session = database.store().session(plan.sessionId) ?: throw WorldBookException("WORLD_SESSION_NOT_FOUND")
    worldCheck(session.kind == SessionKind.Roleplay, "WORLD_NOT_ROLEPLAY")
    val revision = dao.manifest(plan.sessionId)?.revision ?: "empty"
    worldCas(revision, plan.bindingRevision)
    worldCheck(plan.status == "prepared", "WORLD_PLAN_INVALID")
    worldCheck(dao.preparedPlan(plan.sessionId) == null, "WORLD_REQUEST_PENDING")
    val state = runtimeState(plan.sessionId)
    worldSettleState(state, plan)
    worldCheck(plan.activated.distinct().size == plan.activated.size, "WORLD_PLAN_INVALID")
    dao.insertPlan(WorldRequestPlanEntity(plan.requestId, plan.sessionId, plan.status, worldStorageJson.encodeToString(plan), hash, null))
    return plan
  }

  suspend fun settlePlan(requestId: String, success: Boolean, evidence: String): WorldRequestPlan {
    val row = dao.plan(requestId) ?: throw WorldBookException("WORLD_PLAN_NOT_FOUND")
    val current: WorldRequestPlan = worldStorageJson.decodeFromString(row.payload)
    if (current.status != "prepared") return current
    worldCheck(!success || evidence.isNotBlank(), "WORLD_EVIDENCE_REQUIRED")
    val saved = current.copy(status = if (success) "settled" else "failed")
    if (success) {
      val state = worldSettleState(runtimeState(current.sessionId), current)
      dao.putRuntimeState(WorldRuntimeStateEntity(current.sessionId, worldStorageJson.encodeToString(state)))
    }
    worldCheck(
      dao.settlePlan(requestId, saved.status, worldStorageJson.encodeToString(saved), worldHash(evidence)) == 1,
      "WORLD_REQUEST_CONFLICT",
    )
    return saved
  }
}

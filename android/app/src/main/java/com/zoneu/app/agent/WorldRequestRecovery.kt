package com.zoneu.app.agent

import android.content.Context
import androidx.room.withTransaction
import com.zoneu.app.node.NodeRuntime
import kotlinx.coroutines.withTimeoutOrNull

/** Recovery is user-initiated: an unknown outcome is never guessed to have succeeded. */
object WorldRequestRecovery {
  suspend fun reconcile(context: Context, sessionId: String, requestId: String): Boolean {
    val plan = context.worldBookRepository().plan(requestId) ?: throw WorldBookException("WORLD_PLAN_NOT_FOUND")
    worldCheck(plan.sessionId == sessionId, "WORLD_IDENTITY_MISMATCH")
    if (plan.status != "prepared") return true
    if (plan.generation != "dsh" || WorldRuntime.binding(requestId) == null) return false
    return withTimeoutOrNull(15_000L) { NodeRuntime.worldControl(context, requestId, cancel = false) } ?: false
  }

  /** Reload first quiesces the old embedded root; failed reload leaves the uncertain plan untouched. */
  suspend fun abandon(context: Context, sessionId: String, requestId: String) {
    val repository = context.worldBookRepository()
    val plan = repository.plan(requestId) ?: throw WorldBookException("WORLD_PLAN_NOT_FOUND")
    worldCheck(plan.sessionId == sessionId && plan.status == "prepared", "WORLD_REQUEST_MISMATCH")
    val admission = NodeRuntime.beginWorldRecovery() ?: throw WorldBookException("WORLD_RUNTIME_BUSY")
    try {
      if (plan.generation == "dsh") {
        val terminal = if (WorldRuntime.binding(requestId) == null) {
          false
        } else {
          withTimeoutOrNull(15_000L) {
            NodeRuntime.worldControl(context, requestId, cancel = true)
          } ?: false
        }
        if (terminal) return
        NodeRuntime.reloadForPreparation(context, admission)
      }
      repository.database.withTransaction {
        val pending = repository.preparedPlan(sessionId)
        worldCheck(pending?.requestId == requestId, "WORLD_REQUEST_MISMATCH")
        repository.settlePlan(requestId, false, "explicit-user-abandon:$sessionId:$requestId")
      }
    } finally {
      NodeRuntime.endTaskPreparation(admission)
    }
  }
}

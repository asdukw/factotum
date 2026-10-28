package com.zoneu.app.schedule

import android.content.Context
import android.util.Log
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.node.NodeRuntime
import com.zoneu.app.node.NodeTaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** alarm 的发生时刻和编辑版本必须同时匹配；领取清除 next_run_at，重复广播不再执行。 */
internal fun canClaim(task: ScheduledTaskEntity, revision: String, planned: Long, now: Long): Boolean =
  task.enabled && task.revision == revision && task.nextRunAt == planned && planned > 0 && now >= planned

object ScheduledTaskRunner {
  private const val TAG = "ZoneuSchedule"

  suspend fun run(context: Context, id: String, revision: String, planned: Long, onForeground: () -> Unit = {}) {
    val app = context.applicationContext as? ZoneuApplication ?: return
    val dao = app.database.scheduledTasks()
    val started = System.currentTimeMillis()
    val task = scheduleGate.withLock {
      val current = dao.byId(id) ?: return@withLock null
      if (!canClaim(current, revision, planned, started)) return@withLock null
      if (dao.claim(id, revision, planned, started) == 1) current else null
    } ?: return
    var foreground = false
    var error: String? = null
    try {
      NodeTaskService.start(app)
      foreground = true
      onForeground()
      NodeRuntime.initialize(app)
      val outcome = NodeRuntime.runTask(NodeRuntime.persistentSessionId(app), task.prompt)
      // 不把模型错误描述写入结果码或日志，避免混入用户内容。
      if (!outcome.ok) error = "TURN_FAILED"
    } catch (cancelled: CancellationException) {
      error = "TASK_CANCELLED"
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") failure: Exception,
    ) {
      Log.w(TAG, "task failed category=${failure.javaClass.simpleName}")
      error = if (foreground) "TASK_FAILED" else "FOREGROUND_SERVICE_FAILED"
    } finally {
      withContext(NonCancellable) {
        try {
          scheduleGate.withLock {
            dao.finish(id, revision, started, error)
            // 编辑动作已负责自己的排期；不以运行前旧快照恢复停用或删除的任务。
            dao.byId(id)?.takeIf { it.revision == revision }?.let { ScheduledTaskScheduler.schedule(app, it) }
          }
        } finally {
          if (foreground) NodeTaskService.stop(app)
        }
      }
      Log.i(TAG, "task $id done error=${error ?: "-"}")
    }
  }
}

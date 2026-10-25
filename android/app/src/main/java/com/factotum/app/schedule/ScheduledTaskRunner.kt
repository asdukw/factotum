package com.factotum.app.schedule

import android.content.Context
import android.util.Log
import com.factotum.app.FactotumApplication
import com.factotum.app.node.NodeRuntime

/**
 * 跑一条定时任务：拉起运行时、提交那句话、把结果记回表里、排下一次。
 *
 * **只记「成功 / 失败码 + 时间」，不记产出**：任务产出会进会话库（用户能看到），
 * 而这张表是给列表页看的 —— 多存一份正文就是多一处会泄漏屏幕内容的地方
 * （与日志「只记计数 / 类名 / 坐标」同一条规矩）。
 *
 * **错过的任务不补跑**：设备关机或深度休眠期间错过的那些，重新排期时按「今天这个点还没到
 * 就今天、否则明天」算 —— 也就是只跑下一次。开机瞬间连跑十条积压的任务比漏掉更糟。
 */
object ScheduledTaskRunner {
  private const val TAG = "FactotumSchedule"

  suspend fun run(context: Context, id: String) {
    val app = context.applicationContext as? FactotumApplication ?: return
    val dao = app.database.scheduledTasks()
    val task = dao.byId(id) ?: return
    if (!task.enabled) return

    val startedAt = System.currentTimeMillis()
    val error =
      runCatching {
        NodeRuntime.initialize(context)
        NodeRuntime.runTask(NodeRuntime.persistentSessionId(context), task.prompt)
      }
        .map { outcome -> if (outcome.ok) null else outcome.turnErrors.firstOrNull() ?: "TURN_FAILED" }
        .getOrElse { throwable -> throwable.javaClass.simpleName }

    dao.recordRun(id, startedAt, error)
    // 一次性闹钟响完就没了 —— 在这里补下一次。放到 runner 里是为了「跑没跑成都排下一次」。
    ScheduledTaskScheduler.schedule(context, task)
    // 只记 id 与结果码：prompt 里可能有用户的私人内容。
    Log.i(TAG, "task $id done error=${error ?: "-"}")
  }
}

package com.factotum.app.schedule

import android.content.Context
import com.factotum.app.FactotumApplication

/**
 * 「有定时任务，但「闹钟和提醒」权限没开」的判据与主动提醒。
 *
 * 这件事不能等失败：没有精确闹钟权限时任务**照样会跑**，只是可能晚 15 分钟以上
 * （`setWindow` 不豁免 Doze）。用户不会看到任何错误，只会觉得「怎么不准」。
 * 所以有两条提醒：聊天页的常驻提示（[com.factotum.app.ui.chat] 的 `ExactAlarmPrompt`）
 * 与每 6 小时轮询一次的通知（[ExactAlarmMonitor]）。
 *
 * 判据是纯函数，单测钉住；[needsExactAlarmReminder] 只负责读库。
 * 权限恢复后由 [ScheduledTaskBootReceiver] 整体重排，把窗口闹钟升级成精确闹钟。
 */
internal fun needsExactAlarmReminder(canScheduleExact: Boolean, enabledTasks: Int): Boolean =
  !canScheduleExact && enabledTasks > 0

/** 当前是否需要提醒：没有精确权限，且至少有一条启用的定时任务。 */
suspend fun needsExactAlarmReminder(context: Context): Boolean =
  needsExactAlarmReminder(
    ScheduledTaskScheduler.canScheduleExact(context),
    enabledTaskCount(context),
  )

/** 启用的定时任务条数（通知正文用；**只报数量，不报标题**）。 */
suspend fun enabledTaskCount(context: Context): Int {
  val app = context.applicationContext as? FactotumApplication ?: return 0
  return runCatching { app.database.scheduledTasks().all().count { it.enabled } }.getOrDefault(0)
}

/** 通知节流：同一件事最多每 [intervalMs] 提醒一次。纯函数，单测钉住。 */
internal fun shouldNotify(needed: Boolean, now: Long, lastNotifiedAt: Long, intervalMs: Long): Boolean =
  needed && (lastNotifiedAt <= 0L || now - lastNotifiedAt >= intervalMs)

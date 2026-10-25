package com.factotum.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.factotum.app.FactotumApplication
import com.factotum.app.data.db.ScheduledTaskEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 定时任务的排期：每天 HH:mm 用 `AlarmManager` 唤醒一次。
 *
 * **Android 没有 cron**（见 `docs/scheduled-tasks.md`）。能靠得住的只有 `AlarmManager`：
 * Doze 下 `setExactAndAllowWhileIdle` 仍会唤醒，但**每个应用约 9 分钟才允许一次**，
 * 所以任务粒度定在「每天一次」—— 再密就不该用闹钟，而该用前台服务。
 *
 * **精确与不精确两档**（这是与无障碍不同的地方：这里**有降级**）：
 * - 有 `SCHEDULE_EXACT_ALARM`（Android 12+ 的特殊权限）→ `setExactAndAllowWhileIdle`；
 * - 没有 → `setWindow`（15 分钟窗口），照样会跑，只是可能晚几分钟。
 *
 * 权限**从不在启动时索要**：只在用户真的建了一条「准点」任务时由 UI 引导一次。
 */
object ScheduledTaskScheduler {
  const val EXTRA_ID = "task_id"

  /** 不精确档的窗口宽度。系统在 [at, at + WINDOW] 之间挑一个不打扰用户的时刻。 */
  private const val WINDOW_MS = 15 * 60_000L

  fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(AlarmManager::class.java) ?: return false
    return manager.canScheduleExactAlarms()
  }

  /** 去系统的「闹钟和提醒」开关页。应用自己打不开这个权限，只能把用户送过去。 */
  fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(
      Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
      android.net.Uri.parse("package:${context.packageName}"),
    )

  /**
   * 下一次触发时刻（纯函数，单测钉住）。
   *
   * 今天这个点还没过就排今天，已经过了就排明天 —— 这样「刚建完一条 8:00 的任务、而现在
   * 是 8:01」不会被排到明天同一时刻以外的奇怪时间。
   */
  fun nextTrigger(hour: Int, minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    val today = local.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    val next = if (today.toInstant().toEpochMilli() > now) today else today.plusDays(1)
    return next.toInstant().toEpochMilli()
  }

  /** 排一条任务的下一次；[task] 未启用时改为取消。 */
  fun schedule(context: Context, task: ScheduledTaskEntity) {
    if (!task.enabled) {
      cancel(context, task.id)
      return
    }
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val at = nextTrigger(task.hour, task.minute, System.currentTimeMillis())
    val pending = pendingIntent(context, task.id)
    try {
      if (canScheduleExact(context)) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
      } else {
        manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
      }
    } catch (error: SecurityException) {
      // 权限在这个瞬间被撤销：降级成窗口闹钟，别让这条任务就这么静默消失。
      // 只记类型 —— 异常消息里可能带 PendingIntent 的细节。
      android.util.Log.w("FactotumSchedule", "exact alarm denied (${error.javaClass.simpleName}); using window")
      manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
    }
  }

  fun cancel(context: Context, id: String) {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    manager.cancel(pendingIntent(context, id))
  }

  /** 开机、改时间、权限变化之后整体重排 —— alarm 不持久化，不重排就再也不会响。 */
  suspend fun scheduleAll(context: Context) {
    val tasks = (context.applicationContext as FactotumApplication).database.scheduledTasks().all()
    tasks.forEach { schedule(context, it) }
  }

  private fun pendingIntent(context: Context, id: String): PendingIntent {
    val intent =
      Intent(context, ScheduledTaskReceiver::class.java)
        .setAction(ScheduledTaskReceiver.ACTION_FIRE)
        .putExtra(EXTRA_ID, id)
    return PendingIntent.getBroadcast(
      context,
      id.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}

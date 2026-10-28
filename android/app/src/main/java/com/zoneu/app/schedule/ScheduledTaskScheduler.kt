package com.zoneu.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.ScheduledTaskEntity
import java.time.ZoneId

/** 四类日历规则均使用一次性 alarm，触发后重新读取最新任务再排下一次。 */
object ScheduledTaskScheduler {
  const val EXTRA_ID = "task_id"
  const val EXTRA_REVISION = "revision"
  const val EXTRA_PLANNED = "planned_at"
  private const val WINDOW_MS = 15 * 60_000L

  fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
  }

  fun exactAlarmSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
    Uri.parse("package:${context.packageName}"),
  )

  /** 旧每日调用保持兼容，所有计算交给纯规则实现。 */
  fun nextTrigger(hour: Int, minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    requireNotNull(ScheduleRule().next(hour, minute, now, zone))

  suspend fun schedule(context: Context, task: ScheduledTaskEntity) {
    if (!task.enabled) {
      cancel(context, task.id)
      return
    }
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val dao = (context.applicationContext as ZoneuApplication).database.scheduledTasks()
    val at = task.rule().next(task.hour, task.minute, System.currentTimeMillis(), ZoneId.systemDefault())
    if (at == null) {
      dao.expire(task.id, task.revision, if (task.rule().valid()) "SCHEDULE_MISSED" else "SCHEDULE_INVALID_RULE")
      cancel(context, task.id)
      return
    }
    if (dao.arm(task.id, task.revision, at) != 1) return
    manager.cancel(pendingIntent(context, task, legacy = true))
    val pending = pendingIntent(context, task.copy(nextRunAt = at))
    try {
      if (canScheduleExact(context)) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
      } else {
        manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
      }
    } catch (_: SecurityException) {
      manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
    }
  }

  fun cancel(context: Context, id: String) {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val identity = ScheduledTaskEntity(id, "", "", 0, 0, false)
    manager.cancel(pendingIntent(context, identity))
    manager.cancel(pendingIntent(context, identity, legacy = true))
  }

  suspend fun scheduleAll(context: Context) {
    val tasks = (context.applicationContext as ZoneuApplication).database.scheduledTasks().all()
    tasks.forEach { schedule(context, it) }
  }

  private fun pendingIntent(context: Context, task: ScheduledTaskEntity, legacy: Boolean = false): PendingIntent {
    val intent = Intent(context, ScheduledTaskReceiver::class.java)
      .setAction(ScheduledTaskReceiver.ACTION_FIRE)
      .putExtra(EXTRA_ID, task.id)
      .putExtra(EXTRA_REVISION, task.revision)
      .putExtra(EXTRA_PLANNED, task.nextRunAt ?: 0)
    if (!legacy) intent.data = Uri.Builder().scheme("zoneu-schedule").authority("task").appendPath(task.id).build()
    return PendingIntent.getBroadcast(
      context,
      task.id.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}

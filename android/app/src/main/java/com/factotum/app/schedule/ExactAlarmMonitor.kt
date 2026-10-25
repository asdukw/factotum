package com.factotum.app.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.factotum.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 权限提醒的轮询与通知。
 *
 * **为什么是 AlarmManager 而不是 WorkManager**：这个检查不需要精确、不需要联网、不需要
 * 重试保证，而 WorkManager 要多一个依赖与一整套数据库。一次非精确的 6 小时闹钟就够；
 * 代价是 Doze 里会推迟到维护窗口 —— 对「提醒你去开权限」完全可以接受。
 *
 * 轮询是**自续期的一次性闹钟**（不是 `setInexactRepeating`）：重复闹钟会在每次
 * `Application.onCreate` 再排时被推后，用户频繁打开 App 时可能永远轮不到。
 *
 * 节流：检查每 6 小时一次，通知最多每 24 小时一次；权限开好或任务删光后同一次检查会
 * 把通知撤掉。正文只报任务数量，不报标题（与日志同一套隐私规矩）。
 */
object ExactAlarmMonitor {
  private const val TAG = "FactotumSchedule"
  private const val CHANNEL_ID = "schedule_permission"
  private const val CHANNEL_NAME = "定时任务权限提醒"
  private const val NOTIFICATION_ID = 4100
  const val ACTION_CHECK = "com.factotum.app.schedule.CHECK_EXACT_ALARM"

  private const val PREFS_NAME = "factotum_schedule_notice"
  private const val KEY_LAST_NOTIFIED = "last_notified_at"
  private const val KEY_NEXT_CHECK = "next_check_at"

  private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
  internal const val NOTIFY_INTERVAL_MS = 24 * 60 * 60 * 1000L

  /**
   * 安排下一次检查。[force] 用于报警被系统清掉的场景（开机）：此时持久化的
   * `next_check_at` 还停在未来，不能拿它当「已经排过」。
   */
  fun schedule(context: Context, force: Boolean = false) {
    val prefs = prefs(context)
    val now = System.currentTimeMillis()
    if (!force && prefs.getLong(KEY_NEXT_CHECK, 0L) > now) return
    val manager = context.getSystemService(AlarmManager::class.java) ?: return
    val at = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
    // 非精确闹钟：不需要 SCHEDULE_EXACT_ALARM —— 要提醒的正是那个权限没开的情况。
    manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
    prefs.edit().putLong(KEY_NEXT_CHECK, now + CHECK_INTERVAL_MS).apply()
  }

  /** 查一次：需要提醒且有任务就发通知，否则把之前的通知撤掉。 */
  suspend fun check(context: Context) {
    val needed = needsExactAlarmReminder(context)
    if (!needed) {
      cancelNotification(context)
      return
    }
    val now = System.currentTimeMillis()
    val last = prefs(context).getLong(KEY_LAST_NOTIFIED, 0L)
    if (!shouldNotify(true, now, last, NOTIFY_INTERVAL_MS)) return
    post(context, enabledTaskCount(context))
    prefs(context).edit().putLong(KEY_LAST_NOTIFIED, now).apply()
    Log.i(TAG, "exact-alarm permission missing; notified")
  }

  private fun post(context: Context, taskCount: Int) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )
    val settings = ScheduledTaskScheduler.exactAlarmSettingsIntent(context)
    val open =
      PendingIntent.getActivity(context, NOTIFICATION_ID, settings, PendingIntent.FLAG_IMMUTABLE)
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_factotum)
        .setContentTitle("定时任务可能不准时")
        .setContentText(
          "有 $taskCount 条定时任务，但「闹钟和提醒」权限未开启：触发可能晚 15 分钟以上。点此去开启。",
        )
        .setStyle(
          NotificationCompat.BigTextStyle().bigText(
            "有 $taskCount 条定时任务已启用，但「闹钟和提醒」权限未开启。" +
              "没有这个权限时任务仍会运行，只是可能晚 15 分钟以上。" +
              "点这里打开系统设置，允许 Factotum 使用精确闹钟。",
          ),
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    runCatching { manager.notify(NOTIFICATION_ID, notification) }
  }

  private fun cancelNotification(context: Context) {
    runCatching {
      context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private fun pending(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
      context,
      0,
      Intent(context, ExactAlarmMonitorReceiver::class.java).setAction(ACTION_CHECK),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** 轮询到点了：先把下一次排上，再查一遍 —— 中途被杀也不会断掉这条链。 */
class ExactAlarmMonitorReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ExactAlarmMonitor.ACTION_CHECK) return
    val app = context.applicationContext
    ExactAlarmMonitor.schedule(app, force = true)
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        ExactAlarmMonitor.check(app)
      } finally {
        pending.finish()
      }
    }
  }
}

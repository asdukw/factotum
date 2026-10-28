package com.zoneu.app.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 闹钟响了：跑那条定时任务。
 *
 * **先把前台服务拉起来再干活**：任务可能跑几分钟，而 `onReceive` 返回后没有前台服务的话
 * 进程会被系统直接回收 —— 表现是「任务跑了一半没了」，而且没有任何报错。
 *
 * 下一次排期在 [ScheduledTaskRunner] 里补（`AlarmManager` 的一次性闹钟响完就没了）。
 */
class ScheduledTaskReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_FIRE) return
    val id = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_ID) ?: return
    val revision = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_REVISION) ?: return
    val planned = intent.getLongExtra(ScheduledTaskScheduler.EXTRA_PLANNED, 0)
    val app = context.applicationContext
    val pending = goAsync()
    val released = AtomicBoolean(false)

    fun release() {
      if (released.compareAndSet(false, true)) pending.finish()
    }
    CoroutineScope(Dispatchers.IO).launch {
      try {
        ScheduledTaskRunner.run(app, id, revision, planned, ::release)
      } finally {
        release()
      }
    }
  }

  companion object {
    const val ACTION_FIRE = "com.zoneu.app.schedule.FIRE"
  }
}

/**
 * 开机 / 改时间 / 换时区之后整体重排。
 *
 * `AlarmManager` 的闹钟**不持久化**：重启就全没了；改了系统时间，按绝对时间排的闹钟也会错位。
 * 不重排的表现是「任务安安静静再也不会响」—— 这是这类功能最常见的一种坏法。
 */
class ScheduledTaskBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val relevant =
      intent.action == Intent.ACTION_BOOT_COMPLETED ||
        intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
        intent.action == Intent.ACTION_TIME_CHANGED ||
        intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
        // 用户刚在系统设置里打开了「闹钟和提醒」：把已有的窗口闹钟重排成精确闹钟。
        // 不重排的话，之前建的每条任务都会继续晚 15 分钟内，直到下一次编辑。
        (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    if (!relevant) return
    val app = context.applicationContext
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        runCatching { scheduleGate.withLock { ScheduledTaskScheduler.scheduleAll(app) } }
        // 开机 / 改时间也会清掉非精确闹钟 —— 权限提醒的轮询一并重排。
        runCatching { ExactAlarmMonitor.schedule(app, force = true) }
      } finally {
        pending.finish()
      }
    }
  }
}

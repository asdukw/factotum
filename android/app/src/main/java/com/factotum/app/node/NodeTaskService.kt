package com.factotum.app.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.factotum.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 任务期间的前台服务：ColorOS 会冻结后台进程，跨 App 任务必须靠它保活。
 * 不承载逻辑，只负责在任务期间持有前台通知；任务结束（idle）后停止。
 *
 * start/stop 成对使用，内部按引用计数：谁最后放手谁停服务。
 */
class NodeTaskService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW),
    )
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  private fun buildNotification(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Factotum")
      .setContentText("正在执行任务…")
      .setSmallIcon(R.drawable.ic_stat_factotum)
      .setOngoing(true)
      .build()

  companion object {
    private const val CHANNEL_ID = "factotum_task"
    private const val NOTIFICATION_ID = 1

    // 引用计数：聊天任务和 Web 预览都会「开始/停止」自己那一份，
    // 后开的 Web 面板关掉时不能把正在跑的任务的保活一起停掉。
    private val holders = AtomicInteger(0)

    fun start(context: Context) {
      // 计数只在服务**真的起来了**之后才加：Android 12+ 从后台广播里 startForegroundService
      // 会抛 ForegroundServiceStartNotAllowedException，先加后调的话计数会永久偏一位，
      // 之后那次 stop 就停不掉正在跑的服务。
      synchronized(holders) {
        if (holders.get() == 0) {
          ContextCompat.startForegroundService(context, Intent(context, NodeTaskService::class.java))
        }
        holders.incrementAndGet()
      }
    }

    fun stop(context: Context) {
      if (holders.updateAndGet { current -> (current - 1).coerceAtLeast(0) } != 0) return
      context.stopService(Intent(context, NodeTaskService::class.java))
    }
  }
}

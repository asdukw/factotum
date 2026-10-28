package com.zoneu.app.connector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zoneu.app.MainActivity
import com.zoneu.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 浏览器授权期间保护回环监听；与 Node 任务服务独立，结束即停止。 */
class GmailAuthorizationService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var leaseId = 0L
  private var deadline: Runnable? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(
      NotificationChannel(CHANNEL, "Gmail 连接", NotificationManager.IMPORTANCE_LOW),
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val id = intent?.getLongExtra(EXTRA_LEASE, 0) ?: 0
    if (intent?.action == ACTION_CANCEL) {
      if (id == leaseId && leases.current(id) != null) {
        leases.stop(id, "AUTH_CANCELLED")
        stopSelf()
      }
      return START_NOT_STICKY
    }
    val lease = leases.current(id)
    if (lease == null) {
      if (leases.current(leaseId) == null) stopSelf(startId)
      return START_NOT_STICKY
    }
    leaseId = id
    runCatching { promote(id) }
      .onSuccess {
        deadline?.let(handler::removeCallbacks)
        deadline = Runnable { stopAttempt("TIMEOUT") }.also { handler.postDelayed(it, AUTH_TIMEOUT_MS) }
        lease.ready.complete(Unit)
      }
      .onFailure {
        leases.stop(id, "AUTH_SERVICE_FAILED")
        stopSelf(startId)
      }
    return START_NOT_STICKY
  }

  private fun promote(id: Long) {
    val back = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val cancel = PendingIntent.getService(
      this,
      id.toInt(),
      Intent(this, GmailAuthorizationService::class.java)
        .setAction(ACTION_CANCEL).putExtra(EXTRA_LEASE, id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_stat_zoneu)
      .setContentTitle("正在连接 Gmail")
      .setContentText("请在浏览器完成授权，完成后返回 zoneu 查看结果")
      .setContentIntent(back)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .addAction(0, "取消连接", cancel)
      .build()
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun stopAttempt(code: String) {
    leases.stop(leaseId, code)
    stopSelf()
  }

  override fun onTimeout(startId: Int) = stopAttempt("AUTH_SERVICE_FAILED")

  override fun onTimeout(startId: Int, fgsType: Int) = stopAttempt("AUTH_SERVICE_FAILED")

  override fun onDestroy() {
    deadline?.let(handler::removeCallbacks)
    leases.stop(leaseId, "AUTH_SERVICE_STOPPED")
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "zoneu_gmail_authorization"
    private const val NOTIFICATION_ID = 7401
    private const val EXTRA_LEASE = "authorization_attempt"
    private const val ACTION_CANCEL = "com.zoneu.app.connector.CANCEL_AUTHORIZATION"
    private const val AUTH_TIMEOUT_MS = 10 * 60 * 1000L
    private val leases = GmailAuthorizationLease()

    /** 前台通知就绪后才运行授权；服务失败不退回无保活的后台监听。 */
    internal suspend fun <T> during(context: Context, block: suspend () -> T): T {
      val app = context.applicationContext
      val lease = withContext(NonCancellable + Dispatchers.Main.immediate) { leases.begin() }
      try {
        withContext(Dispatchers.Main.immediate) {
          try {
            ContextCompat.startForegroundService(
              app,
              Intent(app, GmailAuthorizationService::class.java).putExtra(EXTRA_LEASE, lease.id),
            )
          } catch (_: IllegalStateException) {
            throw GmailAuthorizationException("AUTH_SERVICE_FAILED")
          } catch (_: SecurityException) {
            throw GmailAuthorizationException("AUTH_SERVICE_FAILED")
          }
        }
        return lease.protect(block)
      } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
          if (leases.release(lease.id)) app.stopService(Intent(app, GmailAuthorizationService::class.java))
        }
      }
    }
  }
}

package com.tappet.app.device

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tappet.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * 静默写操作的回执：日历 / 闹钟建好之后，屏幕上必须有东西告诉用户「刚刚发生了什么」。
 *
 * 两条渲染路径，同一份内容：
 *
 * 1. **Android 16+ 的 Live Updates**（`setRequestPromotedOngoing` + `setShortCriticalText`）——
 *    ColorOS 把这类通知渲染成**流体云**（状态栏胶囊）。这是平台 API，不依赖任何 OPPO SDK，
 *    换到别的 ROM 只是退化成普通通知。
 * 2. 普通通知：不支持 promotion 或用户关了 Live Updates 时用，自动消失。
 *
 * **必须用 `IMPORTANCE_HIGH`**：静默写入的代价是屏幕上什么都没发生，回执要是再不出声、不抬头，
 * 用户根本不会注意到「它替我改了日历」。低优先级通道既不会抬头也不会被 promote。
 *
 * 通道 id 带 `_v2`：Android 不允许改已存在通道的重要性，`device_receipts` 那版在真机上已经建过，
 * 沿用旧 id 的话这次改动不会生效。旧通道留在系统里无害。
 *
 * promotion 要求通知是 ongoing 的 —— 所以卡片带一个「关闭」动作，否则用户划不掉。
 */
object DeviceNotices {
  private const val CHANNEL_ID = "device_receipts_v2"
  private const val CHANNEL_NAME = "设备动作回执"
  const val ACTION_DISMISS = "com.tappet.app.action.DISMISS_NOTICE"
  const val EXTRA_ID = "notification_id"

  private val nextId = AtomicInteger(4200)

  /** 只记标题与时间，**不记屏幕或聊天内容**。失败静默：回执发不出去不该让动作报错。 */
  fun created(context: Context, title: String, detail: String) {
    runCatching { post(context, title, detail) }
  }

  private fun post(context: Context, title: String, detail: String) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
    )
    val id = nextId.getAndIncrement()
    val open =
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE)
      }
    val dismiss =
      PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, NoticeDismissReceiver::class.java)
          .setAction(ACTION_DISMISS)
          .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE,
      )
    // promotion 走的是 Android 16 QPR1 起的小版本 API（`SDK_INT_FULL`），不是 36.0：
    // 只用 `SDK_INT >= 36` 判断会在 36.0 的设备上抛 NoSuchMethodError。ColorOS 17 是 37。
    val promoted =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
        manager.canPostPromotedNotifications()
    val notification =
      if (promoted) {
        promoted(context, title, detail, open, dismiss)
      } else {
        plain(context, title, detail, open)
      }
    manager.notify(id, notification)
  }

  /**
   * 流体云走这条：ongoing + 状态栏短文案，由系统/ROM 决定渲染成胶囊。
   *
   * `@SuppressLint("NewApi")` 是必要的：调用点在 `SDK_INT_FULL >= BAKLAVA_1` 之后，
   * 这条守卫是真的（36.0 的设备没有 promote API），但 lint 的 NewApi 不跟着 `SDK_INT_FULL`
   * 收窄。真机上唯一的入口是 [post] 里那次判断。
   */
  @SuppressLint("NewApi")
  private fun promoted(
    context: Context,
    title: String,
    detail: String,
    open: PendingIntent?,
    dismiss: PendingIntent,
  ): Notification {
    val builder =
      Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_tappet)
        .setContentTitle(title)
        .setContentText(detail)
        // 胶囊上只有十来个字，取 detail 里最有信息量的前半段。
        .setShortCriticalText(detail.take(12))
        .setOngoing(true)
        .setRequestPromotedOngoing(true)
        .addAction(Notification.Action.Builder(null, "关闭", dismiss).build())
    open?.let { builder.setContentIntent(it) }
    return builder.build()
  }

  /** 没有 promotion 时的回执：**要抬头**（v2 通道 + PRIORITY_HIGH），否则用户看不到。 */
  private fun plain(
    context: Context,
    title: String,
    detail: String,
    open: PendingIntent?,
  ): Notification {
    val builder =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_tappet)
        .setContentTitle(title)
        .setContentText(detail)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setAutoCancel(true)
    open?.let { builder.setContentIntent(it) }
    return builder.build()
  }
}

/** 「关闭」动作：ongoing 通知划不掉，只能由自己取消。 */
class NoticeDismissReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getIntExtra(DeviceNotices.EXTRA_ID, 0)
    if (id != 0) context.getSystemService(NotificationManager::class.java)?.cancel(id)
  }
}

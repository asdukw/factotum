package com.tappet.app.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract

/**
 * 不经过无障碍的两个动作：**设置闹钟**与**新建日程**。
 *
 * 它们只做一件事：发一个系统 intent，把预填好的内容交给对应的系统 App，由用户点确认。
 * 三个刻意的性质：
 *
 * 1. **不需要任何权限** —— 直接写 `ContentResolver` 建日程要 `WRITE_CALENDAR` 运行时权限，
 *    而「可选功能」不该为了建一条日程先要一个账号级权限；闹钟要「静默设置」则要
 *    `SET_ALARM`，同理。
 * 2. **不需要无障碍** —— 和读屏 / 点击那条路正交。桥在分发时先看这两个动作，
 *    不会因为无障碍没开而失败（`BridgeHealth` 也就不会被它们污染）。
 * 3. **确认留在系统 App 里** —— 我们不替用户按下「保存」。所以结果是「已打开并预填」，
 *    不是「已创建」；返回文案必须这么说，否则模型会以为事情办完了。
 */
object SystemIntents {
  /**
   * `CalendarContract` 里没有公开这个常量（`Events` 只有 `CONTENT_URI`），但日历 App 的
   * `ACTION_INSERT` intent-filter 就是按这个 MIME 声明的（实测 Google 日历、Etar 都是）。
   */
  private const val EVENT_CONTENT_TYPE = "vnd.android.cursor.dir/event"

  fun setAlarm(context: Context, action: DeviceAction.SetAlarm): DeviceActionResult {
    if (action.hour !in 0..23 || action.minute !in 0..59) {
      return DeviceActionResult(ok = false, summary = "时间不合法：小时 0–23、分钟 0–59", code = "BAD_TIME")
    }
    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, action.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
        action.label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        // 故意不走静默设置：让用户在时钟 App 里看见并确认，也避免误设。
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val clock = "%02d:%02d".format(action.hour, action.minute)
    return launch(
      context,
      intent,
      "已打开时钟 App 并预填 $clock 的闹钟 —— 还需要在系统界面里点「确定」才会真正设置。",
    )
  }

  fun addCalendarEvent(context: Context, action: DeviceAction.AddCalendarEvent): DeviceActionResult {
    if (action.title.isBlank()) {
      return DeviceActionResult(ok = false, summary = "日程标题不能为空", code = "BAD_TITLE")
    }
    if (action.endMillis <= action.beginMillis) {
      return DeviceActionResult(ok = false, summary = "结束时间必须晚于开始时间", code = "BAD_TIME")
    }
    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        // 显式给 MIME：不给的话 AMS 解析时会拿**我们进程的身份**去问日历 provider 要类型，
        // 而我们没有 READ_CALENDAR → 每次都在 logcat 里留一条 Permission Denial，
        // 解析只能靠 scheme 兜底。给了类型就不查 provider（模拟器实测）。
        type = EVENT_CONTENT_TYPE
        putExtra(CalendarContract.Events.TITLE, action.title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.beginMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, action.endMillis)
        action.location?.takeIf { it.isNotBlank() }?.let {
          putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        action.description?.takeIf { it.isNotBlank() }?.let {
          putExtra(CalendarContract.Events.DESCRIPTION, it)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return launch(
      context,
      intent,
      "已打开日历 App 并预填「${action.title}」—— 还需要在系统界面里点保存，日程才会被创建。",
    )
  }

  /** 发 intent。没有能处理它的 App 时如实说「这台设备上没有可用的 App」，不假装成功。 */
  private fun launch(context: Context, intent: Intent, summary: String): DeviceActionResult =
    try {
      context.startActivity(intent)
      DeviceActionResult(ok = true, summary = summary)
    } catch (error: ActivityNotFoundException) {
      // 只记类型：异常消息里可能带 intent 的 extras。
      android.util.Log.w("TappetDevice", "intent not handled: ${error.javaClass.simpleName}")
      DeviceActionResult(
        ok = false,
        summary = "这台设备上没有能处理这个操作的 App（${intent.action ?: "intent"}）",
        code = "NO_APP",
      )
    }
}

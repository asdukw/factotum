package com.tappet.app.device

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * 不经过无障碍的两个动作：**设置闹钟**与**新建日程**。
 *
 * 两条都是静默的：闹钟靠 `EXTRA_SKIP_UI`，日程直接写 `CalendarContract`。共同的动机是
 * 「屏幕上没有可点的地方」也能完成的事，不该被逼着走界面。
 *
 * 三个刻意的性质：
 *
 * 1. **日历需要 `READ_CALENDAR` / `WRITE_CALENDAR` 运行时权限**（读只为拿到可写日历的 id）。
 *    没权限时回 [DeviceAction.CODE_CALENDAR_PERMISSION]，由 UI 弹一次授权，模型不重试。
 * 2. **不需要无障碍** —— 与读屏 / 点击那条路正交。桥分发时先看这两个动作，
 *    不会因为无障碍没开而失败（`BridgeHealth` 也就不会被它们污染）。
 * 3. **成功之后必须有回执** —— 静默写入屏幕上什么都没有，所以走 [DeviceNotices]：
 *    ColorOS 支持时是流体云卡片，否则是普通通知。没有回执的静默写等于背着用户改数据。
 */
object SystemIntents {
  /** 一次最多列多少条：日历里一次把几十条塞进上下文既贵又没用。 */
  private const val MAX_LISTED_EVENTS = 50

  fun setAlarm(context: Context, action: DeviceAction.SetAlarm): DeviceActionResult {
    if (action.hour !in 0..23 || action.minute !in 0..59) {
      return DeviceActionResult(ok = false, summary = "时间不合法：小时 0–23、分钟 0–59", code = "BAD_TIME")
    }
    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, action.hour)
        putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
        action.label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        // 静默：不弹确认界面。这个标记是**请求**不是保证 —— 系统时钟通常照办，
        // 忽略它的第三方时钟会退化成停在确认页，所以返回文案不说「已设置」。
        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    val clock = "%02d:%02d".format(action.hour, action.minute)
    val result = launch(context, intent, "已静默设置 $clock 的闹钟。")
    if (result.ok) DeviceNotices.created(context, "已设置闹钟", "$clock ${action.label.orEmpty()}".trim())
    return result
  }

  fun addCalendarEvent(context: Context, action: DeviceAction.AddCalendarEvent): DeviceActionResult {
    if (action.title.isBlank()) {
      return DeviceActionResult(ok = false, summary = "日程标题不能为空", code = "BAD_TITLE")
    }
    if (action.endMillis <= action.beginMillis) {
      return DeviceActionResult(ok = false, summary = "结束时间必须晚于开始时间", code = "BAD_TIME")
    }
    // 建日程既要写（insert）也要读（查可写日历的 id），两条权限缺一不可。
    if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR) ||
      !hasPermission(context, Manifest.permission.READ_CALENDAR)
    ) {
      return permissionFailure()
    }
    val resolver = context.contentResolver
    val calendarId =
      writableCalendarId(resolver)
        ?: return DeviceActionResult(
          ok = false,
          summary = "这台设备上没有可写的日历（没登录账号，或日历全是只读的）。让用户在日历 App 里建一个本地日历。",
          code = DeviceAction.CODE_NO_WRITABLE_CALENDAR,
        )
    val values = calendarEventValues(action, calendarId, TimeZone.getDefault().id)
    val uri =
      runCatching { resolver.insert(CalendarContract.Events.CONTENT_URI, values.toContentValues()) }
        .getOrNull()
    val eventId = uri?.lastPathSegment?.toLongOrNull()
    if (uri == null || eventId == null) {
      return DeviceActionResult(
        ok = false,
        summary = "写入日历时系统拒绝了这次插入",
        code = "CALENDAR_INSERT_FAILED",
      )
    }
    if (action.reminderMinutes != null) addReminder(resolver, eventId, action.reminderMinutes)
    val range = formatEventRange(action.beginMillis, action.endMillis, ZoneId.systemDefault())
    val reminder = action.reminderMinutes?.let { "，提前 $it 分钟提醒" } ?: ""
    DeviceNotices.created(context, "已创建日程", "${action.title} · $range")
    return DeviceActionResult(
      ok = true,
      summary = "已创建日程「${action.title}」$range$reminder",
      code = "CREATED",
    )
  }

  /** 提前提醒。插失败不影响日程本身 —— 事件已经建好了。 */
  private fun addReminder(resolver: ContentResolver, eventId: Long, minutes: Int) {
    val values =
      ContentValues().apply {
        put(CalendarContract.Reminders.EVENT_ID, eventId)
        put(CalendarContract.Reminders.MINUTES, minutes)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
      }
    runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, values) }
  }

  /**
   * 列出时间窗内的日程。
   *
   * 这是改 / 删的前置：模型必须先拿到真实的 event id。没有它，模型只能去日历界面里翻
   * ——真机上实测为了改一条日程按了 27 次 UI。
   */
  fun listCalendarEvents(context: Context, action: DeviceAction.ListCalendarEvents): DeviceActionResult {
    if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) return permissionFailure()
    if (action.endMillis <= action.beginMillis) {
      return DeviceActionResult(ok = false, summary = "时间窗不合法：结束必须晚于开始", code = "BAD_TIME")
    }
    val projection =
      arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
        CalendarContract.Events.EVENT_LOCATION,
        CalendarContract.Events.ALL_DAY,
      )
    val selection =
      "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? " +
        "AND ${CalendarContract.Events.DELETED} = 0"
    val args = arrayOf(action.beginMillis.toString(), action.endMillis.toString())
    val rows = mutableListOf<String>()
    var total = 0
    runCatching {
      context.contentResolver
        .query(
          CalendarContract.Events.CONTENT_URI,
          projection,
          selection,
          args,
          "${CalendarContract.Events.DTSTART} ASC",
        )
        ?.use { cursor ->
          total = cursor.count
          while (cursor.moveToNext() && rows.size < MAX_LISTED_EVENTS) {
            rows += formatEventRow(cursor)
          }
        }
    }
    if (rows.isEmpty()) {
      return DeviceActionResult(ok = true, summary = "这段时间没有日程", payload = "（没有日程）")
    }
    val more = if (total > rows.size) "\n（还有 ${total - rows.size} 条未显示，缩小时间窗再看）" else ""
    return DeviceActionResult(
      ok = true,
      summary = "读到 $total 条日程",
      payload = rows.joinToString("\n") + more,
    )
  }

  /** 修改日程。只改传进来的字段；全量替换会把模型没提到的字段一起抹掉。 */
  fun updateCalendarEvent(context: Context, action: DeviceAction.UpdateCalendarEvent): DeviceActionResult {
    if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) return permissionFailure()
    val values = calendarUpdateValues(action)
    if (values.isEmpty()) {
      return DeviceActionResult(ok = false, summary = "没有要修改的字段", code = "BAD_PARAMS")
    }
    val resolver = context.contentResolver
    val before = eventRow(resolver, action.eventId)
      ?: return DeviceActionResult(ok = false, summary = "日程 ${action.eventId} 不存在（可能已被删除）", code = DeviceAction.CODE_EVENT_NOT_FOUND)
    val updated =
      runCatching {
        resolver.update(
          CalendarContract.Events.CONTENT_URI,
          values.toContentValues(),
          "${CalendarContract.Events._ID} = ?",
          arrayOf(action.eventId.toString()),
        )
      }.getOrNull()
    if (updated == null || updated == 0) {
      return DeviceActionResult(ok = false, summary = "系统拒绝了这次修改", code = "CALENDAR_UPDATE_FAILED")
    }
    action.reminderMinutes?.let { replaceReminder(resolver, action.eventId, it) }
    val range = formatEventRange(before.begin, before.end, ZoneId.systemDefault())
    DeviceNotices.created(context, "已更新日程", "${before.title} · $range")
    return DeviceActionResult(ok = true, summary = "已更新日程「${before.title}」$range", code = "UPDATED")
  }

  /** 删除日程。先查一行拿标题做回执，再删；查不到就是 id 不对，不猜。 */
  fun deleteCalendarEvent(context: Context, action: DeviceAction.DeleteCalendarEvent): DeviceActionResult {
    if (!hasPermission(context, Manifest.permission.WRITE_CALENDAR)) return permissionFailure()
    val resolver = context.contentResolver
    val row =
      eventRow(resolver, action.eventId)
        ?: return DeviceActionResult(ok = false, summary = "日程 ${action.eventId} 不存在", code = DeviceAction.CODE_EVENT_NOT_FOUND)
    val deleted =
      runCatching {
        resolver.delete(
          CalendarContract.Events.CONTENT_URI,
          "${CalendarContract.Events._ID} = ?",
          arrayOf(action.eventId.toString()),
        )
      }.getOrNull()
    if (deleted == null || deleted == 0) {
      return DeviceActionResult(ok = false, summary = "系统拒绝了这次删除", code = "CALENDAR_DELETE_FAILED")
    }
    DeviceNotices.created(context, "已删除日程", row.title)
    return DeviceActionResult(ok = true, summary = "已删除日程「${row.title}」", code = "DELETED")
  }

  /** 改提醒 = 先删旧的再插新的，否则改一次多一条。 */
  private fun replaceReminder(resolver: ContentResolver, eventId: Long, minutes: Int) {
    runCatching {
      resolver.delete(
        CalendarContract.Reminders.CONTENT_URI,
        "${CalendarContract.Reminders.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
      )
    }
    if (minutes > 0) addReminder(resolver, eventId, minutes)
  }

  private data class EventRow(val title: String, val begin: Long, val end: Long)

  private fun eventRow(resolver: ContentResolver, eventId: Long): EventRow? =
    runCatching {
      resolver
        .query(
          CalendarContract.Events.CONTENT_URI,
          arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
          ),
          "${CalendarContract.Events._ID} = ?",
          arrayOf(eventId.toString()),
          null,
        )
        ?.use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          EventRow(
            title = cursor.getString(0) ?: "（无标题）",
            begin = cursor.getLong(1),
            end = cursor.getLong(2),
          )
        }
    }.getOrNull()

  private fun formatEventRow(cursor: android.database.Cursor): String {
    val id = cursor.getLong(0)
    val title = cursor.getString(1) ?: "（无标题）"
    val begin = cursor.getLong(2)
    val end = cursor.getLong(3)
    val location = cursor.getString(4)?.takeIf { it.isNotBlank() }
    val allDay = cursor.getInt(5) != 0
    val range =
      if (allDay) {
        Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate().toString() + " 全天"
      } else {
        formatEventRange(begin, end, ZoneId.systemDefault())
      }
    return "[$id] $title · $range" + (location?.let { " · $it" } ?: "")
  }

  private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun permissionFailure(): DeviceActionResult =
    DeviceActionResult(
      ok = false,
      summary = "没有日历权限：App 里已经弹出授权提示，等用户授权后再调用一次。授权前不要反复重试。",
      code = DeviceAction.CODE_CALENDAR_PERMISSION,
    )

  /**
   * 选一个能写的日历：优先主日历，其次 id 最小的。`CAL_ACCESS_CONTRIBUTOR` 是「能加事件」的下限，
   * 只读日历（订阅的节假日之类）必须排除，否则插入会被系统拒绝。
   */
  private fun writableCalendarId(resolver: ContentResolver): Long? {
    val projection = arrayOf(CalendarContract.Calendars._ID)
    val selection =
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
        "${CalendarContract.Calendars.VISIBLE} = 1"
    val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
    val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
    return runCatching {
      resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, order)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()
  }

  /** 发 intent。没有能处理它的 App 时如实说「这台设备上没有可用的 App」，不假装成功。 */
  private fun launch(context: Context, intent: Intent, summary: String): DeviceActionResult =
    try {
      context.startActivity(intent)
      DeviceActionResult(ok = true, summary = summary)
    } catch (error: android.content.ActivityNotFoundException) {
      // 只记类型：异常消息里可能带 intent 的 extras。
      android.util.Log.w("TappetDevice", "intent not handled: ${error.javaClass.simpleName}")
      DeviceActionResult(
        ok = false,
        summary = "这台设备上没有能处理这个操作的 App（${intent.action ?: "intent"}）",
        code = "NO_APP",
      )
    }
}

/**
 * 日程 → `CalendarContract.Events` 的列值。抽成纯函数是为了能在 JVM 上单测：
 * `ContentValues` 在单测里是比不了的 Android 桩，而**列名写错在真机上只是静默丢字段**。
 */
internal fun calendarEventValues(
  event: DeviceAction.AddCalendarEvent,
  calendarId: Long,
  timeZoneId: String,
): Map<String, Any?> =
  buildMap {
    put(CalendarContract.Events.CALENDAR_ID, calendarId)
    put(CalendarContract.Events.TITLE, event.title)
    put(CalendarContract.Events.DTSTART, event.beginMillis)
    put(CalendarContract.Events.DTEND, event.endMillis)
    put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
    put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes != null) 1 else 0)
    event.location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
    event.description?.takeIf { it.isNotBlank() }?.let {
      put(CalendarContract.Events.DESCRIPTION, it)
    }
  }

/**
 * 修改日程的列值：null 表示「这一项不改」。
 *
 * 纯函数，理由同 [calendarEventValues] —— 列名错了在真机上是静默不生效。
 * 注意 **CALENDAR_ID 不在其中**：改日程不换日历，换日历等于搬走别人的数据。
 */
internal fun calendarUpdateValues(event: DeviceAction.UpdateCalendarEvent): Map<String, Any?> =
  buildMap {
    event.title?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.TITLE, it) }
    event.beginMillis?.let { put(CalendarContract.Events.DTSTART, it) }
    event.endMillis?.let { put(CalendarContract.Events.DTEND, it) }
    event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
    event.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
  }

/** `2026-10-26 15:00–16:00`；跨天时两端都带日期，避免「23:00–次日 01:00」读成同一天。 */
internal fun formatEventRange(beginMillis: Long, endMillis: Long, zone: ZoneId): String {
  val begin = Instant.ofEpochMilli(beginMillis).atZone(zone)
  val end = Instant.ofEpochMilli(endMillis).atZone(zone)
  val endPattern = if (begin.toLocalDate() == end.toLocalDate()) "HH:mm" else "yyyy-MM-dd HH:mm"
  val beginText = begin.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
  return "$beginText–" + end.format(DateTimeFormatter.ofPattern(endPattern))
}

/** Map → ContentValues。类型只有三种，够用且不引入 reflection。 */
internal fun Map<String, Any?>.toContentValues(): ContentValues =
  ContentValues().apply {
    forEach { (key, value) ->
      when (value) {
        null -> Unit
        is Long -> put(key, value)
        is Int -> put(key, value)
        is String -> put(key, value)
        else -> put(key, value.toString())
      }
    }
  }

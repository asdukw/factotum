package com.tappet.app.device

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 静默建日程的两个纯函数。
 *
 * 为什么值得单测：列名写错在真机上是**静默丢字段** —— 事件建出来了，标题或时间没写进去，
 * 而调用方只会看到「已创建」。这类错误只有在这里才看得见。
 */
class CalendarEventValuesTest {
  private val event =
    DeviceAction.AddCalendarEvent(
      title = "理发",
      beginMillis = 1_790_000_000_000,
      endMillis = 1_790_003_600_000,
      location = "楼下",
      description = "带会员卡",
      reminderMinutes = 15,
    )

  @Test
  fun `maps the fields CalendarContract expects`() {
    val values = calendarEventValues(event, calendarId = 7, timeZoneId = "Asia/Shanghai")

    assertEquals(7L, values[CalendarContract.Events.CALENDAR_ID])
    assertEquals("理发", values[CalendarContract.Events.TITLE])
    assertEquals(event.beginMillis, values[CalendarContract.Events.DTSTART])
    assertEquals(event.endMillis, values[CalendarContract.Events.DTEND])
    assertEquals("Asia/Shanghai", values[CalendarContract.Events.EVENT_TIMEZONE])
    assertEquals("楼下", values[CalendarContract.Events.EVENT_LOCATION])
    assertEquals("带会员卡", values[CalendarContract.Events.DESCRIPTION])
    // 有提醒就必须把 HAS_ALARM 置 1，否则日历不排提醒，Reminders 那行等于白插。
    assertEquals(1, values[CalendarContract.Events.HAS_ALARM])
  }

  @Test
  fun `omits blank optional fields and clears HAS_ALARM without a reminder`() {
    val bare =
      DeviceAction.AddCalendarEvent(
        title = "无提醒",
        beginMillis = 1_790_000_000_000,
        endMillis = 1_790_003_600_000,
        location = "  ",
        description = "",
        reminderMinutes = null,
      )
    val values = calendarEventValues(bare, calendarId = 1, timeZoneId = "UTC")

    assertFalse(values.containsKey(CalendarContract.Events.EVENT_LOCATION))
    assertFalse(values.containsKey(CalendarContract.Events.DESCRIPTION))
    assertEquals(0, values[CalendarContract.Events.HAS_ALARM])
  }

  @Test
  fun `formats a same-day range without repeating the date`() {
    val zone = ZoneId.of("Asia/Shanghai")
    val begin = java.time.LocalDateTime.of(2026, 10, 26, 15, 0).atZone(zone).toInstant().toEpochMilli()
    val end = java.time.LocalDateTime.of(2026, 10, 26, 16, 0).atZone(zone).toInstant().toEpochMilli()

    val text = formatEventRange(begin, end, zone)

    assertEquals("2026-10-26 15:00–16:00", text)
    assertTrue(text.contains("2026-10-26"))
  }

  @Test
  fun `keeps both dates when the range crosses midnight`() {
    val zone = ZoneId.of("Asia/Shanghai")
    val begin = java.time.LocalDateTime.of(2026, 10, 26, 23, 0).atZone(zone).toInstant().toEpochMilli()
    val end = java.time.LocalDateTime.of(2026, 10, 27, 1, 0).atZone(zone).toInstant().toEpochMilli()

    assertEquals("2026-10-26 23:00–2026-10-27 01:00", formatEventRange(begin, end, zone))
  }

  /** 改日程只写传进来的字段：把没传的也写一遍等于用 null 覆盖，会把标题或地点抹掉。 */
  @Test
  fun `update maps only the fields that were passed`() {
    val values =
      calendarUpdateValues(
        DeviceAction.UpdateCalendarEvent(eventId = 5, title = "新标题", reminderMinutes = 30),
      )

    assertEquals("新标题", values[CalendarContract.Events.TITLE])
    assertFalse(values.containsKey(CalendarContract.Events.DTSTART))
    assertFalse(values.containsKey(CalendarContract.Events.DTEND))
    // 改日程不换日历 —— CALENDAR_ID 不在更新字段里。
    assertFalse(values.containsKey(CalendarContract.Events.CALENDAR_ID))
    // 提醒走 Reminders 表，不是 Events 的列。
    assertFalse(values.containsKey("reminder_minutes"))
  }
}

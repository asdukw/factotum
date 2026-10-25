package com.factotum.app.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 定时任务里**能离开设备验证**的两块：下一次触发时刻的计算，与时间输入的解析。
 *
 * 这两处错起来都不报错、只是行为不对：排错一天 → 任务在奇怪的时间跑；
 * 解析放宽 → 用户以为自己设了 25:00。所以用固定时区 + 固定基准时刻钉住。
 */
class ScheduledTaskSchedulerTest {
  private val zone = ZoneId.of("Asia/Shanghai")

  private fun instant(text: String): Long = Instant.parse(text).toEpochMilli()

  private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toInstant().toString()

  @Test
  fun `before the time today schedules today`() {
    val now = instant("2026-10-19T00:30:00Z") // 08:30 本地
    assertEquals("2026-10-19T01:00:00Z", iso(ScheduledTaskScheduler.nextTrigger(9, 0, now, zone)))
  }

  @Test
  fun `after the time today schedules tomorrow`() {
    val now = instant("2026-10-19T02:30:00Z") // 10:30 本地
    assertEquals("2026-10-20T01:00:00Z", iso(ScheduledTaskScheduler.nextTrigger(9, 0, now, zone)))
  }

  @Test
  fun `exactly at the time schedules the next day`() {
    // 边界：等于当前时刻时不能排「现在」——那会在排期的瞬间立刻触发一次。
    val now = instant("2026-10-19T01:00:00Z") // 09:00:00 本地
    assertEquals("2026-10-20T01:00:00Z", iso(ScheduledTaskScheduler.nextTrigger(9, 0, now, zone)))
  }

  @Test
  fun `time parsing accepts hh mm and rejects nonsense`() {
    assertEquals(0 to 0, parseTime("00:00"))
    assertEquals(23 to 59, parseTime("23:59"))
    assertEquals(8 to 5, parseTime(" 8:05 "))
    assertNull(parseTime("24:00"))
    assertNull(parseTime("9"))
    assertNull(parseTime("9:60"))
    assertNull(parseTime("ab:cd"))
  }
}

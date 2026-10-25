package com.factotum.app.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间校验是模型输入的第一道闸：模型把「每天 25:00」这种解析错的时间直接交上来时，
 * 桥必须回可行动的失败码，而不是落一条永远不会响的任务。
 */
class ScheduledTaskStoreTest {
  @Test
  fun `accepts every legal clock time`() {
    assertTrue(validClock(0, 0))
    assertTrue(validClock(8, 30))
    assertTrue(validClock(23, 59))
  }

  @Test
  fun `rejects out-of-range hour or minute`() {
    assertFalse(validClock(-1, 0))
    assertFalse(validClock(24, 0))
    assertFalse(validClock(8, -1))
    assertFalse(validClock(8, 60))
  }

  @Test
  fun `notification is throttled to once per interval and stops when resolved`() {
    val day = 24 * 60 * 60 * 1000L
    assertTrue(shouldNotify(needed = true, now = 10 * day, lastNotifiedAt = 0L, intervalMs = day))
    assertFalse(shouldNotify(true, 10 * day, 10 * day - 1000L, day))
    assertTrue(shouldNotify(true, 10 * day, 9 * day, day))
    assertFalse(shouldNotify(false, 10 * day, 0L, day))
  }

  @Test
  fun `reminder only when tasks exist and the exact-alarm permission is missing`() {
    assertTrue(needsExactAlarmReminder(canScheduleExact = false, enabledTasks = 1))
    assertFalse(needsExactAlarmReminder(canScheduleExact = false, enabledTasks = 0))
    assertFalse(needsExactAlarmReminder(canScheduleExact = true, enabledTasks = 5))
  }
}

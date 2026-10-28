package com.zoneu.app.schedule

import com.zoneu.app.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleRuleTest {
  private val zone = ZoneId.of("Asia/Shanghai")

  private fun at(value: String) = Instant.parse(value).toEpochMilli()

  private fun next(rule: ScheduleRule, now: String, hour: Int = 9, minute: Int = 0): String? =
    rule.next(hour, minute, at(now), zone)?.let { Instant.ofEpochMilli(it).toString() }

  @Test
  fun `weekly before equal and after weekday time`() {
    val monday = ScheduleRule("weekly", weekday = 1)
    assertEquals("2026-10-26T01:00:00Z", next(monday, "2026-10-26T00:59:00Z"))
    assertEquals("2026-11-02T01:00:00Z", next(monday, "2026-10-26T01:00:00Z"))
    assertEquals("2026-11-02T01:00:00Z", next(monday, "2026-10-26T02:00:00Z"))
    assertEquals("2026-11-01T01:00:00Z", next(ScheduleRule("weekly", weekday = 7), "2026-10-31T12:00:00Z"))
  }

  @Test
  fun `monthly skips missing dates across short months and years`() {
    val end = ScheduleRule("monthly", dayOfMonth = 31)
    assertEquals("2026-03-31T01:00:00Z", next(end, "2026-02-01T00:00:00Z"))
    assertEquals("2026-05-31T01:00:00Z", next(end, "2026-04-01T00:00:00Z"))
    assertEquals("2027-01-31T01:00:00Z", next(end, "2026-12-31T01:00:00Z"))
    assertEquals("2028-02-29T01:00:00Z", next(ScheduleRule("monthly", dayOfMonth = 29), "2028-02-01T00:00:00Z"))
    assertEquals("2027-03-29T01:00:00Z", next(ScheduleRule("monthly", dayOfMonth = 29), "2027-02-01T00:00:00Z"))
    assertEquals("2026-03-30T01:00:00Z", next(ScheduleRule("monthly", dayOfMonth = 30), "2026-02-01T00:00:00Z"))
    assertEquals("2026-02-28T01:00:00Z", next(ScheduleRule("monthly", dayOfMonth = 28), "2026-02-01T00:00:00Z"))
  }

  @Test
  fun `once never rolls to tomorrow or another year`() {
    val rule = ScheduleRule("once", date = "2027-01-05")
    assertEquals("2027-01-05T01:00:00Z", next(rule, "2027-01-04T00:00:00Z"))
    assertNull(next(rule, "2027-01-05T01:00:00Z"))
    assertNull(next(rule, "2027-02-01T00:00:00Z"))
  }

  @Test
  fun `yearly date and leap day skip nonexistent years`() {
    val summer = ScheduleRule("yearly", month = 6, dayOfMonth = 1)
    assertEquals("2027-06-01T01:00:00Z", next(summer, "2026-10-28T00:00:00Z"))
    assertEquals("2028-06-01T01:00:00Z", next(summer, "2027-06-01T01:00:00Z"))
    val leap = ScheduleRule("yearly", month = 2, dayOfMonth = 29)
    assertEquals("2028-02-29T01:00:00Z", next(leap, "2026-10-28T00:00:00Z"))
    assertEquals("2104-02-29T01:00:00Z", next(leap, "2096-02-29T01:00:00Z"))
    assertFalse(ScheduleRule("yearly", month = 4, dayOfMonth = 31).valid())
    assertFalse(ScheduleRule("yearly", month = 13, dayOfMonth = 1).valid())
  }

  @Test
  fun `dates are strict and only the selected rule fields are allowed`() {
    for (date in listOf("2027-02-29", "2026-04-31", "2026-1-5", "0000-01-01", "+2027-01-05", "bad")) {
      assertFalse(date, ScheduleRule("once", date = date).valid())
    }
    assertTrue(ScheduleRule("once", date = "2028-02-29").valid())
    for (rule in listOf(
      ScheduleRule("weekly"),
      ScheduleRule("weekly", weekday = 0),
      ScheduleRule("weekly", weekday = 8),
      ScheduleRule("monthly", dayOfMonth = 32),
      ScheduleRule("daily", date = "2027-01-05"),
      ScheduleRule("once", weekday = 1, date = "2027-01-05"),
      ScheduleRule("yearly"),
    )) {
      assertFalse(rule.toString(), rule.valid())
    }
  }

  @Test
  fun `dst gaps shift and overlaps run only at the earlier offset`() {
    val ny = ZoneId.of("America/New_York")
    val gap = ScheduleRule("once", date = "2027-03-14").next(2, 30, at("2027-03-14T00:00:00Z"), ny)
    assertEquals(at("2027-03-14T07:30:00Z"), gap)
    val overlap = ScheduleRule("once", date = "2027-11-07")
    assertEquals(at("2027-11-07T05:30:00Z"), overlap.next(1, 30, at("2027-11-07T00:00:00Z"), ny))
    assertNull(overlap.next(1, 30, at("2027-11-07T06:00:00Z"), ny))
  }

  @Test
  fun `device timezone changes preserve local date and clock semantics`() {
    val once = ScheduleRule("once", date = "2027-01-05")
    val now = at("2027-01-01T00:00:00Z")
    assertEquals(at("2027-01-05T01:00:00Z"), once.next(9, 0, now, zone))
    assertEquals(at("2027-01-05T09:00:00Z"), once.next(9, 0, now, ZoneId.of("UTC")))
  }

  @Test
  fun `rule changes drop old fields but require new rule parameters`() {
    val current = ScheduleRule("weekly", weekday = 3)
    assertEquals(current, mergeScheduleRule(current, ScheduleRulePatch()))
    assertEquals(ScheduleRule("weekly", weekday = 5), mergeScheduleRule(current, ScheduleRulePatch(weekday = 5)))
    assertEquals(ScheduleRule("monthly", dayOfMonth = 15), mergeScheduleRule(current, ScheduleRulePatch("monthly", dayOfMonth = 15)))
    assertFalse(mergeScheduleRule(current, ScheduleRulePatch("monthly")).valid())
    assertFalse(mergeScheduleRule(current, ScheduleRulePatch(date = "2027-01-05")).valid())
  }

  @Test
  fun `only the current enabled due occurrence may be claimed`() {
    val task = ScheduledTaskEntity("id", "title", "prompt", 9, 0, true, revision = "new", nextRunAt = 1000)
    assertTrue(canClaim(task, "new", 1000, 1001))
    assertTrue(canClaim(task, "new", 1000, 1000000))
    assertFalse(canClaim(task, "old", 1000, 1001))
    assertFalse(canClaim(task, "new", 999, 1001))
    assertFalse(canClaim(task, "new", 1000, 999))
    assertFalse(canClaim(task.copy(enabled = false), "new", 1000, 1001))
    assertFalse(canClaim(task.copy(nextRunAt = null), "new", 1000, 1001))
  }
}

package com.zoneu.app.schedule

import com.zoneu.app.data.db.ScheduledTaskEntity
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** 使用设备本地日历计算，不把每月 31 号悄悄夹到短月月末。 */
data class ScheduleRule(
  val type: String = "daily",
  val weekday: Int? = null,
  val dayOfMonth: Int? = null,
  val date: String? = null,
  val month: Int? = null,
) {
  fun valid(): Boolean {
    val supplied = listOfNotNull(
      weekday?.let { "weekday" },
      dayOfMonth?.let { "day" },
      date?.let { "date" },
      month?.let { "month" },
    ).toSet()
    val expected = RULE_FIELDS[type] ?: return false
    if (supplied != expected) return false
    return when (type) {
      "weekly" -> weekday in 1..7
      "monthly" -> dayOfMonth in 1..31
      "yearly" -> validYearDay()
      "once" -> parsedDate() != null
      else -> true
    }
  }

  fun next(hour: Int, minute: Int, now: Long, zone: ZoneId): Long? {
    if (!valid() || !validClock(hour, minute)) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = LocalTime.of(hour, minute)
    return when (type) {
      "once" -> occurrence(requireNotNull(parsedDate()), time, zone).takeIf { it > now }

      "monthly" -> nextMonth(today, time, now, zone)

      "yearly" -> nextYear(today, time, now, zone)

      else -> (0..7).asSequence().map { today.plusDays(it.toLong()) }
        .filter { type == "daily" || it.dayOfWeek.value == weekday }
        .map { occurrence(it, time, zone) }.firstOrNull { it > now }
    }
  }

  fun describe(hour: Int, minute: Int): String {
    val clock = "%02d:%02d".format(hour, minute)
    return when (type) {
      "daily" -> "每天 $clock"
      "weekly" -> "每周${listOf("一", "二", "三", "四", "五", "六", "日").getOrNull((weekday ?: 0) - 1).orEmpty()} $clock"
      "monthly" -> "每月 $dayOfMonth 号 $clock（无此日期的月份跳过）"
      "once" -> "$date $clock（仅一次）"
      "yearly" -> "每年 $month 月 $dayOfMonth 日 $clock（无此日期的年份跳过）"
      else -> "无效排期"
    }
  }

  private fun parsedDate(): LocalDate? = try {
    date?.takeIf { Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) }
      ?.let(LocalDate::parse)?.takeIf { it.year in 1..9999 }
  } catch (_: DateTimeException) {
    null
  }

  private fun nextMonth(today: LocalDate, time: LocalTime, now: Long, zone: ZoneId): Long? {
    val month = YearMonth.from(today)
    return (0..12).asSequence().map { month.plusMonths(it.toLong()) }
      .filter { requireNotNull(dayOfMonth) <= it.lengthOfMonth() }
      .map { occurrence(it.atDay(requireNotNull(dayOfMonth)), time, zone) }.firstOrNull { it > now }
  }

  private fun validYearDay(): Boolean = try {
    java.time.MonthDay.of(month ?: 0, dayOfMonth ?: 0)
    true
  } catch (_: DateTimeException) {
    false
  }

  private fun nextYear(today: LocalDate, time: LocalTime, now: Long, zone: ZoneId): Long? {
    val day = java.time.MonthDay.of(requireNotNull(month), requireNotNull(dayOfMonth))
    return (0..8).asSequence().map { today.year + it }.filter { it <= 9999 && day.isValidYear(it) }
      .map { occurrence(day.atYear(it), time, zone) }.firstOrNull { it > now }
  }

  /** atZone 在 DST gap 顺延，overlap 选择较早偏移；同一天不会安排第二个偏移。 */
  private fun occurrence(day: LocalDate, time: LocalTime, zone: ZoneId): Long =
    day.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
}

internal fun ScheduledTaskEntity.rule(): ScheduleRule = ScheduleRule(scheduleType, weekday, dayOfMonth, date, month)

/** update 未提供的字段保留；换规则只带入新规则的参数，显式矛盾组合仍拒绝。 */
internal fun mergeScheduleRule(current: ScheduleRule, patch: ScheduleRulePatch): ScheduleRule {
  val type = patch.type ?: current.type
  val base = if (type == current.type) current else ScheduleRule(type)
  return ScheduleRule(
    type,
    patch.weekday ?: base.weekday,
    patch.dayOfMonth ?: base.dayOfMonth,
    patch.date ?: base.date,
    patch.month ?: base.month,
  )
}

data class ScheduleRulePatch(
  val type: String? = null,
  val weekday: Int? = null,
  val dayOfMonth: Int? = null,
  val date: String? = null,
  val month: Int? = null,
)

private val RULE_FIELDS = mapOf(
  "daily" to emptySet(),
  "weekly" to setOf("weekday"),
  "monthly" to setOf("day"),
  "yearly" to setOf("day", "month"),
  "once" to setOf("date"),
)

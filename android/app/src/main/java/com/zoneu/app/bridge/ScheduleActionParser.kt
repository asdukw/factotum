package com.zoneu.app.bridge

import com.zoneu.app.device.DeviceAction
import com.zoneu.app.schedule.ScheduleRule
import com.zoneu.app.schedule.ScheduleRulePatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 规则字段显式为 null、错误类型或未知枚举时拒绝，不能静默变成每日任务。 */
internal fun parseScheduledTaskCreate(root: JsonObject): DeviceAction.CreateScheduledTask? {
  if (!validScheduleFields(root)) return null
  val title = root.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val prompt = root.string("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val hour = root.int("hour") ?: return null
  val minute = root.int("minute") ?: return null
  val rule = ScheduleRule(
    root.string("schedule_type") ?: "daily",
    root.int("weekday"),
    root.int("day_of_month"),
    root.string("date"),
    root.int("month"),
  )
  if (!rule.valid()) return null
  return DeviceAction.CreateScheduledTask(title, prompt, hour, minute, rule)
}

internal fun parseScheduledTaskUpdate(id: String, root: JsonObject): DeviceAction.UpdateScheduledTask? {
  if (!validScheduleFields(root)) return null
  return DeviceAction.UpdateScheduledTask(
    id = id,
    title = root.string("title"),
    prompt = root.string("prompt"),
    hour = root.int("hour"),
    minute = root.int("minute"),
    enabled = root.boolean("enabled"),
    rule = ScheduleRulePatch(
      root.string("schedule_type"),
      root.int("weekday"),
      root.int("day_of_month"),
      root.string("date"),
      root.int("month"),
    ),
  )
}

private fun validScheduleFields(root: JsonObject): Boolean {
  val strings = listOf("title", "prompt", "schedule_type", "date")
  val numbers = listOf("hour", "minute", "weekday", "day_of_month", "month")
  if (strings.any { it in root && (root[it] as? JsonPrimitive)?.isString != true }) return false
  if (numbers.any { it in root && ((root[it] as? JsonPrimitive)?.isString != false || root.int(it) == null) }) return false
  val enabled = root["enabled"] as? JsonPrimitive
  val validEnabled = "enabled" !in root || (enabled?.isString == false && enabled.booleanOrNull != null)
  val validType = root.string("schedule_type")?.let { it in setOf("daily", "weekly", "monthly", "yearly", "once") } ?: true
  return validEnabled && validType
}

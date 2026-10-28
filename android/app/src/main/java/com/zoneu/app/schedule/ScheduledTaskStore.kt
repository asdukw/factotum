package com.zoneu.app.schedule

import android.content.Context
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.device.DeviceAction
import com.zoneu.app.device.DeviceActionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 本进程内编辑、领取、重排串行，网络执行不持锁。 */
internal val scheduleGate = Mutex()

object ScheduledTaskStore {
  suspend fun create(
    context: Context,
    title: String,
    prompt: String,
    hour: Int,
    minute: Int,
    rule: ScheduleRule = ScheduleRule(),
  ): DeviceActionResult = scheduleGate.withLock {
    val task = ScheduledTaskEntity(
      id = UUID.randomUUID().toString(),
      title = title.trim(),
      prompt = prompt.trim(),
      hour = hour,
      minute = minute,
      enabled = true,
      scheduleType = rule.type,
      weekday = rule.weekday,
      dayOfMonth = rule.dayOfMonth,
      date = rule.date,
      month = rule.month,
      revision = UUID.randomUUID().toString(),
    )
    validationError(task)?.let { return@withLock it }
    dao(context).upsert(task)
    ScheduledTaskScheduler.schedule(context, task)
    receipt(context, "已创建", requireNotNull(dao(context).byId(task.id)))
  }

  suspend fun list(context: Context, includeDisabled: Boolean): DeviceActionResult {
    val tasks = dao(context).all().filter { includeDisabled || it.enabled }
    return DeviceActionResult(
      ok = true,
      summary = "共 ${tasks.size} 条定时任务",
      payload = buildJsonArray { tasks.forEach { add(taskObject(context, it)) } }.toString(),
    )
  }

  suspend fun update(context: Context, patch: DeviceAction.UpdateScheduledTask): DeviceActionResult = scheduleGate.withLock {
    val id = patch.id
    val current = dao(context).byId(id) ?: return@withLock notFound()
    val next = mergeScheduleRule(current.rule(), patch.rule)
    val updated = current.copy(
      title = patch.title?.trim() ?: current.title,
      prompt = patch.prompt?.trim() ?: current.prompt,
      hour = patch.hour ?: current.hour,
      minute = patch.minute ?: current.minute,
      enabled = patch.enabled ?: current.enabled,
      scheduleType = next.type,
      weekday = next.weekday,
      dayOfMonth = next.dayOfMonth,
      date = next.date,
      month = next.month,
      revision = UUID.randomUUID().toString(),
      nextRunAt = null,
    )
    validationError(updated)?.let { return@withLock it }
    dao(context).upsert(updated)
    ScheduledTaskScheduler.schedule(context, updated)
    receipt(context, "已更新", requireNotNull(dao(context).byId(id)))
  }

  suspend fun delete(context: Context, id: String): DeviceActionResult = scheduleGate.withLock {
    if (dao(context).byId(id) == null) return@withLock notFound()
    dao(context).delete(id)
    ScheduledTaskScheduler.cancel(context, id)
    DeviceActionResult(ok = true, summary = "已删除 1 条定时任务", payload = null)
  }

  private fun validationError(task: ScheduledTaskEntity): DeviceActionResult? {
    val code = when {
      !validClock(task.hour, task.minute) -> "SCHEDULE_INVALID_TIME"

      !task.rule().valid() -> "SCHEDULE_INVALID_RULE"

      task.title.isBlank() || task.prompt.isBlank() -> "SCHEDULE_EMPTY_CONTENT"

      task.enabled && task.scheduleType == "once" &&
        task.rule().next(task.hour, task.minute, System.currentTimeMillis(), ZoneId.systemDefault()) == null -> "SCHEDULE_PAST_DATE"

      else -> return null
    }
    val summary = when (code) {
      "SCHEDULE_INVALID_TIME" -> "时间不合法：hour 0-23、minute 0-59"

      "SCHEDULE_EMPTY_CONTENT" -> "标题和任务指令不能为空"

      "SCHEDULE_PAST_DATE" -> "指定日期时间已过，请提供未来的日期和时间；不会自动改成循环任务"

      else ->
        "规则不合法：daily 不带日期；weekly 需 weekday 1-7；monthly 需 day_of_month 1-31；" +
          "yearly 需 month 1-12 与有效 day_of_month；once 需有效 date YYYY-MM-DD"
    }
    return DeviceActionResult(ok = false, summary = summary, payload = null, code = code)
  }

  private fun receipt(context: Context, verb: String, task: ScheduledTaskEntity): DeviceActionResult = DeviceActionResult(
    ok = true,
    summary = "$verb 定时任务：${task.rule().describe(task.hour, task.minute)}" +
      (if (task.enabled) exactNote(context) else "（已停用）"),
    payload = taskObject(context, task).toString(),
  )

  private fun dao(context: Context) = (context.applicationContext as ZoneuApplication).database.scheduledTasks()

  private fun exactNote(context: Context): String =
    if (ScheduledTaskScheduler.canScheduleExact(context)) "" else "（无精确闹钟权限，可能延迟15分钟或更久）"

  private fun taskObject(context: Context, task: ScheduledTaskEntity): JsonObject = buildJsonObject {
    put("id", task.id)
    put("title", task.title)
    put("prompt", task.prompt)
    put("hour", task.hour)
    put("minute", task.minute)
    put("enabled", task.enabled)
    put("schedule_type", task.scheduleType)
    task.weekday?.let { put("weekday", it) }
    task.dayOfMonth?.let { put("day_of_month", it) }
    task.date?.let { put("date", it) }
    task.month?.let { put("month", it) }
    put("schedule", task.rule().describe(task.hour, task.minute))
    val zone = ZoneId.systemDefault()
    put("timezone", zone.id)
    val at = if (task.enabled) task.nextRunAt else null
    if (at == null) {
      put("next_trigger", JsonNull)
    } else {
      put("next_trigger", Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }
    task.lastRunAt?.let { put("last_run_at", it) }
    put("last_error", task.lastError ?: "")
    if (!task.enabled) {
      put("note", "已停用，不会再触发")
    } else if (!ScheduledTaskScheduler.canScheduleExact(context)) {
      put("note", "没有精确闹钟权限：可能延迟15分钟或更久")
    }
  }

  private fun notFound() = DeviceActionResult(
    ok = false,
    summary = "找不到这条定时任务。先 list_scheduled_tasks 再按 id 操作，不要按标题猜。",
    payload = null,
    code = "SCHEDULE_NOT_FOUND",
  )
}

internal fun validClock(hour: Int, minute: Int): Boolean = hour in 0..23 && minute in 0..59

internal fun parseTime(raw: String): Pair<Int, Int>? {
  val parts = raw.trim().split(':')
  if (parts.size != 2) return null
  val hour = parts[0].toIntOrNull() ?: return null
  val minute = parts[1].toIntOrNull() ?: return null
  return if (validClock(hour, minute)) hour to minute else null
}

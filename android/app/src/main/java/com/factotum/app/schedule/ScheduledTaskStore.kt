package com.factotum.app.schedule

import android.content.Context
import com.factotum.app.FactotumApplication
import com.factotum.app.data.db.ScheduledTaskEntity
import com.factotum.app.device.DeviceActionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 定时任务的模型侧入口：语义化创建的落点。
 *
 * 用户在聊天里说「每天早上八点提醒我看天气」，模型把它拆成 `title / prompt / hour / minute`
 * 交给这里 —— 落库、排闹钟、回一行结构化结果。设置页不再有定时任务入口：创建、查看、修改、
 * 删除都走对话（`docs/scheduled-tasks.md`）。
 *
 * **summary 里不放标题与指令**：桥会把 summary 打进日志（`FactotumBridge`），而标题与指令是
 * 用户内容。需要给模型看的字段全部放 payload。
 */
object ScheduledTaskStore {
  /** 任务的简短回执：只有时间与数量，不含用户内容。 */
  private val triggerFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  suspend fun create(
    context: Context,
    title: String,
    prompt: String,
    hour: Int,
    minute: Int,
  ): DeviceActionResult {
    if (!validClock(hour, minute)) return invalidTime()
    val task =
      ScheduledTaskEntity(
        id = UUID.randomUUID().toString(),
        title = title.trim(),
        prompt = prompt.trim(),
        hour = hour,
        minute = minute,
        enabled = true,
      )
    dao(context).upsert(task)
    ScheduledTaskScheduler.schedule(context, task)
    return DeviceActionResult(
      ok = true,
      summary = "已创建定时任务：每天 ${clock(task)}${exactNote(context)}",
      payload = taskObject(context, task).toString(),
    )
  }

  suspend fun list(context: Context, includeDisabled: Boolean): DeviceActionResult {
    val tasks = dao(context).all().filter { includeDisabled || it.enabled }
    val payload = buildJsonArray { tasks.forEach { add(taskObject(context, it)) } }
    return DeviceActionResult(
      ok = true,
      summary = "共 ${tasks.size} 条定时任务",
      payload = payload.toString(),
    )
  }

  suspend fun update(
    context: Context,
    id: String,
    title: String?,
    prompt: String?,
    hour: Int?,
    minute: Int?,
    enabled: Boolean?,
  ): DeviceActionResult {
    if (hour != null || minute != null) {
      val nextHour = hour ?: return invalidTime()
      val nextMinute = minute ?: return invalidTime()
      if (!validClock(nextHour, nextMinute)) return invalidTime()
    }
    val current = dao(context).byId(id) ?: return notFound()
    val updated =
      current.copy(
        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
        prompt = prompt?.trim()?.takeIf { it.isNotEmpty() } ?: current.prompt,
        hour = hour ?: current.hour,
        minute = minute ?: current.minute,
        enabled = enabled ?: current.enabled,
      )
    dao(context).upsert(updated)
    ScheduledTaskScheduler.schedule(context, updated)
    return DeviceActionResult(
      ok = true,
      summary =
        "已更新定时任务：每天 ${clock(updated)}" +
          (if (updated.enabled) "" else "（已停用）") +
          exactNote(context),
      payload = taskObject(context, updated).toString(),
    )
  }

  suspend fun delete(context: Context, id: String): DeviceActionResult {
    val current = dao(context).byId(id) ?: return notFound()
    ScheduledTaskScheduler.cancel(context, id)
    dao(context).delete(id)
    return DeviceActionResult(
      ok = true,
      summary = "已删除定时任务：每天 ${clock(current)}",
      payload = taskObject(context, current).toString(),
    )
  }

  private fun dao(context: Context) =
    (context.applicationContext as FactotumApplication).database.scheduledTasks()

  private fun clock(task: ScheduledTaskEntity): String = "%02d:%02d".format(task.hour, task.minute)

  /** 没有精确闹钟权限时会降级成 15 分钟窗口 —— 在回执里说清楚，别让用户以为准点。 */
  private fun exactNote(context: Context): String =
    if (ScheduledTaskScheduler.canScheduleExact(context)) "" else "（无精确闹钟权限，可能晚 15 分钟内）"

  private fun taskObject(context: Context, task: ScheduledTaskEntity): JsonObject =
    buildJsonObject {
      put("id", task.id)
      put("title", task.title)
      put("prompt", task.prompt)
      put("hour", task.hour)
      put("minute", task.minute)
      put("enabled", task.enabled)
      put("next_trigger", nextTriggerText(task))
      task.lastRunAt?.let { put("last_run_at", it) }
      put("last_error", task.lastError ?: "")
      if (!task.enabled) put("note", "已停用，不会再触发")
      // 只有停用 / 降级这类「需要用户处理」的信息才进 payload 的 note。
      if (task.enabled && !ScheduledTaskScheduler.canScheduleExact(context)) {
        put("note", "没有精确闹钟权限：实际触发可能晚 15 分钟内")
      }
    }

  private fun nextTriggerText(task: ScheduledTaskEntity): String {
    val at = ScheduledTaskScheduler.nextTrigger(task.hour, task.minute, System.currentTimeMillis())
    return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(triggerFormat)
  }

  private fun invalidTime() =
    DeviceActionResult(
      ok = false,
      summary = "时间不合法：hour 0-23、minute 0-59",
      payload = null,
      code = "SCHEDULE_INVALID_TIME",
    )

  private fun notFound() =
    DeviceActionResult(
      ok = false,
      summary = "找不到这条定时任务（可能已被删除）。先 list_scheduled_tasks 再按 id 操作，不要按标题猜。",
      payload = null,
      code = "SCHEDULE_NOT_FOUND",
    )
}

/** 时间是否落在合法钟点上。纯函数，单测钉住。 */
internal fun validClock(hour: Int, minute: Int): Boolean = hour in 0..23 && minute in 0..59

/** 解析「HH:mm」；越界与乱写一律 null。纯函数，单测钉住。 */
internal fun parseTime(raw: String): Pair<Int, Int>? {
  val parts = raw.trim().split(':')
  if (parts.size != 2) return null
  val hour = parts[0].toIntOrNull() ?: return null
  val minute = parts[1].toIntOrNull() ?: return null
  return if (validClock(hour, minute)) hour to minute else null
}

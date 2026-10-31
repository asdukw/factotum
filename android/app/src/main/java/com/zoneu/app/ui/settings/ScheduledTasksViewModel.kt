package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.schedule.ScheduledTaskRunner
import com.zoneu.app.schedule.rule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时任务列表的一行。
 *
 * 标题是用户内容：这一页与审批 UI、工具结果一样，是它允许出现的地方；日志里仍然只有 id 与结果码。
 */
internal data class ScheduledTaskRow(
  val id: String,
  val title: String,
  val schedule: String,
  /** 下次触发，或为什么不会再触发。 */
  val timing: String,
  /** 上次结果，只写结果码对应的短语，不写模型输出正文。 */
  val result: String,
  /** 上次执行失败或已错过：界面上用错误色。正在执行不算失败。 */
  val isError: Boolean,
  /** 本进程内真的在跑；用来区分进程被杀后残留的 RUNNING。 */
  val running: Boolean,
  val enabled: Boolean,
)

/**
 * 设置 → 定时任务：只读列表。
 *
 * 任务仍然由对话创建和修改（`docs/scheduled-tasks.md`）；这一页只回答「我有哪些任务、
 * 什么时候响、上次成没成」。数据库是唯一事实来源，所以直接观察 Room，不做本地缓存。
 */
internal class ScheduledTasksViewModel(appContext: Context) : ViewModel() {
  private val dao = (appContext as ZoneuApplication).database.scheduledTasks()

  /** null = 还没读到库。首帧不能渲染空态，否则有任务时也会闪一下「还没有定时任务」。 */
  val rows: StateFlow<List<ScheduledTaskRow>?> =
    dao.observeAll()
      .map { tasks ->
        scheduledTaskRows(tasks, System.currentTimeMillis(), ZoneId.systemDefault(), ScheduledTaskRunner::isRunning)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

internal fun scheduledTaskRows(
  tasks: List<ScheduledTaskEntity>,
  now: Long,
  zone: ZoneId,
  isRunning: (String) -> Boolean = { false },
): List<ScheduledTaskRow> = tasks.map { task ->
  val rule = task.rule()
  val running = isRunning(task.id)
  // 只信库里已排好的时刻：next_run_at 为 null 时不要按规则编一个「下次」出来 ——
  // 闹钟没排上时那个时间不会响，列表不能替运行层作承诺。
  val nextAt = task.nextRunAt
  ScheduledTaskRow(
    id = task.id,
    title = task.title,
    schedule = rule.describe(task.hour, task.minute),
    timing =
      when {
        running -> "正在执行"

        !task.enabled && task.scheduleType == "once" && task.lastError == "RUNNING" ->
          "单次任务未完成，不会再触发"

        !task.enabled && task.scheduleType == "once" && task.lastRunAt != null -> "单次任务已执行"

        !task.enabled -> "已停用，不会再触发"

        nextAt != null && nextAt <= now -> "已到点，尚未触发（可能延迟）"

        nextAt != null -> "下次 ${formatTime(nextAt, zone)}"

        else -> "未排期，暂不会触发"
      },
    result = resultLabel(task, zone, running),
    isError = task.lastError != null && !running,
    running = running,
    enabled = task.enabled,
  )
}

private fun resultLabel(task: ScheduledTaskEntity, zone: ZoneId, running: Boolean): String {
  val at = task.lastRunAt
  val error = task.lastError
  return when {
    running -> "正在执行"

    error == "RUNNING" -> "上次运行未完成（进程可能被中断）"

    // expire() 只写结果码、不动 last_run_at：不能把「已错过」挂在上一次成功的时刻后面。
    error == "SCHEDULE_MISSED" || error == "SCHEDULE_INVALID_RULE" -> failureLabel(error)

    at == null -> error?.let(::failureLabel) ?: "还没运行过"

    error == null -> "上次 ${formatTime(at, zone)} · 成功"

    else -> "上次 ${formatTime(at, zone)} · ${failureLabel(error)}"
  }
}

/** 结果码翻译成给用户看的一句短语；未知码原样显示，避免把新状态悄悄藏掉。 */
private fun failureLabel(code: String): String = when (code) {
  "TURN_FAILED" -> "失败：模型调用失败"
  "TASK_FAILED" -> "失败：执行失败"
  "TASK_CANCELLED" -> "失败：已取消"
  "FOREGROUND_SERVICE_FAILED" -> "失败：后台服务启动失败"
  "SCHEDULE_MISSED" -> "已错过（未补跑）"
  "SCHEDULE_INVALID_RULE" -> "排期无效，未执行"
  else -> "失败：$code"
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(at: Long, zone: ZoneId): String =
  Instant.ofEpochMilli(at).atZone(zone).format(TIME_FORMAT)

package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.ZoneuApplication
import com.zoneu.app.data.db.SCHEDULE_RUN_HISTORY_LIMIT
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.data.db.ScheduledTaskRunEntity
import com.zoneu.app.schedule.ScheduledTaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一条执行记录：只含时间与结果码，不含任务内容。 */
internal data class ScheduledTaskRunRow(
  val id: String,
  val scheduled: String,
  val started: String,
  /** 已结束才有用时；中断或正在跑的记录没有。 */
  val duration: String?,
  val result: String,
  val isError: Boolean,
)

/**
 * 设置 → 定时任务 → 执行记录。
 *
 * 记录由 [ScheduledTaskRunner] 在领取时写入、结束时补完；这一页只读。
 * 进程在任务执行中被强杀时记录会停在未完成状态，用进程内运行集合区分「正在跑」与「上次没跑完」。
 */
internal class ScheduledTaskRunsViewModel(appContext: Context, taskId: String) : ViewModel() {
  private val db = (appContext as ZoneuApplication).database
  private val _task = MutableStateFlow<ScheduledTaskEntity?>(null)
  val task: StateFlow<ScheduledTaskEntity?> = _task.asStateFlow()

  /** null = 还没读到库，避免首帧闪一下空态。 */
  val rows: StateFlow<List<ScheduledTaskRunRow>?> =
    db.scheduledTaskRuns()
      .observeForTask(taskId, SCHEDULE_RUN_HISTORY_LIMIT)
      .map { runs -> scheduledTaskRunRows(runs, ZoneId.systemDefault(), ScheduledTaskRunner::isRunning) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  init {
    viewModelScope.launch { _task.value = db.scheduledTasks().byId(taskId) }
  }
}

internal fun scheduledTaskRunRows(
  runs: List<ScheduledTaskRunEntity>,
  zone: ZoneId,
  isRunning: (String) -> Boolean = { false },
): List<ScheduledTaskRunRow> = runs.map { run ->
  val running = run.finishedAt == null && isRunning(run.taskId)
  ScheduledTaskRunRow(
    id = run.id,
    scheduled = formatRunTime(run.scheduledAt, zone),
    started = formatRunTime(run.startedAt, zone),
    duration = run.finishedAt?.let { formatDuration(it - run.startedAt) },
    result =
      when {
        running -> "执行中"
        run.finishedAt == null -> "未完成（进程可能被中断）"
        run.result == null -> "成功"
        else -> scheduledFailureLabel(run.result)
      },
    isError = run.result != null || (run.finishedAt == null && !running),
  )
}

private val RUN_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatRunTime(at: Long, zone: ZoneId): String =
  Instant.ofEpochMilli(at).atZone(zone).format(RUN_TIME_FORMAT)

private fun formatDuration(ms: Long): String {
  val seconds = (ms / 1000).coerceAtLeast(0)
  return when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
  }
}

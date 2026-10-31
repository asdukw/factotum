package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.data.db.SCHEDULE_RUN_HISTORY_LIMIT
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.schedule.rule

/**
 * 一条任务的执行记录（设置 → 定时任务 → 点某条任务）。
 *
 * 记录只回答「什么时候该跑、什么时候开始、跑了多久、结果是什么」，不显示任务指令或模型输出 ——
 * 这一页会进截图（`docs/scheduled-tasks.md` §5）。
 */
@Composable
fun ScheduledTaskRunScreen(
  taskId: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: ScheduledTaskRunsViewModel = viewModel(key = taskId) {
    ScheduledTaskRunsViewModel(appContext, taskId)
  }
  val task by viewModel.task.collectAsStateWithLifecycle()
  val rows by viewModel.rows.collectAsStateWithLifecycle()

  ScheduledTaskRunScreen(task = task, rows = rows, onBack = onBack, modifier = modifier)
}

@Composable
internal fun ScheduledTaskRunScreen(
  task: ScheduledTaskEntity?,
  rows: List<ScheduledTaskRunRow>?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "执行记录", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (task != null) {
          BigTitle(title = task.title, subtitle = task.rule().describe(task.hour, task.minute))
        }
        when {
          rows == null -> Unit

          rows.isEmpty() -> NoRuns()

          else ->
            SettingsGroup {
              rows.forEachIndexed { index, run ->
                if (index > 0) SettingsDivider()
                RunItem(run)
              }
            }
        }
        Text(
          text =
            "记录只含计划时间、开始/结束时间与结果码；不含任务指令、模型输出或屏幕内容。" +
              "每条任务保留最近 $SCHEDULE_RUN_HISTORY_LIMIT 次。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
      }
    }
  }
}

@Composable
private fun NoRuns() {
  SettingsGroup {
    Text(
      text = "还没有执行记录。任务到点跑过之后，这里会留下开始时间、用时和结果。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    )
  }
}

@Composable
private fun RunItem(run: ScheduledTaskRunRow) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Glyph(
      kind = GlyphKind.Clock,
      modifier = Modifier.padding(top = 2.dp).size(24.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = "计划 ${run.scheduled}",
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = "开始 ${run.started}" + (run.duration?.let { " · 用时 $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = run.result,
        style = MaterialTheme.typography.labelSmall,
        color =
          if (run.isError) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}

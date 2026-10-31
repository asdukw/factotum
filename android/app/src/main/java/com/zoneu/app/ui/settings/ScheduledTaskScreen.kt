package com.zoneu.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.schedule.ScheduledTaskScheduler

/**
 * 设置 → 定时任务：只读列表。
 *
 * 入口放在设置首页，而不是执行层：执行层是「agent 怎么动手机」的开关，这里是一个任务清单。
 * 创建 / 修改 / 删除仍然只走对话（`docs/scheduled-tasks.md`），本页不提供表单 ——
 * 自然语言里的「每周三九点」很难用一个日期选择器表达。
 */
@Composable
fun ScheduledTaskScreen(
  onBack: () -> Unit,
  onOpenRuns: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  // Nav3 1.0.1 缺配套的 SavedState 装饰器（装了会启动即崩），所以用 Activity 作用域的 VM。
  val viewModel: ScheduledTasksViewModel = viewModel { ScheduledTasksViewModel(appContext) }
  val rows by viewModel.rows.collectAsStateWithLifecycle()

  ScheduledTaskScreen(rows = rows, onBack = onBack, onOpenRuns = onOpenRuns, modifier = modifier)
}

@Composable
internal fun ScheduledTaskScreen(
  rows: List<ScheduledTaskRow>?,
  onBack: () -> Unit,
  onOpenRuns: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // null = 还没读到库：首帧不渲染空态，否则有任务时也会闪一下「还没有定时任务」。
  val items = rows.orEmpty()
  val context = LocalContext.current
  var exactAlarm by remember { mutableStateOf(ScheduledTaskScheduler.canScheduleExact(context)) }

  // 用户从系统设置授予「闹钟和提醒」后回来，延迟提示要立刻消失。
  LifecycleResumeEffect(Unit) {
    exactAlarm = ScheduledTaskScheduler.canScheduleExact(context)
    onPauseOrDispose { }
  }

  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "定时任务", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        when {
          rows == null -> Unit

          items.isEmpty() -> EmptyTasks()

          else ->
            SettingsGroup {
              items.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider()
                ScheduledTaskItem(row, onClick = { onOpenRuns(row.id) })
              }
            }
        }
        if (!exactAlarm && items.any { it.enabled }) {
          Text(
            text = "「闹钟和提醒」权限未开启：任务可能延迟 15 分钟以上。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
          )
        }
        Text(
          text = "任务由对话创建和修改；这里只读，展示排期、下次触发时间和上次结果。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
      }
    }
  }
}

@Composable
private fun EmptyTasks() {
  SettingsGroup {
    Text(
      text = "还没有定时任务。\n在对话里说「每天早上八点提醒我看天气」即可创建。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    )
  }
}

@Composable
private fun ScheduledTaskItem(row: ScheduledTaskRow, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
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
        text = row.title,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = row.schedule,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = row.timing,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = row.result,
        style = MaterialTheme.typography.labelSmall,
        color =
          if (row.isError) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
    Spacer(Modifier.width(12.dp))
    val (badgeText, badgeTone) =
      when {
        row.running -> "执行中" to BadgeTone.Info
        row.enabled -> "已启用" to BadgeTone.Enabled
        else -> "已停用" to BadgeTone.Disabled
      }
    StatusBadge(text = badgeText, tone = badgeTone)
    Spacer(Modifier.width(4.dp))
    ChevronRight()
  }
}

package com.tappet.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tappet.app.TappetApplication
import com.tappet.app.data.db.ScheduledTaskEntity
import com.tappet.app.schedule.ScheduledTaskScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 定时任务：每天某个时刻让 agent 跑一句指令。
 *
 * 这一页刻意做得窄：**只有「标题 + 指令 + 每天几点」**，没有 cron 表达式。
 * 理由在 `docs/scheduled-tasks.md`：手机上靠得住的只有每天一次的 AlarmManager 唤醒，
 * 给出更细的配置等于给一个跑不住的承诺。
 *
 * 精确闹钟权限（Android 12+）**只在真的需要有精度时**才引导一次 —— 与无障碍同一套：
 * 不在启动时打扰，而且是**有降级**的（不精确的 setWindow 照样会跑）。
 */
@Composable
fun ScheduledTasksScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val dao = (context.applicationContext as TappetApplication).database.scheduledTasks()
  val tasks by remember { dao.observeAll() }.collectAsState(initial = emptyList())
  var editing by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
  var creating by remember { mutableStateOf(false) }
  var needsExact by remember { mutableStateOf(false) }

  fun persist(task: ScheduledTaskEntity) {
    scope.launch {
      withContext(Dispatchers.IO) {
        dao.upsert(task)
        ScheduledTaskScheduler.schedule(context, task)
      }
    }
  }

  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "定时任务", onBack = onBack)
      LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
          Text(
            "每天到点让 agent 跑一句指令。手机靠得住的是「每天一次」这种粒度；" +
              "开机后会自动重排，错过的任务不补跑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        items(tasks, key = { it.id }) { task ->
          TaskRow(
            task = task,
            onToggle = { enabled -> persist(task.copy(enabled = enabled)) },
            onEdit = { editing = task },
            onDelete = {
              scope.launch {
                withContext(Dispatchers.IO) {
                  ScheduledTaskScheduler.cancel(context, task.id)
                  dao.delete(task.id)
                }
              }
            },
          )
        }
        item {
          TextButton(onClick = { creating = true }) { Text("＋ 新建定时任务") }
        }
        item {
          if (!ScheduledTaskScheduler.canScheduleExact(context)) {
            TextButton(onClick = { needsExact = true }) {
              Text("要点准点触发？去开启「闹钟和提醒」权限")
            }
          }
        }
      }
    }
  }

  if (creating || editing != null) {
    TaskEditorDialog(
      initial = editing,
      onDismiss = {
        creating = false
        editing = null
      },
      onSave = { task ->
        persist(task)
        creating = false
        editing = null
        // 保存后再看一次权限：用户可能刚在系统里关掉它。
        needsExact = !ScheduledTaskScheduler.canScheduleExact(context)
      },
    )
  }

  if (needsExact) {
    ExactAlarmDialog(
      onDismiss = { needsExact = false },
      onOpenSettings = {
        needsExact = false
        runCatching { context.startActivity(ScheduledTaskScheduler.exactAlarmSettingsIntent(context)) }
      },
    )
  }
}

@Composable
private fun ExactAlarmDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("准点触发需要额外授权") },
    text = {
      Text(
        "「每天 8:00」这种准点任务需要系统的「闹钟和提醒」权限（Android 12 起是单独开关，" +
          "应用不能自己打开）。\n\n不开也能用：任务会在目标时间前后 15 分钟内触发，可能晚几分钟。",
      )
    },
    confirmButton = { TextButton(onClick = onOpenSettings) { Text("去开启") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("先用不精确时间") } },
  )
}

@Composable
private fun TaskRow(
  task: ScheduledTaskEntity,
  onToggle: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  SettingsGroup {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("%02d:%02d · %s".format(task.hour, task.minute, task.title), style = MaterialTheme.typography.titleSmall)
        Text(
          task.lastError?.let { "上次失败：$it" } ?: (task.lastRunAt?.let { "上次已运行" } ?: "还没运行过"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = onEdit) { Text("编辑") }
      TextButton(onClick = onDelete) { Text("删除") }
      Spacer(Modifier.width(4.dp))
      Switch(checked = task.enabled, onCheckedChange = onToggle)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorDialog(
  initial: ScheduledTaskEntity?,
  onDismiss: () -> Unit,
  onSave: (ScheduledTaskEntity) -> Unit,
) {
  var title by remember { mutableStateOf(initial?.title.orEmpty()) }
  var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
  var time by remember { mutableStateOf(initial?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "08:00") }
  val parsed = parseTime(time)
  val valid = title.isNotBlank() && prompt.isNotBlank() && parsed != null

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initial == null) "新建定时任务" else "编辑定时任务") },
    text = {
      Column {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("标题") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = { Text("要 agent 做什么") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = time,
          onValueChange = { time = it },
          label = { Text("每天几点（HH:mm）") },
          singleLine = true,
          isError = parsed == null,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = valid,
        onClick = {
          val hm = parsed ?: return@TextButton
          onSave(
            ScheduledTaskEntity(
              id = initial?.id ?: UUID.randomUUID().toString(),
              title = title.trim(),
              prompt = prompt.trim(),
              hour = hm.first,
              minute = hm.second,
              enabled = initial?.enabled ?: true,
              lastRunAt = initial?.lastRunAt,
              lastError = initial?.lastError,
            ),
          )
        },
      ) {
        Text("保存")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/** 解析 "HH:mm"。不合法回 null —— UI 用 `isError` 立刻告诉用户，不等到保存才说。 */
internal fun parseTime(raw: String): Pair<Int, Int>? {
  val parts = raw.trim().split(':')
  if (parts.size != 2) return null
  val hour = parts[0].toIntOrNull() ?: return null
  val minute = parts[1].toIntOrNull() ?: return null
  val inRange = hour in 0..23 && minute in 0..59
  return if (inRange) hour to minute else null
}

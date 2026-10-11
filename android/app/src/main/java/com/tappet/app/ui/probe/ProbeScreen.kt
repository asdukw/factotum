package com.tappet.app.ui.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.device.CapabilityResult
import com.tappet.app.device.CapabilityStatus
import com.tappet.app.device.DeviceCapability
import com.tappet.app.device.OemProfile
import com.tappet.app.device.OemProfiles
import com.tappet.app.node.NodeRuntime

@Composable
fun ProbeScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: ProbeViewModel = viewModel { ProbeViewModel() },
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ProbeScreen(
    state = state,
    onRun = viewModel::run,
    onCancel = viewModel::cancel,
    onBack = onBack,
    modifier = modifier,
  )
}

@Composable
internal fun ProbeScreen(
  state: ProbeViewModel.UiState,
  onRun: (DeviceCapability) -> Unit,
  onCancel: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var localText by remember { mutableStateOf("") }
  val profile = remember { OemProfiles.current() }

  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onBack) { Text("返回") }
      Text(text = "无障碍探针", style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider()

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item { ConnectionCard(connected = state.connected, profile = profile) }

      state.countdown?.let { countdown ->
        item { CountdownCard(countdown = countdown, onCancel = onCancel) }
      }

      item { LocalTargetCard(value = localText, onValueChange = { localText = it }) }

      item { NodeSelfTestCard() }

      items(DeviceCapability.entries) { capability ->
        ProbeCard(
          capability = capability,
          result = state.results[capability],
          enabled = state.connected && state.countdown == null,
          onRun = onRun,
        )
      }

      item { PrivacyNote() }
    }
  }
}

@Composable
private fun ConnectionCard(connected: Boolean, profile: OemProfile) {
  ProbeSurface {
    Text(
      text = if (connected) "服务已连接 · ${profile.displayName}" else "服务未连接 · ${profile.displayName}",
      style = MaterialTheme.typography.titleSmall,
      color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    if (!connected) {
      Text(
        text = "adb 启用：\n" + profile.adbEnableCommands().joinToString("\n"),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 6.dp),
      )
      Text(
        text = "或：${profile.manualEnablePath}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )
      profile.constraints.forEach { constraint ->
        Text(
          text = "• $constraint",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun CountdownCard(countdown: ProbeViewModel.Countdown, onCancel: () -> Unit) {
  ProbeSurface {
    Text(
      text = "${countdown.capability.title}：${countdown.secondsLeft} 秒后执行",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = "现在切到目标 App 并停稳；结果切回来就能看到（不采集页面正文）。",
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(top = 4.dp),
    )
    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) { Text("取消") }
  }
}

@Composable
private fun LocalTargetCard(value: String, onValueChange: (String) -> Unit) {
  ProbeSurface {
    Text(text = "本地控制组", style = MaterialTheme.typography.titleSmall)
    Text(
      text = "留在本页执行 Q3 / Q4 时的安全目标（写它 / 点它）；对第三方 App 的实测请切出去。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      placeholder = { Text("探针输入框") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
    )
  }
}

@Composable
private fun NodeSelfTestCard() {
  val context = LocalContext.current
  val status by NodeRuntime.status.collectAsStateWithLifecycle()
  val busy = status is NodeRuntime.Status.Preparing || status is NodeRuntime.Status.Running

  ProbeSurface {
    Text(text = "Node 运行时（常驻）", style = MaterialTheme.typography.titleSmall)
    Text(
      text = "启动内嵌 libnode 与 dsh SDK JSON-RPC 运行时；启动后任务通过 session/prompt 提交，" +
        "stderr 在 logcat 的 NODEJS-MOBILE 标签下。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp),
    )
    Button(
      onClick = { NodeRuntime.ensureStarted(context) },
      enabled = !busy && status is NodeRuntime.Status.Idle,
      modifier = Modifier.padding(top = 8.dp),
      shape = RoundedCornerShape(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Text(if (busy) "启动中…" else "启动 Node 运行时")
    }
    Text(
      text = nodeStatusText(status),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

private fun nodeStatusText(status: NodeRuntime.Status): String =
  when (status) {
    NodeRuntime.Status.Idle -> "未启动"
    is NodeRuntime.Status.Preparing -> "准备中：${status.detail}"
    NodeRuntime.Status.Running -> "启动中（node::Start 已进入）"
    NodeRuntime.Status.Ready -> "就绪（SDK JSON-RPC 已握手）"
    is NodeRuntime.Status.Exited -> "已退出 code=${status.code}"
    is NodeRuntime.Status.Failed -> "失败：${status.reason}"
  }

@Composable
private fun ProbeCard(
  capability: DeviceCapability,
  result: CapabilityResult?,
  enabled: Boolean,
  onRun: (DeviceCapability) -> Unit,
) {
  ProbeSurface {
    Text(text = capability.title, style = MaterialTheme.typography.titleSmall)
    Text(
      text = capability.goal,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp),
    )
    Button(
      onClick = { onRun(capability) },
      enabled = enabled,
      modifier = Modifier.padding(top = 8.dp),
      shape = RoundedCornerShape(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Text("执行（8 秒倒计时）")
    }

    result?.let { value ->
      Text(
        text = "${value.status} · ${value.summary}",
        style = MaterialTheme.typography.bodyMedium,
        color =
          when (value.status) {
            CapabilityStatus.OK -> MaterialTheme.colorScheme.primary
            CapabilityStatus.FAILED -> MaterialTheme.colorScheme.error
            CapabilityStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
          },
        modifier = Modifier.padding(top = 8.dp),
      )
      value.details.forEach { detail ->
        Text(
          text = detail,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun PrivacyNote() {
  Text(
    text = "隐私：探针只记录计数、类名、包名、坐标与错误码；不写页面正文，不落盘，不导出。",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ProbeSurface(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp), content = content)
  }
}

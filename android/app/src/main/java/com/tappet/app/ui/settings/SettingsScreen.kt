package com.tappet.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.LlmPreset
import com.tappet.app.device.AccessibilityStatus

/**
 * 设置页：选服务商、填端点与自己的 API key。
 *
 * 存在的理由不是「多个配置项」，而是 **发布物里不能有密钥**：密钥只有一个来源 —— 这一页，
 * 存进 Keystore 封装的 SecretVault。构建期注入那条路已经删掉，包里不可能有编译进去的密钥。
 *
 * 页面按「服务商 → 端点 → 密钥」分三段，每段一个 composable：detekt 的长方法阈值（80 行）
 * 在这里是有道理的 —— 一段一屏，改哪段找哪段；而且这段代码最容易出错的地方是
 * 「端点 / 调用风格 / 模型必须成套」，分开写才看得清它们是一组。
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // context 必须在外面取：viewModel {} 的工厂 lambda 不是 composable 作用域，
  // 在里面读 LocalContext.current 编译不过。
  val appContext = LocalContext.current.applicationContext
  val viewModel: SettingsViewModel = viewModel { SettingsViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  SettingsScreen(
    state = state,
    onPresetChange = viewModel::onPresetChange,
    onBaseUrlChange = viewModel::onBaseUrlChange,
    onModelChange = viewModel::onModelChange,
    onApiKeyChange = viewModel::onApiKeyChange,
    onSave = viewModel::save,
    onClearApiKey = viewModel::clearApiKey,
    onBack = onBack,
    modifier = modifier,
  )
}

@Composable
internal fun SettingsScreen(
  state: SettingsViewModel.UiState,
  onPresetChange: (LlmPreset) -> Unit,
  onBaseUrlChange: (String) -> Unit,
  onModelChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onSave: () -> Unit,
  onClearApiKey: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    ScreenHeader(onBack = onBack)
    HorizontalDivider()
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AccessibilitySection()
      ProviderSection(state = state, onPresetChange = onPresetChange)
      EndpointSection(
        state = state,
        onBaseUrlChange = onBaseUrlChange,
        onModelChange = onModelChange,
      )
      ApiKeySection(
        state = state,
        onApiKeyChange = onApiKeyChange,
        onSave = onSave,
        onClearApiKey = onClearApiKey,
      )
      NoteCard()
    }
  }
}

@Composable
private fun ScreenHeader(onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
  ) {
    TextButton(onClick = onBack) { Text("返回") }
    Text(
      text = "设置",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(start = 4.dp, top = 10.dp),
    )
  }
}

/**
 * 无障碍这一段：**这是使用者唯一能主动找到「怎么开」的地方**（聊天页的提示卡只在他真的撞上
 * 之后才出现）。所以这里写全三步，包括 ColorOS 那个「保持开启」的坑。
 *
 * 状态每次回到前台重查 —— 用户去系统设置里开完回来，这里要立刻变成「已开启」。
 */
@Composable
private fun AccessibilitySection() {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }
  LifecycleResumeEffect(Unit) {
    enabled = AccessibilityStatus.isEnabled(context)
    onPauseOrDispose { }
  }

  SectionLabel("无障碍（执行层）")
  Text(
    text = if (enabled) "已开启 —— 读屏 / 点击 / 截图可用" else "未开启 —— 任务只能聊天，动不了手机",
    style = MaterialTheme.typography.bodyMedium,
    color =
      if (enabled) {
        MaterialTheme.colorScheme.onSurface
      } else {
        MaterialTheme.colorScheme.error
      },
  )
  if (!enabled) {
    Text(
      text =
        "Android 不允许应用自行开启无障碍（否则任何 App 都能自己拿到读屏与模拟点击的权力）。" +
          "只能你来开一次：",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = AccessibilityStatus.GUIDE_STEPS,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
      onClick = { context.startActivity(AccessibilityStatus.settingsIntent()) },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("去开启")
    }
  }
}

@Composable
private fun ProviderSection(
  state: SettingsViewModel.UiState,
  onPresetChange: (LlmPreset) -> Unit,
) {
  SectionLabel("服务商")
  // 用 FlowRow 换行而不是横向滚动：真机上五个 chip 一屏放不下，滚动的话「DeepSeek / 自定义」
  // 就藏在右边看不见 —— 看起来像没有这两家，而不是像可以滑。
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    LlmPreset.entries.forEach { preset ->
      FilterChip(
        selected = state.preset == preset,
        onClick = { onPresetChange(preset) },
        label = { Text(preset.label) },
      )
    }
  }
  Text(
    text =
      buildString {
        append("调用风格：${state.api}")
        if (!state.preset.verified) append("　·　这条路径没在真机上跑通过")
      },
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
    text =
      "选中会把端点与模型填成这家自己的值（可再手改）。Anthropic 走的是原生 messages 协议，" +
        "不是 OpenAI 兼容层 —— 端点、调用风格、模型三者必须成套，混着填连不上。",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun EndpointSection(
  state: SettingsViewModel.UiState,
  onBaseUrlChange: (String) -> Unit,
  onModelChange: (String) -> Unit,
) {
  SectionLabel("LLM 端点")
  OutlinedTextField(
    value = state.baseUrl,
    onValueChange = onBaseUrlChange,
    label = { Text("Base URL") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  OutlinedTextField(
    value = state.model,
    onValueChange = onModelChange,
    label = { Text("模型") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  Text(
    text =
      if (state.modelHints.isEmpty()) {
        "留空则回落到内置默认值。"
      } else {
        "目录里有的模型：${state.modelHints.joinToString("、")}" +
          "（也可以手填别的 id，但 dsh 认不出的会被当成纯文本模型，读图会失败）"
      },
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ApiKeySection(
  state: SettingsViewModel.UiState,
  onApiKeyChange: (String) -> Unit,
  onSave: () -> Unit,
  onClearApiKey: () -> Unit,
) {
  // 默认遮起来：这页可能被截图或投屏，密钥不该顺带出去。要看就点「显示」。
  var revealKey by remember { mutableStateOf(false) }

  SectionLabel("API key")
  Text(
    text = "当前：${state.keySource}",
    style = MaterialTheme.typography.bodyMedium,
    color =
      if (state.hasStoredKey) {
        MaterialTheme.colorScheme.onSurface
      } else {
        MaterialTheme.colorScheme.error
      },
  )
  OutlinedTextField(
    value = state.apiKeyInput,
    onValueChange = onApiKeyChange,
    label = { Text("API key（留空 = 不修改）") },
    singleLine = true,
    visualTransformation =
      if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
      TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "隐藏" else "显示") }
    },
    modifier = Modifier.fillMaxWidth(),
  )
  Text(
    text = "已保存的密钥不会回填到这里，只显示「有没有」；要换就整条重填。",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("保存") }
  if (state.hasStoredKey) {
    TextButton(onClick = onClearApiKey) { Text("清除已保存的密钥") }
  }
  when {
    state.error != null ->
      Text(
        text = state.error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )

    state.justSaved ->
      Text(
        text = "已保存。下次启动 App 生效（见下）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
      )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 8.dp),
  )
}

@Composable
private fun NoteCard() {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("密钥怎么存", style = MaterialTheme.typography.titleSmall)
      Text(
        text =
          "用 Android Keystore 里的 AES 密钥封装后再落盘。那把密钥是硬件封装、不可导出的，" +
            "所以密文换台手机就解不开，卸载重装也会丢 —— 都需要重新填一次。" +
            "它不进云备份，也不参与设备间直传（应用关掉了这两条），不进日志，不回填到界面上。" +
            "构建期注入那条路已经去掉：包里没有、也不可能有编译进去的密钥。",
        style = MaterialTheme.typography.bodySmall,
      )
      Text("什么时候生效", style = MaterialTheme.typography.titleSmall)
      Text(
        text =
          "下次启动 App 才生效。内嵌 Node 的环境变量与 dsh profile 只在启动时读一次，" +
            "这里不做热重载 —— 重拉 Node 进程会把整个 App 带崩。",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

package com.tappet.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 设置页：填 LLM 端点与自己的 API key。
 *
 * 存在的理由不是「多个配置项」，而是 **发布物里不能有密钥**：BuildConfig 是编译期常量、
 * 会以明文进 dex，所以带密钥的包不能发出去。有了这一页，装包的人自己填 key 即可。
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
  onBaseUrlChange: (String) -> Unit,
  onModelChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onSave: () -> Unit,
  onClearApiKey: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealKey by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().safeDrawingPadding()) {
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
    HorizontalDivider()

    Column(
      modifier =
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
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
        text = "留空则回落到构建期默认值。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      SectionLabel("API key")

      Text(
        text = "当前：${state.keySource}",
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (state.hasAnyKey) {
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
        // 默认遮起来：这页可能被截图或投屏，密钥不该顺带出去。要看就点「显示」。
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

      NoteCard()
    }
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
            "它不进云备份（应用已关掉 allowBackup），不进日志，也不会回填到界面上。",
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

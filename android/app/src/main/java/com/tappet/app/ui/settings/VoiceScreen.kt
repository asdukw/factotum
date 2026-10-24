package com.tappet.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 语音设置页：Fish Audio 的 API key、自动朗读、语速、试听。
 *
 * 朗读全部走 Fish Audio 的云端免费模型（`s2.1-pro-free`）：本机不再合成任何语音。
 * 代价是**助手正文会离开设备**，所以这一页把这句话写在设置项旁边，而不是藏在文档里。
 * 语音输入不受影响 —— 识别仍然完全在本机（paraformer），这一页也就没有识别相关设置。
 */
@Composable
fun VoiceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: VoiceViewModel = viewModel { VoiceViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  VoiceScreen(
    state = state,
    onBack = onBack,
    onKeyInput = viewModel::onKeyInput,
    onToggleReveal = viewModel::toggleReveal,
    onSaveKey = viewModel::saveKey,
    onClearKey = viewModel::clearKey,
    onAutoReadChange = viewModel::setAutoRead,
    onRateChange = viewModel::setRate,
    onPreview = viewModel::preview,
    modifier = modifier,
  )
}

@Composable
internal fun VoiceScreen(
  state: VoiceViewModel.UiState,
  onBack: () -> Unit,
  onKeyInput: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSaveKey: () -> Unit,
  onClearKey: () -> Unit,
  onAutoReadChange: (Boolean) -> Unit,
  onRateChange: (Float) -> Unit,
  onPreview: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    SettingsHeader(title = "语音", onBack = onBack)
    Column(
      modifier =
        Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
    ) {
      SectionLabel("Fish Audio")
      SettingsGroup {
        SettingsRow(
          glyph = GlyphKind.Key,
          title = "API Key",
          subtitle = if (state.hasKey) "已配置（Keystore 封装）" else "未配置：朗读不可用",
        ) {
          if (state.hasKey) {
            TextButton(onClick = onClearKey) { Text("清除") }
          }
        }
        SettingsDivider()
        KeyField(
          state = state,
          onKeyInput = onKeyInput,
          onToggleReveal = onToggleReveal,
          onSaveKey = onSaveKey,
        )
      }

      SectionLabel("朗读")
      SettingsGroup {
        SettingsRow(
          glyph = GlyphKind.Speaker,
          title = "自动朗读回复",
          subtitle = "模型答完自动读出来；关掉时点回复下方的喇叭",
        ) {
          Switch(checked = state.autoRead, onCheckedChange = onAutoReadChange)
        }
        SettingsDivider()
        RateRow(rate = state.rate, label = state.rateLabel, onRateChange = onRateChange)
        SettingsDivider()
        SettingsRow(
          glyph = GlyphKind.Mic,
          title = "试听",
          subtitle = "读一句中文，听当前的语速与音色",
        ) {
          Button(onClick = onPreview, enabled = state.unavailableReason == null) { Text("播放") }
        }
      }

      state.saveError?.let { message ->
        Spacer(Modifier.height(12.dp))
        InfoBanner(message)
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun KeyField(
  state: VoiceViewModel.UiState,
  onKeyInput: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSaveKey: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = state.keyInput,
      onValueChange = onKeyInput,
      label = { Text(if (state.hasKey) "API Key（已保存，留空不修改）" else "API Key") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      // Password 类型让输入法不要学习 / 联想 / 首字母大写这一段；只遮住显示是不够的。
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onSaveKey() }),
      visualTransformation =
        if (state.revealKey) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        Box(
          modifier =
            Modifier
              .size(44.dp)
              .clip(CircleShape)
              .clickable(onClick = onToggleReveal)
              .semantics { contentDescription = if (state.revealKey) "隐藏 API Key" else "显示 API Key" },
          contentAlignment = Alignment.Center,
        ) {
          Glyph(
            kind = if (state.revealKey) GlyphKind.EyeOff else GlyphKind.Eye,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    if (state.keyInput.isNotBlank()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onSaveKey) { Text("保存密钥") }
      }
    }
  }
}

@Composable
private fun RateRow(rate: Float, label: String, onRateChange: (Float) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text = "语速", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = rate,
      onValueChange = onRateChange,
      // 0.1 一档：语速是听觉偏好，格子太大就只能「要么太慢要么太快」。
      valueRange = 0.5f..2.0f,
      steps = 14,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

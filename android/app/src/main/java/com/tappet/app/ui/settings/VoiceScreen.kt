package com.tappet.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.voice.TtsVoice
import com.tappet.app.voice.VoiceEngines

/**
 * 语音设置页：朗读用哪个引擎、要不要自动读、读多快、用哪个音色。
 *
 * 两个引擎是**两种取舍**，不是新旧关系：系统 TTS 装完就能用、离线、不要流量，但音色随 ROM；
 * 本地引擎（sherpa-onnx + 中文模型）音色好，代价是几十 MB 的模型要下。
 * 所以这一页不做「推荐哪个」，只把两者的状态与代价写清楚。
 */
@Composable
fun VoiceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: VoiceViewModel = viewModel { VoiceViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  VoiceScreen(
    state = state,
    onBack = onBack,
    onSelectEngine = viewModel::selectEngine,
    onAutoReadChange = viewModel::setAutoRead,
    onRateChange = viewModel::setRate,
    onSelectVoice = viewModel::selectVoice,
    onPreview = viewModel::preview,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceScreen(
  state: VoiceViewModel.UiState,
  onBack: () -> Unit,
  onSelectEngine: (String) -> Unit,
  onAutoReadChange: (Boolean) -> Unit,
  onRateChange: (Float) -> Unit,
  onSelectVoice: (String?) -> Unit,
  onPreview: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var voiceSheetOpen by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()

  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    SettingsHeader(title = "语音", onBack = onBack)
    Column(
      modifier =
        Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
    ) {
      EngineSection(state = state, onSelectEngine = onSelectEngine)

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
        VoiceRow(state = state, onOpenVoices = { voiceSheetOpen = true })
        SettingsDivider()
        SettingsRow(
          glyph = GlyphKind.Mic,
          title = "试听",
          subtitle = "读一句中文，听当前的引擎 / 语速 / 音色",
        ) {
          Button(onClick = onPreview, enabled = state.unavailableReason == null) { Text("播放") }
        }
      }

      state.unavailableReason?.let { reason ->
        Spacer(Modifier.height(12.dp))
        InfoBanner("当前引擎不可用：$reason")
      }
      Spacer(Modifier.height(24.dp))
    }
  }

  if (voiceSheetOpen) {
    ModalBottomSheet(onDismissRequest = { voiceSheetOpen = false }, sheetState = sheetState) {
      VoiceList(
        voices = state.voices,
        selected = state.selectedVoice,
        onSelect = {
          onSelectVoice(it)
          voiceSheetOpen = false
        },
      )
    }
  }
}

/** 引擎选项。两者是取舍关系，不标「推荐」；不可用的那个把原因写在副标题里。 */
@Composable
private fun EngineSection(state: VoiceViewModel.UiState, onSelectEngine: (String) -> Unit) {
  SectionLabel("朗读引擎")
  SettingsGroup {
    EngineRow(
      title = "系统 TTS",
      subtitle = "用手机自带的语音引擎：离线、不用下模型、音色随 ROM",
      selected = state.engineId == VoiceEngines.SYSTEM,
      unavailableReason = state.engineReasons[VoiceEngines.SYSTEM],
      onClick = { onSelectEngine(VoiceEngines.SYSTEM) },
    )
    SettingsDivider()
    EngineRow(
      title = "本地引擎",
      subtitle = "开源模型在手机上推理：音色更好，要先下一个几十 MB 的模型",
      selected = state.engineId == VoiceEngines.LOCAL,
      unavailableReason = state.engineReasons[VoiceEngines.LOCAL],
      onClick = { onSelectEngine(VoiceEngines.LOCAL) },
    )
  }
}

/** 音色行。本地引擎的音色由模型决定，所以那一档不给点。 */
@Composable
private fun VoiceRow(state: VoiceViewModel.UiState, onOpenVoices: () -> Unit) {
  SettingsRow(
    glyph = GlyphKind.Sliders,
    title = "音色",
    subtitle = voiceSubtitle(state),
    enabled = state.engineId == VoiceEngines.SYSTEM && state.voices.isNotEmpty(),
    onClick = onOpenVoices,
  ) {
    ChevronRight()
  }
}

private fun voiceSubtitle(state: VoiceViewModel.UiState): String =
  when {
    state.engineId == VoiceEngines.LOCAL -> "由模型决定，本地引擎没有额外音色可选"
    state.loadingVoices -> "正在读取系统里的音色…"
    state.voices.isEmpty() -> "系统里没有可选的音色"
    else -> state.voices.firstOrNull { it.id == state.selectedVoice }?.label ?: "跟随系统默认"
  }

/** 引擎选项：右边一个对勾，不可用时把原因写在副标题里（而不是禁用这一行 —— 用户得知道为什么）。 */
@Composable
private fun EngineRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  unavailableReason: String?,
  onClick: () -> Unit,
) {
  SettingsRow(
    glyph = GlyphKind.Speaker,
    title = title,
    subtitle = unavailableReason?.let { "$subtitle\n不可用：$it" } ?: subtitle,
    onClick = onClick,
  ) {
    if (selected) {
      Glyph(GlyphKind.Check, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
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

@Composable
private fun VoiceList(
  voices: List<TtsVoice>,
  selected: String?,
  onSelect: (String?) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Text(
      text = "音色",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    VoiceListRow(label = "跟随系统默认", active = selected == null) { onSelect(null) }
    voices.forEach { voice ->
      VoiceListRow(label = voice.label, active = voice.id == selected) { onSelect(voice.id) }
    }
  }
}

@Composable
private fun VoiceListRow(label: String, active: Boolean, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(12.dp))
    if (active) Glyph(GlyphKind.Check, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
  }
}

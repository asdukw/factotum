package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.voice.TtsVoice
import com.tappet.app.voice.VoiceEngines
import com.tappet.app.voice.VoiceReader
import com.tappet.app.voice.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 语音设置页状态：引擎、自动朗读、语速、音色。
 *
 * 引擎有「可用 / 不可用」两态，不可用时把原因原样显示给用户（本地引擎没下模型就是最常见的那条）
 * —— 一个选了却没反应的选项比一个写着「还没下载模型」的选项糟糕得多。
 */
class VoiceViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val engineId: String = VoiceEngines.SYSTEM,
    val autoRead: Boolean = false,
    val rate: Float = VoiceSettings.DEFAULT_RATE,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoice: String? = null,
    /** 每个引擎不可用的原因（null = 可用）。两个都显示，用户不必先切过去才知道能不能用。 */
    val engineReasons: Map<String, String?> = emptyMap(),
    val loadingVoices: Boolean = false,
  ) {
    /** 界面上的语速文案。`1.0x` 读起来比 `100%` 更像语速。 */
    val rateLabel: String get() = "%.1fx".format(rate)

    val unavailableReason: String? get() = engineReasons[engineId]
  }

  private val _state =
    MutableStateFlow(
      UiState(
        engineId = VoiceSettings.engineId(appContext),
        autoRead = VoiceSettings.autoRead(appContext),
        rate = VoiceSettings.rate(appContext),
        selectedVoice = VoiceSettings.systemVoice(appContext),
      ),
    )
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refreshEngine()
  }

  fun selectEngine(engineId: String) {
    VoiceSettings.setEngineId(appContext, engineId)
    // 换引擎先把正在读的停掉：旧引擎的声音继续念、新设置已经生效，是最让人困惑的状态。
    VoiceReader.stop()
    _state.update { it.copy(engineId = engineId, voices = emptyList(), loadingVoices = true) }
    refreshEngine()
  }

  fun setAutoRead(enabled: Boolean) {
    VoiceSettings.setAutoRead(appContext, enabled)
    _state.update { it.copy(autoRead = enabled) }
  }

  /**
   * 语速。只写偏好，**不重建引擎** —— 引擎在下次朗读时按新值重建（[VoiceReader] 的
   * engineKey 含语速）。拖动滑杆时逐格重建 TTS 绑定会让设置页卡顿。
   */
  fun setRate(rate: Float) {
    VoiceSettings.setRate(appContext, rate)
    _state.update { it.copy(rate = rate) }
  }

  fun selectVoice(voiceId: String?) {
    VoiceSettings.setSystemVoice(appContext, voiceId)
    _state.update { it.copy(selectedVoice = voiceId) }
    VoiceReader.currentEngine(appContext).selectVoice(voiceId)
  }

  /** 试听：读一句固定的中文，让用户听到当前引擎 + 语速 + 音色的实际效果。 */
  fun preview() {
    VoiceReader.preview(appContext, PREVIEW_TEXT)
  }

  /** 读出各引擎的可用性与当前引擎的音色。进页面、换引擎后都调它。 */
  fun refreshEngine() {
    val reasons = VoiceEngines.ALL.associateWith { VoiceReader.reasonFor(appContext, it) }
    _state.update { it.copy(engineReasons = reasons, loadingVoices = true) }
    if (reasons[_state.value.engineId] != null) {
      _state.update { it.copy(voices = emptyList(), loadingVoices = false) }
      return
    }
    viewModelScope.launch {
      val voices = VoiceReader.availableVoices(appContext)
      _state.update { it.copy(voices = voices, loadingVoices = false) }
    }
  }

  private companion object {
    const val PREVIEW_TEXT = "你好，我是 Tappet。这是当前的朗读效果，语速和音色都可以在设置里调整。"
  }
}

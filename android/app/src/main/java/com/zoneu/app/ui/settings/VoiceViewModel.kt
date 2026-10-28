package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.voice.FishVoices
import com.zoneu.app.voice.VoiceReader
import com.zoneu.app.voice.VoiceSecretStore
import com.zoneu.app.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音设置页状态：Fish Audio 的 API key、自动朗读、语速、试听。
 *
 * 朗读只有 Fish Audio 一个引擎（免费模型 `s2.1-pro-free`），所以没有「选引擎」——
 * 这一页的设置项就是 key 本身。**语音输入不在这页**：识别模型只有 paraformer 一条，
 * 随安装自动下载，没有可选项。
 */
class VoiceViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val autoRead: Boolean = false,
    val rate: Float = VoiceSettings.DEFAULT_RATE,
    /** Fish 的参考音色 id（空 = 默认音色）；可选值见 [FishVoices]。 */
    val selectedVoice: String = FishVoices.DEFAULT_ID,
    /** key 是否已配置。**不含 key 本身** —— 这个对象会进界面、进截图。 */
    val hasKey: Boolean = false,
    /** 输入框里的草稿；保存成功即清空，不落盘。 */
    val keyInput: String = "",
    val revealKey: Boolean = false,
    /** 引擎不可用的原因（没配 key 时就是那句话）。 */
    val unavailableReason: String? = null,
    val saveError: String? = null,
  ) {
    /** 界面上的语速文案。`1.0x` 读起来比 `100%` 更像语速。 */
    val rateLabel: String get() = "%.1fx".format(rate)

    /** 当前音色名；认不出的 id（模型被删）显示默认音色。 */
    val voiceLabel: String get() = FishVoices.labelOf(selectedVoice)
  }

  private val _state =
    MutableStateFlow(
      UiState(
        autoRead = VoiceSettings.autoRead(appContext),
        rate = VoiceSettings.rate(appContext),
        hasKey = VoiceSecretStore.hasKey(appContext),
        selectedVoice = VoiceSettings.fishVoice(appContext),
      ),
    )
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refreshAvailability()
  }

  fun onKeyInput(text: String) {
    _state.update { it.copy(keyInput = text, saveError = null) }
  }

  fun toggleReveal() {
    _state.update { it.copy(revealKey = !it.revealKey) }
  }

  /**
   * 保存 key。空输入不做事 —— 「清空输入框再点保存」应当是空操作，而不是把 key 删掉；
   * 清除有单独的按钮。
   */
  fun saveKey() {
    val key = _state.value.keyInput.trim()
    if (key.isEmpty()) return
    viewModelScope.launch {
      val saved = withContext(Dispatchers.IO) { VoiceSecretStore.save(appContext, key) }
      if (!saved) {
        _state.update { it.copy(saveError = "密钥没能写入 Keystore，请再试一次") }
        return@launch
      }
      // 正在读的音频还是旧 key 合成的：停掉，下次朗读按新 key 重建引擎。
      VoiceReader.stop()
      _state.update { it.copy(hasKey = true, keyInput = "", revealKey = false, saveError = null) }
      refreshAvailability()
    }
  }

  fun clearKey() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { VoiceSecretStore.clear(appContext) }
      VoiceReader.stop()
      _state.update { it.copy(hasKey = false, keyInput = "", saveError = null) }
      refreshAvailability()
    }
  }

  fun setAutoRead(enabled: Boolean) {
    VoiceSettings.setAutoRead(appContext, enabled)
    _state.update { it.copy(autoRead = enabled) }
  }

  /**
   * 语速。只写偏好，**不重建引擎** —— 引擎在下次朗读时按新值重建（[VoiceReader] 的
   * engineKey 含语速）。拖动滑杆时逐格重建会让设置页卡顿。
   */
  fun setRate(rate: Float) {
    VoiceSettings.setRate(appContext, rate)
    _state.update { it.copy(rate = rate) }
  }

  /** 选一条参考音色。正在播的还是旧声音：停掉，下次朗读按新音色重建引擎。 */
  fun selectVoice(voiceId: String) {
    VoiceSettings.setFishVoice(appContext, voiceId)
    VoiceReader.stop()
    _state.update { it.copy(selectedVoice = voiceId) }
  }

  /** 试听：读一句固定的中文，让用户听到当前的语速与音色。 */
  fun preview() {
    VoiceReader.preview(appContext, PREVIEW_TEXT)
  }

  private fun refreshAvailability() {
    _state.update { it.copy(unavailableReason = VoiceReader.reasonFor(appContext)) }
  }

  private companion object {
    const val PREVIEW_TEXT = "你好，我是 Zoneu。这是当前的朗读效果，语速和音色都可以在设置里调整。"
  }
}

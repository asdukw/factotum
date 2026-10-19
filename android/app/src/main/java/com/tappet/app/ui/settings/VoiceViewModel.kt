package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.voice.TtsModel
import com.tappet.app.voice.TtsModelCatalog
import com.tappet.app.voice.TtsModelInstaller
import com.tappet.app.voice.TtsModelStore
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
 * 语音设置页状态：引擎、自动朗读、语速、音色，以及本地模型的下载与管理。
 *
 * 引擎有「可用 / 不可用」两态，不可用时把原因原样显示给用户（本地引擎没下模型就是最常见的
 * 那条）—— 一个选了却没反应的选项比一个写着「还没下载模型」的选项糟糕得多。
 */
class VoiceViewModel(private val appContext: Context) : ViewModel() {
  /** 目录里的一个模型在这台设备上的状态。 */
  data class ModelUi(
    val model: TtsModel,
    val installed: Boolean,
    val installedBytes: Long,
    /** 上次没下完的归档还剩多少（> 0 说明会续传）。 */
    val partialBytes: Long,
  )

  data class UiState(
    val engineId: String = VoiceEngines.SYSTEM,
    val autoRead: Boolean = false,
    val rate: Float = VoiceSettings.DEFAULT_RATE,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoice: String? = null,
    /** 每个引擎不可用的原因（null = 可用）。两个都显示，用户不必先切过去才知道能不能用。 */
    val engineReasons: Map<String, String?> = emptyMap(),
    val loadingVoices: Boolean = false,
    val models: List<ModelUi> = emptyList(),
    /** 选中的本地模型 id；null = 还没选。 */
    val selectedModelId: String? = null,
    /** 正在下载 / 解包的模型；null = 没有在装。 */
    val installing: TtsModelInstaller.State? = null,
  ) {
    /** 界面上的语速文案。`1.0x` 读起来比 `100%` 更像语速。 */
    val rateLabel: String get() = "%.1fx".format(rate)

    val unavailableReason: String? get() = engineReasons[engineId]

    /** 本地引擎是否已经可用（选了模型且装好了）。 */
    val localReady: Boolean
      get() = models.any { it.model.id == selectedModelId && it.installed }
  }

  private val _state =
    MutableStateFlow(
      UiState(
        engineId = VoiceSettings.engineId(appContext),
        autoRead = VoiceSettings.autoRead(appContext),
        rate = VoiceSettings.rate(appContext),
        selectedVoice = VoiceSettings.systemVoice(appContext),
        selectedModelId = VoiceSettings.localModel(appContext),
      ),
    )
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refreshEngine()
    refreshModels()
    // 下载结束后刷新一次：装好了模型，「本地引擎」那一行与可用性都要立刻变。
    viewModelScope.launch {
      TtsModelInstaller.state.collect { installing ->
        _state.update { it.copy(installing = installing) }
        if (installing == null) refreshModels()
      }
    }
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

  /** 选中一个本地模型。选完顺手切到本地引擎 —— 用户点了模型就是想说这个声音。 */
  fun selectModel(model: TtsModel) {
    VoiceSettings.setLocalModel(appContext, model.id)
    VoiceReader.stop()
    _state.update { it.copy(selectedModelId = model.id) }
    selectEngine(VoiceEngines.LOCAL)
  }

  fun installModel(model: TtsModel) {
    TtsModelInstaller.install(appContext, model)
  }

  fun cancelInstall() {
    TtsModelInstaller.cancel()
  }

  /** 删除一个已下载的模型。删的正是当前选中的那个时，引擎要重新判定可用性。 */
  fun deleteModel(model: TtsModel) {
    VoiceReader.stop()
    TtsModelStore.delete(appContext, model.id)
    refreshModels()
    refreshEngine()
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

  private fun refreshModels() {
    val models =
      TtsModelCatalog.ALL.map { model ->
        ModelUi(
          model = model,
          installed = TtsModelStore.isInstalled(appContext, model),
          installedBytes = TtsModelStore.installedBytes(appContext, model.id),
          partialBytes = TtsModelStore.archivePart(appContext, model.id).takeIf { it.isFile }?.length() ?: 0L,
        )
      }
    _state.update { it.copy(models = models) }
    // 装完 / 删掉之后，本地引擎的「可用性」跟着变。
    val localReason = VoiceReader.reasonFor(appContext, VoiceEngines.LOCAL)
    _state.update { current -> current.copy(engineReasons = current.engineReasons + (VoiceEngines.LOCAL to localReason)) }
  }

  private companion object {
    const val PREVIEW_TEXT = "你好，我是 Tappet。这是当前的朗读效果，语速和音色都可以在设置里调整。"
  }
}

package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.agent.LlmPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置页状态机。三件事：选服务商预选、把当前生效的配置填进输入框、把输入写回去。
 *
 * **密钥从不回填到界面上** —— 已存的 key 只以「有没有」的形式呈现（[UiState.hasStoredKey]），
 * 免得它出现在截图、录屏或无障碍读屏里。要换就整条重填。
 */
class SettingsViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    /** 当前选中的服务商预选。它决定调用风格（api）与推理格式，不只是个标签。 */
    val preset: LlmPreset = LlmPreset.default,
    /** 当前**生效**的 base URL（已保存值 > 预选默认 > 构建期默认），不是输入框的草稿。 */
    val baseUrl: String = "",
    val model: String = "",
    /** 用户这次输入的密钥；空串表示「不修改」。 */
    val apiKeyInput: String = "",
    /** 设置页里存着密钥（Keystore 封装）。 */
    val hasStoredKey: Boolean = false,
    /** 这次会话里保存成功过，用来给一次明确的反馈。 */
    val justSaved: Boolean = false,
    val error: String? = null,
  ) {
    /** pi-ai 的调用风格。摆在界面上，免得「填了 Anthropic 的端点却按 OpenAI 调」没人看出来。 */
    val api: String
      get() = preset.api

    /** 该预选下目录里真实存在的模型 id，给输入框做提示。 */
    val modelHints: List<String>
      get() = preset.modelHints

    /** 密钥来源的一句话说明，界面直接用。构建期那条路径已经去掉，所以只有两种状态。 */
    val keySource: String
      get() = if (hasStoredKey) "已保存到本机（Android Keystore 封装）" else "未配置"
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
  }

  /**
   * 选中一个服务商。非「自定义」的预选会**直接把端点与模型填成这家自己的值** ——
   * 预选的意义就是「一套能用的组合」，只换标签不换端点等于什么都没做。
   */
  fun onPresetChange(value: LlmPreset) {
    _state.update { current ->
      if (value.custom) {
        current.copy(preset = value, justSaved = false, error = null)
      } else {
        current.copy(
          preset = value,
          baseUrl = value.baseUrl,
          model = value.defaultModel,
          justSaved = false,
          error = null,
        )
      }
    }
  }

  fun onBaseUrlChange(value: String) {
    _state.update { it.copy(baseUrl = value, justSaved = false, error = null) }
  }

  fun onModelChange(value: String) {
    _state.update { it.copy(model = value, justSaved = false, error = null) }
  }

  fun onApiKeyChange(value: String) {
    _state.update { it.copy(apiKeyInput = value, justSaved = false, error = null) }
  }

  /**
   * 保存。密钥输入框留空表示**不改密钥**（只改了端点的场景），要删密钥请用 [clearApiKey]。
   */
  fun save() {
    val snapshot = _state.value
    // 非自定义的预选：端点/模型留空就用这家自己的默认值。否则会存成「Anthropic 的预选 +
    // 空端点」，而空端点会回落到内置默认（zen），等于拿 zen 的地址配 Anthropic 的模型 ——
    // 报出来的错还像是网络问题。
    val baseUrl = snapshot.baseUrl.ifBlank { snapshot.preset.baseUrl }
    val model = snapshot.model.ifBlank { snapshot.preset.defaultModel }
    val ok =
      AgentConfigStore.save(
        context = appContext,
        baseUrl = baseUrl,
        model = model,
        apiKey = snapshot.apiKeyInput.takeIf { it.isNotBlank() },
        preset = snapshot.preset,
      )
    if (!ok) {
      _state.update { it.copy(error = "写入失败：本地存储不可用") }
      return
    }
    reload(justSaved = true)
  }

  fun clearApiKey() {
    if (!AgentConfigStore.clearApiKey(appContext)) {
      _state.update { it.copy(error = "清除失败：本地存储不可用") }
      return
    }
    reload()
  }

  private fun reload(justSaved: Boolean = false) {
    val config = AgentConfigStore.current(appContext)
    _state.value =
      UiState(
        preset = config.preset,
        baseUrl = config.baseUrl,
        model = config.model,
        apiKeyInput = "",
        hasStoredKey = AgentConfigStore.hasStoredApiKey(appContext),
        justSaved = justSaved,
      )
  }
}

package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tappet.app.agent.AgentConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置页状态机。只做两件事：把当前生效的配置填进输入框、把输入写回去。
 *
 * **密钥从不回填到界面上** —— 已存的 key 只以「有没有」的形式呈现（[UiState.hasStoredKey]），
 * 免得它出现在截图、录屏或无障碍读屏里。要换就整条重填。
 */
class SettingsViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    /** 当前**生效**的 base URL（已保存值 > 构建期默认），不是输入框的草稿。 */
    val baseUrl: String = "",
    val model: String = "",
    /** 用户这次输入的密钥；空串表示「不修改」。 */
    val apiKeyInput: String = "",
    /** 设置页里存着密钥（Keystore 封装）。 */
    val hasStoredKey: Boolean = false,
    /** 构建期注入了密钥（只可能发生在开发机上的包）。 */
    val hasBuildTimeKey: Boolean = false,
    /** 这次会话里保存成功过，用来给一次明确的反馈。 */
    val justSaved: Boolean = false,
    val error: String? = null,
  ) {
    /** 当前是否有一把可用的密钥（来自设置页或构建期）。 */
    val hasAnyKey: Boolean
      get() = hasStoredKey || hasBuildTimeKey

    /** 密钥来源的一句话说明，界面直接用。 */
    val keySource: String
      get() =
        when {
          hasStoredKey -> "已保存到本机（Android Keystore 封装）"
          hasBuildTimeKey -> "来自构建期注入（开发机上的 local.properties）"
          else -> "未配置"
        }
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
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
    val ok =
      AgentConfigStore.save(
        context = appContext,
        baseUrl = snapshot.baseUrl,
        model = snapshot.model,
        apiKey = snapshot.apiKeyInput.takeIf { it.isNotBlank() },
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
        baseUrl = config.baseUrl,
        model = config.model,
        apiKeyInput = "",
        hasStoredKey = AgentConfigStore.hasStoredApiKey(appContext),
        hasBuildTimeKey = AgentConfigStore.hasBuildTimeApiKey(),
        justSaved = justSaved,
      )
  }
}

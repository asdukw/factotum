package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.agent.TermuxBridgeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置首页状态：当前启用的提供商 + 是否已能发请求。
 *
 * 只读「有没有 key」，不读密钥本体 —— 首页的摘要会进截图 / 读屏，密钥永远不该出现在这里。
 */
class SettingsViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    /** 启用中的提供商；null = 一个都没启用。 */
    val providerName: String? = null,
    val providerModel: String = "",
    /** 当前配置能不能真的发请求（端点 + 模型 + 密钥齐全）。 */
    val configured: Boolean = false,
    /** 手机上的 Linux 环境（Termux 桥）配好没有。 */
    val linuxConfigured: Boolean = false,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    val linux = TermuxBridgeStore.state(appContext).enabled
    val preset = AgentConfigStore.enabledPreset(appContext)
    if (preset == null) {
      _state.value = UiState(providerName = null, configured = false, linuxConfigured = linux)
      return
    }
    val settings = AgentConfigStore.provider(appContext, preset)
    _state.value =
      UiState(
        providerName = settings.name,
        providerModel = settings.model,
        configured = AgentConfigStore.current(appContext).isConfigured,
        linuxConfigured = linux,
      )
  }
}

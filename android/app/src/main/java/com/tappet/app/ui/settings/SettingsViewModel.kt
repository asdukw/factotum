package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.connector.ConnectorStore
import com.tappet.app.connector.GMAIL_CONNECTOR_ID
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
    /** 连接器是否已授权。只读「有没有凭据」，读不到凭据本体。 */
    val gmailConnected: Boolean = false,
    val gmailAccount: String? = null,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    // 连接器与模型提供商互不相干（一个是云端只读数据源，一个是 agent 的脑子），
    // 所以它在两条返回路径上都要写进去 —— 没有启用任何提供商时这一行同样要显示。
    val connector = ConnectorStore.status(appContext, GMAIL_CONNECTOR_ID)
    val preset = AgentConfigStore.enabledPreset(appContext)
    if (preset == null) {
      _state.value =
        UiState(
          providerName = null,
          configured = false,
          gmailConnected = connector.connected,
          gmailAccount = connector.account,
        )
      return
    }
    val settings = AgentConfigStore.provider(appContext, preset)
    _state.value =
      UiState(
        providerName = settings.name,
        providerModel = settings.model,
        configured = AgentConfigStore.current(appContext).isConfigured,
        gmailConnected = connector.connected,
        gmailAccount = connector.account,
      )
  }
}

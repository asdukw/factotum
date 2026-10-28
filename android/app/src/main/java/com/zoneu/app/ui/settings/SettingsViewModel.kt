package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.zoneu.app.agent.AgentConfigStore
import com.zoneu.app.connector.ConnectorStore
import com.zoneu.app.connector.GMAIL_CONNECTOR_ID
import com.zoneu.app.voice.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置首页状态：当前启用的提供商 + 是否已能发请求 + 执行层与连接器的开关状态。
 *
 * 只读「有没有凭据」，不读凭据本体 —— 首页的摘要会进截图 / 读屏，密钥永远不该出现在这里。
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
    /** 回复完自动朗读。读偏好，不读任何正文。 */
    val voiceAutoRead: Boolean = false,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    // 连接器与语音开关跟模型提供商互不相干，所以两条返回路径都要写进去 ——
    // 没有启用任何提供商时同样要显示。
    val connector = ConnectorStore.status(appContext, GMAIL_CONNECTOR_ID)
    val voiceAutoRead = VoiceSettings.autoRead(appContext)
    val preset = AgentConfigStore.enabledPreset(appContext)
    if (preset == null) {
      _state.value =
        UiState(
          providerName = null,
          configured = false,
          gmailConnected = connector.connected,
          gmailAccount = connector.account,
          voiceAutoRead = voiceAutoRead,
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
        voiceAutoRead = voiceAutoRead,
      )
  }
}

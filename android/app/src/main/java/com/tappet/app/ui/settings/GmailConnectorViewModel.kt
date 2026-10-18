package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.connector.ConnectEvent
import com.tappet.app.connector.ConnectorStore
import com.tappet.app.connector.GMAIL_CONNECTOR_ID
import com.tappet.app.connector.GmailAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gmail 连接器的设置页状态：客户端 id / 授权进度 / 已连接的账号。
 *
 * 客户端 id **改一次存一次**（与提供商详情页的非密钥字段同一取舍）：它会在用户去 Google
 * Cloud 复制粘贴时被反复改，攒着不存只会让人以为已经好了。凭据不在这里 —— 它只进
 * [com.tappet.app.data.crypto.SecretVault]，这一页读不到、也不该读到。
 */
internal class GmailConnectorViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val clientId: String = "",
    /** true = 这个 id 来自包里的预置默认值，用户什么都不用填就能连。界面据此不摆注册指引。 */
    val clientIdFromDefault: Boolean = false,
    /**
     * 生效的客户端密码（`GOCSPX-…`）。**它不是机密** —— 公共客户端的公开标识，
     * 预置的那份本来就在 APK 里；但 Google 的令牌端点要求必须发，所以界面要能改。
     */
    val clientSecret: String = "",
    val clientSecretFromDefault: Boolean = false,
    val connected: Boolean = false,
    val account: String? = null,
    /** true = 浏览器已经拉起，等用户在授权页点「同意」后跳回来。 */
    val awaitingConsent: Boolean = false,
    val connecting: Boolean = false,
    val error: String? = null,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var connectJob: Job? = null

  init {
    reload()
  }

  fun reload() {
    val status = GmailAuth.status(appContext)
    val storedSecret = ConnectorStore.clientSecret(appContext, GMAIL_CONNECTOR_ID)
    val secret = ConnectorStore.effectiveClientSecret(appContext, GMAIL_CONNECTOR_ID)
    _state.update {
      it.copy(
        clientId = status.clientId,
        clientIdFromDefault = status.clientIdFromDefault,
        clientSecret = secret,
        clientSecretFromDefault = storedSecret.isBlank() && secret.isNotBlank(),
        connected = status.connected,
        account = status.account,
      )
    }
  }

  fun onClientIdChange(value: String) {
    ConnectorStore.saveClientId(appContext, GMAIL_CONNECTOR_ID, value)
    _state.update { it.copy(clientId = value, error = null) }
  }

  fun onClientSecretChange(value: String) {
    ConnectorStore.saveClientSecret(appContext, GMAIL_CONNECTOR_ID, value)
    _state.update { it.copy(clientSecret = value, error = null) }
  }

  /**
   * 开始授权。**冷流每 collect 一次就是一次完整尝试**，所以这里先取消上一次 ——
   * 用户连点两次不该起两个回环监听器（第二个会占着另一个端口一直等到超时）。
   */
  fun connect() {
    if (_state.value.connecting) return
    connectJob?.cancel()
    _state.update { it.copy(connecting = true, awaitingConsent = false, error = null) }
    connectJob =
      viewModelScope.launch {
        GmailAuth.connect(appContext, _state.value.clientId).collect { event ->
          when (event) {
            ConnectEvent.AwaitingConsent -> _state.update { it.copy(awaitingConsent = true) }

            ConnectEvent.Connected -> {
              reload()
              _state.update { it.copy(connecting = false, awaitingConsent = false, error = null) }
            }

            is ConnectEvent.Failed ->
              _state.update { it.copy(connecting = false, awaitingConsent = false, error = event.message) }
          }
        }
      }
  }

  /** 用户点「取消」或离开页面：停掉流程，回环监听器在 `finally` 里被关掉。 */
  fun cancelConnect() {
    connectJob?.cancel()
    connectJob = null
    _state.update { it.copy(connecting = false, awaitingConsent = false) }
  }

  /**
   * 断开：清本机凭据。**同时清掉内存里的 access token**（[GmailAuth.disconnect] 负责），
   * 否则断开后这一轮进程里插件仍能拿到有效令牌，直到它自然过期 ——
   * 「断开」必须立刻生效，不能有这段窗口。
   */
  fun disconnect() {
    if (!GmailAuth.disconnect(appContext)) {
      _state.update { it.copy(error = "断开失败：本地存储不可用") }
      return
    }
    reload()
    _state.update { it.copy(error = null) }
  }

  override fun onCleared() {
    // 页面销毁时别留下一个还在等的协程（Activity 作用域的 VM 活得比页面久）。
    connectJob?.cancel()
    super.onCleared()
  }
}

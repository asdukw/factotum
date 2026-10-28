package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.connector.ConnectEvent
import com.zoneu.app.connector.ConnectorStore
import com.zoneu.app.connector.GMAIL_CONNECTOR_ID
import com.zoneu.app.connector.GmailAuth
import com.zoneu.app.connector.GmailClient
import com.zoneu.app.connector.tokenFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Gmail 连接器的设置页状态：客户端 id / 授权进度 / 已连接的账号。
 *
 * 输入作为草稿，连接时快照完整客户端配置；长期凭据不经 ViewModel 暴露。
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
  private var attempt = 0L

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
        error = status.problem?.takeUnless { problem -> problem == "NOT_CONNECTED" }?.let { problem -> tokenFailure(problem).summary },
      )
    }
  }

  fun onClientIdChange(value: String) {
    _state.update {
      it.copy(
        clientId = value,
        clientSecret = if (it.clientSecretFromDefault && value.trim() != it.clientId.trim()) "" else it.clientSecret,
        clientSecretFromDefault = false,
        error = null,
      )
    }
  }

  fun onClientSecretChange(value: String) {
    _state.update { it.copy(clientSecret = value, clientSecretFromDefault = false, error = null) }
  }

  /**
   * 开始授权。**冷流每 collect 一次就是一次完整尝试**，所以这里先取消上一次 ——
   * 用户连点两次不该起两个回环监听器（第二个会占着另一个端口一直等到超时）。
   */
  fun connect() {
    if (_state.value.connecting) return
    connectJob?.cancel()
    _state.update { it.copy(connecting = true, awaitingConsent = false, error = null) }
    val currentAttempt = ++attempt
    val config = GmailClient(_state.value.clientId.trim(), _state.value.clientSecret.trim())
    connectJob =
      viewModelScope.launch {
        try {
          GmailAuth.connect(appContext, config).collect { event ->
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
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (
          @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
          _state.update { it.copy(error = "Gmail 连接未完成 [CONNECT_FAILED]，请检查网络和客户端配置后重试") }
        } finally {
          if (attempt == currentAttempt) _state.update { it.copy(connecting = false, awaitingConsent = false) }
        }
      }
  }

  /** 用户点「取消」或离开页面：停掉流程，回环监听器在 `finally` 里被关掉。 */
  fun cancelConnect() {
    attempt++
    if (connectJob?.isActive == true) GmailAuth.cancelPending(appContext)
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
    cancelConnect()
    if (!GmailAuth.disconnect(appContext)) {
      _state.update { it.copy(error = "断开失败：本地存储不可用") }
      return
    }
    reload()
    _state.update { it.copy(error = null) }
  }

  override fun onCleared() {
    // 页面销毁时别留下一个还在等的协程（Activity 作用域的 VM 活得比页面久）。
    cancelConnect()
    super.onCleared()
  }
}

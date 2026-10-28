package com.zoneu.app.connector

/** 授权过程对界面暴露的事件。 */
internal sealed interface ConnectEvent {
  /**
   * 已经把浏览器拉起来了，等用户在授权页点「同意」后跳回来。
   * 界面据此显示「请在浏览器里完成授权」+ 取消按钮。
   */
  data object AwaitingConsent : ConnectEvent

  data object Connected : ConnectEvent

  data class Failed(val message: String) : ConnectEvent
}

/** 向插件交付令牌的结果。失败用**机器可读的 code**，工具侧据此给模型一句准话。 */
internal sealed interface AccessTokenResult {
  data class Ok(val accessToken: String, val expiresAtMillis: Long) : AccessTokenResult

  /** [code]：`NOT_CONFIGURED`（没填客户端 id）/ `NOT_CONNECTED`（没授权）/ `REFRESH_FAILED`。 */
  data class Unavailable(val code: String, val summary: String) : AccessTokenResult
}

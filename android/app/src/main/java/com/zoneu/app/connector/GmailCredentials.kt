package com.zoneu.app.connector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** OAuth 客户端按一组使用，不将自定义 ID 与预置 secret 拼接。 */
internal data class GmailClient(val id: String, val secret: String)

internal data class GmailCredential(val refreshToken: String, val client: GmailClient?, val account: String? = null)

internal sealed interface GmailCredentialRead {
  data object Missing : GmailCredentialRead

  data object Unreadable : GmailCredentialRead

  data class Present(val credential: GmailCredential) : GmailCredentialRead
}

internal interface GmailCredentialStore {
  fun read(): GmailCredentialRead

  fun save(credential: GmailCredential): Boolean

  fun clear(): Boolean
}

/** 单个 vault 值同时提交凭据、客户端绑定和账号，不依赖跨 prefs 事务。 */
internal fun encodeGmailCredential(value: GmailCredential): String =
  buildJsonObject {
    put("version", 1)
    put("refreshToken", value.refreshToken)
    value.client?.let {
      put("clientId", it.id)
      put("clientSecret", it.secret)
    }
    value.account?.let { put("account", it) }
  }.toString()

internal fun decodeGmailCredential(value: String, legacyAccount: String? = null): GmailCredentialRead =
  runCatching {
    if (!value.startsWith("{")) {
      if (value.isBlank() || value == "null") {
        GmailCredentialRead.Unreadable
      } else {
        GmailCredentialRead.Present(GmailCredential(value, null, legacyAccount))
      }
    } else {
      val root = Json.parseToJsonElement(value).jsonObject

      fun string(key: String): String? = root[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
      val token = string("refreshToken")?.takeIf { it.isNotBlank() }
      val id = string("clientId")?.takeIf { it.isNotBlank() }
      val secret = string("clientSecret")
      val validVersion = root["version"]?.jsonPrimitive?.intOrNull == 1
      val complete = listOf(token, id, secret).all { it != null }
      if (!validVersion || !complete) {
        GmailCredentialRead.Unreadable
      } else {
        val config = GmailClient(requireNotNull(id), requireNotNull(secret))
        GmailCredentialRead.Present(GmailCredential(requireNotNull(token), config, string("account")))
      }
    }
  }.getOrDefault(GmailCredentialRead.Unreadable)

internal fun tokenFailure(code: String): AccessTokenResult.Unavailable {
  val safeCode = code.takeIf { it in GMAIL_ERROR_CODES } ?: "OAUTH_FAILED"
  val summary = GMAIL_ERROR_MESSAGES[safeCode] ?: "Gmail 授权未完成，请检查网络与客户端配置后重试"
  return AccessTokenResult.Unavailable(safeCode, "$summary [$safeCode]")
}

/** 临时 HTTP 故障不解释成授权撤销。 */
internal fun parseTokenHttpResponse(status: Int, body: String): TokenOutcome {
  val parsed = parseTokenResponse(body)
  return when {
    status == 429 || status >= 500 -> TokenOutcome.Denied("TEMPORARY")
    status in 200..299 -> if (parsed is TokenOutcome.Granted) parsed else TokenOutcome.Denied("invalid_response")
    status == 400 && parsed is TokenOutcome.Denied -> parsed
    status == 401 -> TokenOutcome.Denied("invalid_client")
    else -> TokenOutcome.Denied("invalid_response")
  }
}

private val GMAIL_ERROR_MESSAGES = mapOf(
  "NOT_CONNECTED" to "尚未连接 Gmail，请在设置里完成授权",
  "CREDENTIAL_UNREADABLE" to "Gmail 凭据暂时无法解密；请解锁设备后重试，仍失败再重新连接（不会自动删除凭据）",
  "STORAGE_FAILED" to "Gmail 凭据保存失败，请检查本地存储后重新连接",
  "MISSING_REFRESH_TOKEN" to "Google 未返回长期授权，连接未完成；请重试授权并确认离线访问",
  "NOT_CONFIGURED" to "尚未配置 Gmail OAuth 桌面客户端 ID",
  "invalid_client" to "Gmail OAuth 客户端配置有误，请检查同一桌面客户端的 ID 与密码",
  "invalid_request" to "Google 拒绝了授权请求，请检查客户端 ID 与配套密码",
  "unauthorized_client" to "此 OAuth 客户端不允许当前授权流程，请检查桌面客户端配置",
  "CONNECTION_PENDING" to "正在重新连接 Gmail，请在授权完成后重试邮件工具",
  "CLIENT_CHANGED" to "Gmail 客户端配置已更换，请使用新配置重新连接",
  "invalid_grant" to "Google 已拒绝当前长期授权，请重新连接 Gmail（Testing 项目可能七天到期）",
  "NETWORK" to "连不上 Google 授权服务，请检查网络；现有凭据已保留",
  "TEMPORARY" to "Google 授权服务暂时不可用或限流，请稍后重试；现有凭据已保留",
  "STALE_OPERATION" to "Gmail 授权操作已取消或被新操作替代，请重试",
  "access_denied" to "Google 授权被取消或拒绝，请重新连接并确认所需权限",
  "STATE_MISMATCH" to "授权回调 state 不匹配，已中止；请重新发起连接",
  "AUTH_CANCELLED" to "Gmail 连接已取消，可重新发起授权",
  "AUTH_SERVICE_FAILED" to "无法启动授权前台服务，请返回 zoneu 后重新连接并检查系统后台限制",
  "AUTH_SERVICE_STOPPED" to "系统停止了授权服务，请回到 zoneu 重新连接",
  "TIMEOUT" to "Gmail 授权超时，请重新连接",
  "BROWSER_UNAVAILABLE" to "打不开浏览器，请检查设备默认浏览器",
  "invalid_response" to "Google 授权服务返回无效响应，请稍后重试；现有凭据已保留",
)
private val GMAIL_ERROR_CODES = GMAIL_ERROR_MESSAGES.keys

/** error 回调也先验证 state，避免把外部传入的错误当成本次授权结果。 */
internal fun redirectFailure(params: RedirectParams?, state: String): String? = when {
  params == null -> "TIMEOUT"
  params.state != state -> "STATE_MISMATCH"
  params.error != null -> params.error
  params.code == null -> "invalid_response"
  else -> null
}

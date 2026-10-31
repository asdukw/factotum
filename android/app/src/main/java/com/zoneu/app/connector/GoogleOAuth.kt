package com.zoneu.app.connector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google OAuth 2.0 授权码流程 —— **纯函数的那一半**（PKCE、授权 URL、令牌解析、
 * 回环重定向的请求行解析）。网络与界面那一半见 [GmailAuth]。
 *
 * ## 为什么不是设备码流程
 *
 * Google 的**设备授权**（limited input device）**只支持一小撮 scope**：
 * `email` / `openid` / `profile`、Drive 的 `drive.appdata` / `drive.file`、
 * YouTube 的两个 —— **Gmail 的 scope 一个都不在里面**。所以 Outlook 那套
 * 「显示码 + 轮询」在 Gmail 上直接不可用（见 `docs/email-connector.md` §5）。
 *
 * 替代方案是**回环重定向**：`http://127.0.0.1:<随机端口>`，Google 对**桌面应用**类型
 * 的客户端支持这条；桌面客户端是公共客户端，client secret 不能当机密使用。
 * 当前客户端的换码仍需配套的 client secret（与 PKCE 同时使用），不绑定 Android 包名。
 * Gmail scope 为 gmail.modify；读工具与审批门控写工具的边界见 docs/email-connector.md。

 */

internal const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"

internal const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"

/** Gmail API 的版本根。插件拼路径用。 */
internal const val GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1"

/**
 * **申请的是 `gmail.modify`，不是 `gmail.readonly`** —— 而工具面目前仍然只有只读三个。
 * 这个「授权比能力宽」是有意的，理由与代价都写在 `docs/email-connector.md` §1：
 *
 * - **为什么宽**：Google 对 installed apps **不支持增量授权**，扩权 = 已连接的用户要重走
 *   一遍同意页。而 `gmail.modify` 的描述本身就是「Read, compose, and **send** emails」——
 *   它一次就把读 / 写 / 发都覆盖了（**不含**永久删除，那是 `https://mail.google.com/`）。
 *   所以与其以后逼所有用户重新授权，不如一次要齐。
 * - **代价（必须记住）**：这条边界以前是 **Google 兜的** —— 就算插件被完全攻破，
 *   端点也会拒绝发送。现在它变成**我们自己的代码兜的**（插件只有一个 `gmailGet`，
 *   出网全是 GET，有单测钉住）。防线从「外部强制」降级成「自我约束」，
 *   这是这次改动真正的安全成本。
 *
 * 敏感度没变：`gmail.modify` 与 `gmail.readonly` 同属 **restricted**，验证要求一样重
 * （测试模式下只有白名单测试用户可用；要公开发布得过验证 + 年度安全评估）。
 *
 * `openid email` 只为在设置页显示「连的是哪个账号」——少了它只能显示「已连接（账号未知）」。
 */
internal const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.modify openid email"

/** PKCE verifier 的字节数：32 字节 → base64url 43 字符，落在 RFC 7636 要求的 43–128 内。 */
private const val VERIFIER_BYTES = 32

private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 3600L

private val ACCOUNT_CLAIMS = listOf("email", "preferred_username", "sub")

private const val HEX = "0123456789ABCDEF"

private val json = Json { ignoreUnknownKeys = true }

/** 回环重定向拿回来的参数。`error` 与 `code` 只会有一个非空。 */
internal data class RedirectParams(
  val code: String?,
  val state: String?,
  val error: String?,
  val errorDescription: String?,
)

/** 令牌端点的两种结局。Google 没有「还在等用户同意」这个中间态（不像设备码），所以只有两种。 */
internal sealed interface TokenOutcome {
  /**
   * [refreshToken] 为 null 表示这次响应没带（刷新时常见，RFC 6749 §6 允许）——
   * 调用方应**沿用旧的那一个**，不是把它清掉。
   */
  data class Granted(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val account: String?,
  ) : TokenOutcome

  /**
   * 拿不到了：用户拒绝 / 授权码失效 / refresh token 失效。
   *
   * [description] 是端点的 `error_description` —— 它讲的是**我们这次请求哪里不对**
   * （缺参数、redirect_uri 不匹配……），不含用户数据，所以可以进日志、也可以在界面上显示。
   * 没有它，`invalid_request` 这类码就是一句没有信息量的话（真机上踩过）。
   */
  data class Denied(val code: String, val description: String? = null) : TokenOutcome
}

// --- PKCE -------------------------------------------------------------------

/** 生成一个 code verifier：32 字节密码学随机数，base64url 无 padding。 */
internal fun newCodeVerifier(): String {
  val bytes = ByteArray(VERIFIER_BYTES)
  SecureRandom().nextBytes(bytes)
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * `code_challenge = BASE64URL(SHA256(ASCII(verifier)))`（RFC 7636 §4.2，S256）。
 * 这是 PKCE 的全部密码学 —— 它让「偷到授权码的人」换不到令牌。
 */
internal fun codeChallenge(verifier: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
  return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** 授权页地址。`access_type=offline` + `prompt=consent` 是**拿到 refresh token 的前提**。 */
internal fun authorizationUrl(
  clientId: String,
  redirectUri: String,
  challenge: String,
  state: String,
): String =
  buildString {
    append(GOOGLE_AUTH_URL)
    append('?')
    append(
      formOf(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to GMAIL_SCOPE,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "state" to state,
        // 不带 offline 就只在**第一次**授权时给 refresh token；带了才会每次都给。
        "access_type" to "offline",
        // 不带 consent 时，用户已经同意过的应用会**跳过**同意页，也就不会再发 refresh token。
        // 两个都要，才能保证「重新连接」真的能换到一份新的长期凭据。
        "prompt" to "consent",
      ),
    )
  }

// --- 回环重定向 ---------------------------------------------------------------

/**
 * 解析浏览器跳回来的那一行请求行：`GET /?code=...&state=... HTTP/1.1`。
 *
 * 纯函数，因为它是最容易出错又最难在设备上定位的一段（端口对了但路径不对、参数被
 * 百分号转义、Google 回的是 `error=access_denied`）。认不出来返回 null。
 */
internal fun parseRedirectRequest(requestLine: String): RedirectParams? {
  val parts = requestLine.split(' ')
  if (parts.size < 2 || parts[0] != "GET") return null
  val target = parts[1]
  val query = target.substringAfter('?', missingDelimiterValue = "")
  if (query.isEmpty()) return null
  val params = query.split('&').mapNotNull { pair ->
    val name = pair.substringBefore('=', missingDelimiterValue = "")
    if (name.isEmpty()) null else name to percentDecode(pair.substringAfter('=', missingDelimiterValue = ""))
  }.toMap()
  return RedirectParams(
    code = params["code"]?.takeIf { it.isNotEmpty() },
    state = params["state"]?.takeIf { it.isNotEmpty() },
    error = params["error"]?.takeIf { it.isNotEmpty() },
    errorDescription = params["error_description"]?.takeIf { it.isNotEmpty() },
  )
}

// --- 令牌 -------------------------------------------------------------------

/**
 * 解析令牌 JSON；HTTP 成功 / 临时故障由 parseTokenHttpResponse 单独分类。
 */
internal fun parseTokenResponse(body: String): TokenOutcome {
  val root = jsonObjectOrNull(body) ?: return TokenOutcome.Denied("invalid_response")
  val accessToken = root.stringOrNull("access_token")
  if (accessToken != null) {
    val ttl = if ("expires_in" in root) root.longOrNull("expires_in") else DEFAULT_ACCESS_TOKEN_TTL_SECONDS
    if (ttl == null || ttl <= 0 || ttl > Long.MAX_VALUE / 1000L) return TokenOutcome.Denied("invalid_response")
    return TokenOutcome.Granted(
      accessToken = accessToken,
      refreshToken = root.stringOrNull("refresh_token"),
      // 缺 expires_in 时按 1 小时：宁可早换一次，也不要拿着过期令牌发请求。
      expiresInSeconds = ttl,
      account = root.stringOrNull("id_token")?.let(::parseIdTokenAccount),
    )
  }
  return TokenOutcome.Denied(
    code = root.stringOrNull("error") ?: "invalid_response",
    description = root.stringOrNull("error_description"),
  )
}

/**
 * 从 `id_token`（JWT）的 payload 里取账号标识。
 *
 * 只解 payload、**不验签**：这个 token 是我们自己从 TLS 连着的令牌端点拿回来的，
 * 不是别人递给我们的 —— 验签解决的是「伪造者」，这里没有伪造者。它只用来在设置页显示
 * 「连的是谁」，不参与任何授权判断。解不出来返回 null（显示「账号未知」比显示一个
 * 猜错的地址好）。
 */
internal fun parseIdTokenAccount(idToken: String): String? {
  val payload = decodeJwtPayload(idToken) ?: return null
  return ACCOUNT_CLAIMS.firstNotNullOfOrNull { claim ->
    payload.stringOrNull(claim)?.trim()?.takeIf { it.isNotEmpty() }
  }
}

/**
 * 换码请求。[clientSecret] 为空时**不写这一项**（自定义客户端的用户可能没有），
 * 非空时按 Google 桌面应用的要求带上 —— 不带会被令牌端点拒成 `client_secret is missing`。
 */
internal fun codeExchangeForm(
  clientId: String,
  clientSecret: String,
  code: String,
  verifier: String,
  redirectUri: String,
): String =
  formOf(
    "grant_type" to "authorization_code",
    "client_id" to clientId,
    "client_secret" to clientSecret,
    "code" to code,
    "code_verifier" to verifier,
    "redirect_uri" to redirectUri,
  )

internal fun refreshTokenForm(clientId: String, clientSecret: String, refreshToken: String): String =
  formOf(
    "grant_type" to "refresh_token",
    "client_id" to clientId,
    "client_secret" to clientSecret,
    "refresh_token" to refreshToken,
  )

private fun formOf(vararg pairs: Pair<String, String>): String =
  // 空值整项略去：`client_secret=`（空串）与「没有这一项」在端点上不是一回事。
  pairs.filter { it.second.isNotEmpty() }
    .joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

/**
 * `application/x-www-form-urlencoded` 的转义（RFC 3986）。
 *
 * 不用 `java.net.URLEncoder`：Android 上只有 `(String, String)` 那个已废弃的重载
 * （要 Charset 的是 API 33 才有的），而这里要转义的东西（scope 里的 `/` 与空格、
 * PKCE 里的 `-` `_`）正是它历史上最容易出偏差的地方。自己写八行，顺便能单测。
 */
internal fun urlEncode(value: String): String {
  val out = StringBuilder()
  for (byte in value.toByteArray(Charsets.UTF_8)) {
    val code = byte.toInt() and 0xFF
    val char = code.toChar()
    when {
      char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> out.append(char)
      char == '-' || char == '.' || char == '_' || char == '~' -> out.append(char)
      char == ' ' -> out.append('+')
      else -> out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
    }
  }
  return out.toString()
}

/**
 * `application/x-www-form-urlencoded` 的反向操作。
 *
 * 逐**字节**累积再整体按 UTF-8 解码，而不是逐字符 `toChar()`：非 ASCII 的百分号转义
 * （`%E4%B8%AD`）逐字符转出来是乱码。重定向参数目前都是 ASCII，但这段代码不该只对
 * 今天的输入正确。
 */
private fun percentDecode(value: String): String {
  val bytes = ByteArrayOutputStream()
  var index = 0
  while (index < value.length) {
    val char = value[index]
    val hex =
      if (char == '%' && index + 2 < value.length) {
        value.substring(index + 1, index + 3).toIntOrNull(16)
      } else {
        null
      }
    when {
      char == '+' -> {
        bytes.write(' '.code)
        index++
      }

      hex != null -> {
        bytes.write(hex)
        index += 3
      }

      else -> {
        bytes.write(char.code)
        index++
      }
    }
  }
  return String(bytes.toByteArray(), Charsets.UTF_8)
}

private fun decodeJwtPayload(token: String): JsonObject? {
  val segments = token.split('.')
  if (segments.size < 2) return null
  val payload = segments[1]
  // base64url 去掉 padding；java.util.Base64 的解码器要求长度是 4 的倍数。
  val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
  val bytes = runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull() ?: return null
  return runCatching { json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }.getOrNull()
}

private fun jsonObjectOrNull(body: String): JsonObject? =
  runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.longOrNull(key: String): Long? =
  runCatching { this[key]?.jsonPrimitive?.content?.toLong() }.getOrNull()

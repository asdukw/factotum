package com.tappet.app.connector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Gmail 授权的网络与界面那一半：回环重定向 + PKCE 换码 + 令牌刷新 + 内存缓存。
 * 纯协议部分见 [GoogleOAuth]；为什么不用设备码流程见那个文件的类注释。
 *
 * **为什么授权在 Kotlin 而不在 dsh 插件里**：它要拉起浏览器、要等用户在那个页面上点同意、
 * 要起一个本地监听器接收跳转 —— 那是**界面**该干的事，不是一个工具调用该干的事
 * （工具调用会长时间挂住）。插件那边只拿一个短命的 access token
 * （经 [com.tappet.app.bridge.ConnectorBridgeServer]），从不接触 refresh token。
 *
 * 三条不变量（与 Outlook 那套一致）：
 * 1. **refresh token 只进 [SecretVault]**（经 [ConnectorStore]），access token 只在内存。
 * 2. **不打印任何令牌**，任何形态（含前缀）。日志只有错误码。
 * 3. 换令牌**串行**（[refreshLock]）：并发请求同时发现过期会各换一次，而端点对
 *    refresh 有速率限制，撞上去表现为「突然要重新授权」。
 */
internal object GmailAuth {
  private const val TAG = "TappetConnector"

  private const val EXPIRY_MARGIN_MILLIS = 60_000L

  /**
   * 用户在授权页上停留的上限。超过就当放弃 —— 监听器不能一直挂着。
   *
   * 10 分钟而不是 5 分钟：**首次**授权要过两步验证、可能要读「未验证应用」的警告页，
   * 真机上第一轮就是这么超时的（那时还没有任何日志，只能靠猜）。
   */
  private const val CONSENT_TIMEOUT_MILLIS = 10 * 60 * 1000L

  private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

  private val http =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()

  private val refreshLock = Mutex()

  /** 内存里的 access token。**不落盘**：短命凭据，重启后重换一次比存起来安全。 */
  @Volatile private var cached: CachedToken? = null

  private data class CachedToken(val accessToken: String, val expiresAtMillis: Long) {
    fun isFresh(now: Long): Boolean = now + EXPIRY_MARGIN_MILLIS < expiresAtMillis
  }

  fun status(context: Context): ConnectorStatus = ConnectorStore.status(context, GMAIL_CONNECTOR_ID)

  /**
   * 授权流程。**冷流**：每 collect 一次就是一次完整的授权尝试，界面用 `job.cancel()` 取消。
   *
   * 步骤：起回环监听器 → 拉起浏览器 → 等跳转 → 校验 state → 用 PKCE 换令牌 → 落盘。
   * 监听器在 `finally` 里关掉 —— 取消（用户点「取消」或离开页面）时它也必须关，
   * 否则端口会一直占着到进程结束。
   */
  fun connect(context: Context, clientId: String): Flow<ConnectEvent> =
    flow {
      val id = clientId.trim()
      if (id.isEmpty()) {
        emit(ConnectEvent.Failed("先填客户端 ID"))
        return@flow
      }
      val receiver = LoopbackReceiver()
      try {
        val verifier = newCodeVerifier()
        val state = UUID.randomUUID().toString()
        val url = authorizationUrl(id, receiver.redirectUri, codeChallenge(verifier), state)
        // 日志只记**步骤与形状**：端口、有没有 code / error、state 对不对。
        // 绝不记 code、token、邮箱地址（AGENTS.md 红线 2 / docs/email-connector.md §2.1）。
        Log.i(TAG, "connect: listener on ${receiver.redirectUri}")
        if (!openBrowser(context, url)) {
          emit(ConnectEvent.Failed("打不开浏览器：设备上没有能处理 https 链接的应用"))
          return@flow
        }
        emit(ConnectEvent.AwaitingConsent)
        val params = receiver.awaitRedirect(CONSENT_TIMEOUT_MILLIS)
        if (params == null) {
          Log.w(TAG, "connect: no redirect within ${CONSENT_TIMEOUT_MILLIS / 1000}s")
          emit(ConnectEvent.Failed("授权超时（10 分钟内没有完成），请重新连接"))
          return@flow
        }
        Log.i(
          TAG,
          "connect: redirect hasCode=${params.code != null} error=${params.error} stateMatch=${params.state == state}",
        )
        if (params.error != null) {
          emit(ConnectEvent.Failed(describe(params.error, params.errorDescription)))
          return@flow
        }
        // state 是我们自己发的随机串：对不上说明这个回调不是这次授权发起的（同设备上别的
        // 进程、或一个伪造的页面）。CSRF 防护就靠这一条，不能省。
        if (params.state != state) {
          emit(ConnectEvent.Failed("授权回调的 state 不匹配，已中止"))
          return@flow
        }
        val code = params.code
        if (code == null) {
          emit(ConnectEvent.Failed("授权回调里没有 code"))
          return@flow
        }
        // 客户端密码不是机密（公共客户端），但**必须发**：不带会被令牌端点拒成
        // `client_secret is missing` —— 真机上就是这么栽的。
        val secret = ConnectorStore.effectiveClientSecret(context, GMAIL_CONNECTOR_ID)
        when (val outcome = exchangeCode(id, secret, code, verifier, receiver.redirectUri)) {
          is TokenOutcome.Granted -> {
            Log.i(TAG, "connect: token granted refresh=${outcome.refreshToken != null}")
            persist(context, outcome)
            emit(ConnectEvent.Connected)
          }

          is TokenOutcome.Denied -> {
            Log.w(TAG, "connect: token denied code=${outcome.code} desc=${outcome.description}")
            emit(ConnectEvent.Failed(describe(outcome.code, outcome.description)))
          }
        }
      } finally {
        receiver.close()
      }
    }
      .flowOn(Dispatchers.IO)

  /**
   * 取一个可用的 access token，必要时用 refresh token 换。
   *
   * 缓存命中（距过期还有 60 秒以上）时不发任何请求 —— 插件每次工具调用都会问一次，
   * 每次都换的话既慢又容易撞上端点的速率限制。
   */
  suspend fun accessToken(context: Context): AccessTokenResult {
    freshToken()?.let { return it }
    return refreshLock.withLock {
      // 双重检查：等锁期间可能已经有人换好了。
      freshToken()?.let { return@withLock it }
      // effectiveClientId 而不是 clientId：包里预置了默认值时，用户什么都不用填就能连。
      val clientId = ConnectorStore.effectiveClientId(context, GMAIL_CONNECTOR_ID)
      if (clientId.isBlank()) {
        return@withLock AccessTokenResult.Unavailable(
          code = "NOT_CONFIGURED",
          summary = "还没有配置 Gmail：请在 设置 → 连接器 → Gmail 里填写客户端 ID",
        )
      }
      val refreshToken = ConnectorStore.refreshToken(context, GMAIL_CONNECTOR_ID)
      if (refreshToken.isNullOrBlank()) {
        return@withLock AccessTokenResult.Unavailable(
          code = "NOT_CONNECTED",
          summary = "还没有连接 Gmail：请在 设置 → 连接器 → Gmail 里完成授权",
        )
      }
      val clientSecret = ConnectorStore.effectiveClientSecret(context, GMAIL_CONNECTOR_ID)
      val refreshForm = refreshTokenForm(clientId, clientSecret, refreshToken)
      when (val outcome = parseTokenResponse(postForm(GOOGLE_TOKEN_URL, refreshForm).orEmpty())) {
        is TokenOutcome.Granted -> {
          persist(context, outcome)
          freshToken() ?: AccessTokenResult.Unavailable("REFRESH_FAILED", "换到令牌但没能缓存，请重试")
        }

        is TokenOutcome.Denied -> {
          Log.w(TAG, "refresh denied code=${outcome.code} desc=${outcome.description}")
          // refresh token 失效（改密码 / 用户在 Google 账号页撤销 / 测试模式下超过 7 天）：
          // 缓存一并清掉，否则界面还显示「已连接」，而每次调用都失败。
          if (outcome.code == "invalid_grant") disconnect(context)
          AccessTokenResult.Unavailable(
            code = "REFRESH_FAILED",
            summary = "授权已失效（${outcome.code}），请在设置里重新连接 Gmail",
          )
        }
      }
    }
  }

  /** 断开：清本机凭据与缓存。**不动客户端 id**（见 [ConnectorStore.clearCredentials]）。 */
  fun disconnect(context: Context): Boolean {
    cached = null
    return ConnectorStore.clearCredentials(context, GMAIL_CONNECTOR_ID)
  }

  private fun freshToken(): AccessTokenResult.Ok? {
    val token = cached ?: return null
    if (!token.isFresh(System.currentTimeMillis())) return null
    return AccessTokenResult.Ok(token.accessToken, token.expiresAtMillis)
  }

  private fun persist(context: Context, granted: TokenOutcome.Granted) {
    cached =
      CachedToken(
        accessToken = granted.accessToken,
        expiresAtMillis = System.currentTimeMillis() + granted.expiresInSeconds * 1000L,
      )
    // refresh token 没带就沿用旧的（RFC 6749 §6 允许刷新响应不返回新的）。
    granted.refreshToken?.takeIf { it.isNotEmpty() }?.let {
      ConnectorStore.saveRefreshToken(context, GMAIL_CONNECTOR_ID, it)
    }
    granted.account?.let { ConnectorStore.saveAccount(context, GMAIL_CONNECTOR_ID, it) }
  }

  private fun exchangeCode(
    clientId: String,
    clientSecret: String,
    code: String,
    verifier: String,
    redirectUri: String,
  ): TokenOutcome =
    parseTokenResponse(
      postForm(
        GOOGLE_TOKEN_URL,
        codeExchangeForm(clientId, clientSecret, code, verifier, redirectUri),
      ).orEmpty(),
    )

  private fun openBrowser(context: Context, url: String): Boolean =
    runCatching {
      context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }.isSuccess

  /**
   * 发一个表单 POST，返回响应体。**不返回状态码**：令牌端点把成功与失败都放在 body 里，
   * 按状态码分流会把一次正常的 400（`invalid_grant`）当网络错误。
   *
   * 失败只回 null，**不回显响应体** —— 它可能带请求细节。
   */
  private fun postForm(url: String, form: String): String? {
    val request =
      Request.Builder()
        .url(url)
        .post(form.toRequestBody(formMediaType))
        .build()
    return runCatching { http.newCall(request).execute().use { it.body?.string().orEmpty() } }
      .getOrNull()
  }

  /**
   * OAuth 错误码 → 给用户看的一句话。**认不出来时把码和端点的 error_description 一起给出去**，
   * 不编也不吞 —— 真机上第一轮就卡在一个没有映射的 `invalid_request` 上，
   * 而当时既没日志也没描述，只能靠猜。描述讲的是我们这次请求哪里不对，不含用户数据。
   */
  private fun describe(code: String, description: String? = null): String {
    val known =
      when (code) {
        // 这个码有两种来源，而首版**预置客户端停在测试模式**，第二种更常见：
        // 别人的账号不在测试用户白名单里，Google 直接挡在「Access blocked」页。
        // 说成「你拒绝了访问」是误导 —— 用户会以为自己点错了。
        "access_denied" ->
          "授权被拒绝。可能是你在授权页点了拒绝；" +
            "也可能是这个预置客户端还停在测试模式、你的账号不在它的测试用户白名单里 —— " +
            "那就用下面那行「使用我自己的 OAuth 客户端 ID」填自己的"

        "invalid_client" -> "客户端 ID 无效：确认它来自「OAuth 客户端 ID」且类型是「桌面应用」"

        "redirect_uri_mismatch" -> "重定向地址被拒绝：确认客户端类型是「桌面应用」（回环地址只对它开放）"

        "invalid_grant" -> "授权已失效，请重新连接"

        else -> null
      }
    val detail = description?.take(MAX_ERROR_DETAIL_CHARS)?.takeIf { it.isNotEmpty() }
    return listOfNotNull(known ?: "授权失败：$code", detail).joinToString("｜")
  }

  /** 端点的描述文本可能很长；截断只为别把界面撑爆，不是为了隐藏什么。 */
  private const val MAX_ERROR_DETAIL_CHARS = 300

  /**
   * 一次性回环监听器：Google 授权完成后浏览器会跳到 `http://127.0.0.1:<port>/?code=...`。
   * 我们收下这一个请求，回一个「可以回去了」的页面，然后关掉。
   *
   * **端口交给系统分配**（`ServerSocket(0)`）：授权 URL 里必须带上 redirect_uri，
   * 而 Google 的桌面应用类型允许任意回环端口 —— 写死端口在被占用时就直接失败了。
   *
   * 只绑 `127.0.0.1`：这个监听器会收到授权码，绑 `0.0.0.0` 等于把它暴露给同网段。
   */
  private class LoopbackReceiver {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String = "http://127.0.0.1:${server.localPort}"

    /**
     * 等浏览器跳回来。返回 null = 超时或一直没等到一个能解析的请求。
     *
     * 循环是必要的：浏览器在跳转前后还会发 `/favicon.ico` 之类的请求，
     * 那些解析出来没有 code，要丢掉继续等（每个都回一个 204，免得浏览器挂在那儿）。
     */
    fun awaitRedirect(timeoutMillis: Long): RedirectParams? {
      val deadline = System.currentTimeMillis() + timeoutMillis
      var found: RedirectParams? = null
      while (found == null && System.currentTimeMillis() < deadline) {
        server.soTimeout = remainingMillis(deadline)
        val socket = runCatching { server.accept() }.getOrNull()
        if (socket != null) found = handle(socket)
      }
      return found
    }

    fun close() {
      runCatching { server.close() }
    }

    private fun handle(socket: Socket): RedirectParams? =
      socket.use { client ->
        val line = readRequestLine(client)
        val params = line?.let(::parseRedirectRequest)
        writeResponse(client, authorized = params?.code != null)
        params
      }

    /** 给浏览器一个能看的落地页 —— 不然用户看到的是一个连接被重置的空白页。 */
    private fun writeResponse(socket: Socket, authorized: Boolean) {
      val body =
        if (authorized) {
          "<!doctype html><meta charset=utf-8><title>完成</title><p>授权完成，可以回到 Tappet 了。"
        } else {
          ""
        }
      val status = if (authorized) "200 OK" else "204 No Content"
      val head =
        "HTTP/1.1 $status\r\n" +
          "Content-Type: text/html; charset=utf-8\r\n" +
          "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
          "Connection: close\r\n\r\n"
      runCatching {
        BufferedOutputStream(socket.getOutputStream()).use { out ->
          out.write(head.toByteArray(Charsets.US_ASCII))
          out.write(body.toByteArray(Charsets.UTF_8))
          out.flush()
        }
      }
    }

    private fun readRequestLine(socket: Socket): String? {
      val input = BufferedInputStream(socket.getInputStream())
      val buffer = StringBuilder()
      while (buffer.length < MAX_REQUEST_LINE) {
        val next = input.read()
        if (next < 0 || next == '\n'.code) return buffer.toString().trimEnd('\r')
        buffer.append(next.toChar())
      }
      return buffer.toString()
    }

    private fun remainingMillis(deadline: Long): Int =
      (deadline - System.currentTimeMillis()).coerceIn(MIN_ACCEPT_TIMEOUT_MILLIS, MAX_ACCEPT_TIMEOUT_MILLIS.toLong()).toInt()

    private companion object {
      const val MAX_REQUEST_LINE = 8192
      const val MIN_ACCEPT_TIMEOUT_MILLIS = 1L
      const val MAX_ACCEPT_TIMEOUT_MILLIS = 10_000L
    }
  }
}

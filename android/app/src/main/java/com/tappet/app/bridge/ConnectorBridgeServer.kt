package com.tappet.app.bridge

import android.content.Context
import android.util.Log
import com.tappet.app.connector.AccessTokenResult
import com.tappet.app.connector.GMAIL_CONNECTOR_ID
import com.tappet.app.connector.GmailAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 连接器插件的本地桥：只监听 127.0.0.1，token 鉴权，**只发凭据、不代理 API**。
 *
 * 为什么是**另一个**桥，而不是给 [DeviceBridgeServer] 加两个 action：
 * 1. 那个桥只讲 `DeviceAction`，把 access token 塞进 `DeviceActionResult` 是类型上的错配；
 * 2. 它是无障碍那条命脉（九个原语全在上面），不该为连接器承担回归风险；
 * 3. 两者的信任边界不同：设备桥交出的是「能操作这台手机」，连接器桥交出的是
 *    「一个短命的、只读邮箱的令牌」。混在一个端点里，将来收紧其中一个就得同时动另一个。
 *
 * 桥只做一件事：把 Kotlin 侧换好的 access token 交给插件。
 * **Graph 的 REST 语义（`$select` / `$search` / 分页）全在插件里**，不在这里 ——
 * 否则一处 API 会被劈成「宿主知道路径、插件知道描述」两半（见 `docs/email-connector.md` §5.1）。
 */
object ConnectorBridgeServer {
  private const val TAG = "TappetConnector"

  /** 鉴权头名。与设备桥的 `x-android-bridge-token` 分开：两个桥的令牌互不通用。 */
  private const val TOKEN_HEADER = "x-connector-bridge-token"

  private val started = AtomicBoolean(false)
  private val token = UUID.randomUUID().toString()

  @Volatile private var port: Int = -1

  @Volatile private var appContext: Context? = null

  /** 启动（幂等），返回给 Node 的 base URL；token 通过 [authToken] 读取。 */
  fun start(context: Context): String {
    appContext = context.applicationContext
    if (started.compareAndSet(false, true)) {
      val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      port = socket.localPort
      Thread {
        Log.i(TAG, "connector bridge listening on 127.0.0.1:$port")
        socket.use {
          while (!it.isClosed) {
            val client = runCatching { it.accept() }.getOrElse { break }
            Thread { handle(client) }.start()
          }
        }
      }
        .apply { isDaemon = true }
        .start()
    }
    return "http://127.0.0.1:$port"
  }

  fun authToken(): String = token

  private fun handle(client: Socket) {
    client.use { socket ->
      val input = BufferedInputStream(socket.getInputStream())
      val output = BufferedOutputStream(socket.getOutputStream())
      val response =
        runCatching { dispatch(input) }
          .getOrElse { throwable ->
            // 只记异常类型：异常消息里可能带 URL，而 URL 可能带查询参数。
            Log.w(TAG, "request failed: ${throwable.javaClass.simpleName}")
            errorJson("BRIDGE_FAILED", "桥处理失败：${throwable.javaClass.simpleName}")
          }
      val bytes = response.toByteArray(Charsets.UTF_8)
      val header =
        "HTTP/1.1 200 OK\r\n" +
          "Content-Type: application/json; charset=utf-8\r\n" +
          "Content-Length: ${bytes.size}\r\n" +
          "Connection: close\r\n\r\n"
      output.write(header.toByteArray(Charsets.US_ASCII))
      output.write(bytes)
      output.flush()
    }
  }

  /** 解析并执行一个请求；与设备桥一样极简：只支持 POST /action。 */
  private fun dispatch(input: BufferedInputStream): String {
    val requestLine = readLine(input) ?: return errorJson("BAD_REQUEST", "空请求")
    val parts = requestLine.split(' ')
    if (parts.firstOrNull() != "POST" || parts.getOrNull(1)?.startsWith("/action") != true) {
      return errorJson("BAD_REQUEST", "不支持的请求：$requestLine")
    }
    val headers = readHeaders(input)
    if (headers[TOKEN_HEADER] != token) return errorJson("UNAUTHORIZED", "未授权")
    val body = readJsonBody(input, headers["content-length"]?.toIntOrNull() ?: 0)
    val action = body?.string("action")
    if (action == null) return errorJson("BAD_REQUEST", "非法 JSON 或缺 action")
    return when (action) {
      "access_token" -> accessToken(body.string("connector"))
      else -> errorJson("UNKNOWN_ACTION", "未知 action：$action")
    }
  }

  /**
   * 读头，读到一个空行（或流结束）为止。
   *
   * 写成「先读一次、循环里再读」而不是 `while (true)` + 两个 break：后者的
   * break/continue 计数会触发 detekt 的 LoopWithTooManyJumpStatements，
   * 而这条规则报的确实是可读性（设备桥那份已经进了基线，这里不重复欠账）。
   */
  private fun readHeaders(input: BufferedInputStream): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    var line = readLine(input)
    while (line != null && line.isNotEmpty()) {
      val colon = line.indexOf(':')
      if (colon > 0) {
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
      }
      line = readLine(input)
    }
    return headers
  }

  /** 按 Content-Length 读满请求体并解析成 JSON。读不满 / 不是 JSON 都回 null。 */
  private fun readJsonBody(input: BufferedInputStream, contentLength: Int): JsonObject? {
    val length = contentLength.coerceAtLeast(0)
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
      val count = input.read(body, read, length - read)
      if (count < 0) break
      read += count
    }
    return runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
  }

  /**
   * 交付一个 access token。**这是本桥唯一的动作**，参数是连接器 id
   * （`{"action":"access_token","connector":"gmail"}`）—— 加一个连接器只在这里加一个分支。
   *
   * 令牌本身**不落日志**，只记连接器名、成功与否与失败码
   * （与设备桥「只记动作与结构化结果」同一条规矩）。
   */
  private fun accessToken(connectorId: String?): String {
    val context = appContext ?: return errorJson("BRIDGE_FAILED", "连接器桥尚未初始化")
    val id = connectorId?.trim().orEmpty()
    if (id.isEmpty()) return errorJson("BAD_REQUEST", "缺少 connector")
    val result =
      when (id) {
        GMAIL_CONNECTOR_ID -> runBlocking { GmailAuth.accessToken(context) }
        else -> return errorJson("UNKNOWN_CONNECTOR", "未知连接器：$id")
      }
    return when (result) {
      is AccessTokenResult.Ok -> {
        Log.i(TAG, "access_token connector=$id ok")
        buildJsonObject {
          put("ok", true)
          put("accessToken", result.accessToken)
          put("expiresAt", result.expiresAtMillis)
        }
          .toString()
      }

      is AccessTokenResult.Unavailable -> {
        Log.i(TAG, "access_token connector=$id failed code=${result.code}")
        errorJson(result.code, result.summary)
      }
    }
  }

  private fun readLine(input: BufferedInputStream): String? {
    val buffer = StringBuilder()
    while (true) {
      val next = input.read()
      if (next < 0) return if (buffer.isEmpty()) null else buffer.toString()
      if (next == '\n'.code) return buffer.toString().trimEnd('\r')
      buffer.append(next.toChar())
    }
  }

  private fun errorJson(code: String, message: String): String =
    buildJsonObject {
      put("ok", false)
      put("code", code)
      put("summary", message)
    }
      .toString()
}

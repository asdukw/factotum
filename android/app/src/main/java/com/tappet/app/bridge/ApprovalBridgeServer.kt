package com.tappet.app.bridge

import android.util.Log
import com.tappet.app.agent.ApprovalDecision
import com.tappet.app.agent.ApprovalHub
import com.tappet.app.agent.PendingApproval
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 审批的本地桥：dsh 的 `approval/request` 从这里进到 App 的确认面板，结果从这里回去。
 *
 * **为什么再开一个桥**（前面已经有设备 / 连接器 / 提问三个）：理由与提问桥完全一样 ——
 * 传的是「人对一次操作的许可」，命脉与信任边界都不同。形状对齐 dsh 的
 * `ApprovalRequestEvent` / `ApprovalOutcome`（见 `docs/approvals.md`）。
 *
 * **这个桥是阻塞的**：`approve` 这个 HTTP 请求会一直挂着，直到用户点允许 / 拒绝、
 * 关掉面板、或插件那边取消（`fetch` 断开）。与提问桥一样，它不是一个快请求。
 *
 * **不记 reason**：它可能带收件人、主题等用户内容（`docs/email-connector.md` §2）。
 * 日志只记工具名与结果。
 */
object ApprovalBridgeServer {
  private const val TAG = "TappetApproval"

  /** 鉴权头名。四个桥的令牌互不通用。 */
  private const val TOKEN_HEADER = "x-approval-bridge-token"

  private val started = AtomicBoolean(false)
  private val token = UUID.randomUUID().toString()

  @Volatile private var port: Int = -1

  /** 启动（幂等），返回给 Node 的 base URL；token 通过 [authToken] 读取。 */
  fun start(): String {
    if (started.compareAndSet(false, true)) {
      val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      port = socket.localPort
      Thread {
        Log.i(TAG, "approval bridge listening on 127.0.0.1:$port")
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
            // 只记异常类型：异常消息里可能带请求内容。
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

  /** 与另外三个桥一样极简：只支持 POST /action。 */
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
      "approve" -> approve(body)
      else -> errorJson("UNKNOWN_ACTION", "未知 action：$action")
    }
  }

  /**
   * 把一次审批交给 UI 并挂起等结果。
   *
   * 用户关掉面板 → `cancelled`（不是拒绝，但同样不授权）；桥返回 `ok:true` + 结果，
   * 插件只认封闭词汇里的值。
   */
  private fun approve(body: JsonObject): String {
    val toolName = body.string("toolName")?.trim().orEmpty()
    if (toolName.isEmpty()) return errorJson("BAD_REQUEST", "缺少 toolName")
    val request =
      PendingApproval(
        toolName = toolName,
        callId = body.string("callId"),
        reason = body.string("reason"),
        displayReason = parseDisplayReason(body["displayReason"]),
      )
    val decision = runBlocking { ApprovalHub.request(request) }
    Log.i(TAG, "approve tool=$toolName decision=${decision.name.lowercase()}")
    return buildJsonObject {
      put("ok", true)
      put(
        "outcome",
        when (decision) {
          ApprovalDecision.ALLOW_ONCE -> "allowed-once"
          ApprovalDecision.REJECT -> "rejected"
          ApprovalDecision.CANCEL -> "cancelled"
        },
      )
    }
      .toString()
  }

  /** 只接受字符串值；形状不对的本地化文案直接丢掉，不影响审批本身。 */
  private fun parseDisplayReason(element: JsonElement?): Map<String, String> {
    val objectValue = element as? JsonObject ?: return emptyMap()
    return objectValue.mapNotNull { (locale, value) ->
      runCatching { value.jsonPrimitive.content }.getOrNull()?.let { locale to it }
    }
      .toMap()
  }

  private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

  /**
   * 读头，读到一个空行（或流结束）为止。
   *
   * 写成「先读一次、循环里再读」而不是 `while (true)` + 两个 break：后者的
   * break/continue 计数会触发 detekt 的 LoopWithTooManyJumpStatements
   * （与提问桥同一处取舍）。
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

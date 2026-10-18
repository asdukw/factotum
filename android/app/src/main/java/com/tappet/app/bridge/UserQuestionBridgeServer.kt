package com.tappet.app.bridge

import android.util.Log
import com.tappet.app.agent.PendingUserQuestions
import com.tappet.app.agent.UserQuestion
import com.tappet.app.agent.UserQuestionAnswer
import com.tappet.app.agent.UserQuestionHub
import com.tappet.app.agent.UserQuestionOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
 * 用户提问的本地桥：`ask_user_question` 的问题从这里进到 App 的 UI，答案从这里回去。
 *
 * **为什么是第三个桥**（前两个是 [DeviceBridgeServer] 与 [ConnectorBridgeServer]）：
 * 理由与连接器桥那三条一样 —— 类型不同（这里传的是「人给的答案」）、命脉不同
 * （设备桥是无障碍那条命脉，不该为 UI 承担回归风险）、信任边界不同
 * （这条桥交出去的是「能让用户看见一句话、并拿到他的回答」）。
 *
 * **dsh 原生怎么做的**：`ctx.userQuestions` 的答案来自注册在 Cordis waterfall
 * `user-questions/request` 上的 answerer，官方 Web UI 就是这么接的（经 `dsh-api-remotes`
 * 转发）。我们的 Kotlin 宿主不连那条 Web 通道，所以由 `dsh-plugin-user-questions`
 * 自己当 answerer，把请求转到这里 —— 形状逐字对齐 dsh 的
 * `AskUserQuestionRequestEvent` / `AskUserQuestionAnswer`（见 `docs/user-questions.md`）。
 *
 * **这个桥是阻塞的**：`ask` 这个 HTTP 请求会一直挂着，直到用户作答、关掉面板、或插件那边
 * 取消（`fetch` 断开）。所以它不像前两个桥那样是「一问一答」的快请求。
 */
object UserQuestionBridgeServer {
  private const val TAG = "TappetQuestion"

  /** 鉴权头名。三个桥的令牌互不通用。 */
  private const val TOKEN_HEADER = "x-question-bridge-token"

  private val started = AtomicBoolean(false)
  private val token = UUID.randomUUID().toString()

  @Volatile private var port: Int = -1

  /** 启动（幂等），返回给 Node 的 base URL；token 通过 [authToken] 读取。 */
  fun start(): String {
    if (started.compareAndSet(false, true)) {
      val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      port = socket.localPort
      Thread {
        Log.i(TAG, "question bridge listening on 127.0.0.1:$port")
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
            // 只记异常类型：异常消息里可能带 URL / 用户输入。
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

  /** 与另外两个桥一样极简：只支持 POST /action。 */
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
      "ask" -> ask(body)
      else -> errorJson("UNKNOWN_ACTION", "未知 action：$action")
    }
  }

  /**
   * 把一批问题交给 UI 并挂起等答案。
   *
   * **挂起的是这条 HTTP 请求本身**：插件那边的 `fetch` 会一直等，直到这里返回。
   * 用户关掉面板 → 回 `DISMISSED`（ok:false），插件据此 `next()` 让路 ——
   * 这与 dsh「关闭不是回答」的语义一致（见 `docs/user-questions.md` §3）。
   */
  private fun ask(body: JsonObject): String {
    val questions = parseQuestions(body["questions"])
    if (questions.isEmpty()) return errorJson("BAD_REQUEST", "至少需要一道题")
    val request =
      PendingUserQuestions(
        callId = body.string("callId"),
        timed = body["timed"]?.jsonPrimitive?.content == "true",
        questions = questions,
      )
    val answers = runBlocking { UserQuestionHub.ask(request) }
    if (answers == null) {
      Log.i(TAG, "ask dismissed questions=${questions.size}")
      return errorJson("DISMISSED", "用户没有回答（面板已关闭）")
    }
    Log.i(TAG, "ask answered questions=${questions.size} answers=${answers.size}")
    return buildJsonObject {
      put("ok", true)
      put(
        "answers",
        buildJsonArray {
          for (answer in answers) {
            add(
              buildJsonObject {
                put("id", answer.id)
                put("selected", buildJsonArray { answer.selected.forEach { add(JsonPrimitive(it)) } })
                // custom 为空时**不带这个字段**：dsh 的语义里「没写自定义答案」与
                // 「写了空串」不是一回事，后者会被当成用户真填了个空。
                answer.custom?.let { put("custom", it) }
              },
            )
          }
        },
      )
    }
      .toString()
  }

  /** 只取协议里有的字段；形状不对的条目直接丢掉（宁可少一题，也不给 UI 半个问题）。 */
  private fun parseQuestions(element: kotlinx.serialization.json.JsonElement?): List<UserQuestion> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { entry ->
      val item = entry as? JsonObject ?: return@mapNotNull null
      val id = item.string("id")?.trim().orEmpty()
      val question = item.string("question")?.trim().orEmpty()
      if (id.isEmpty() || question.isEmpty()) return@mapNotNull null
      UserQuestion(
        id = id,
        question = question,
        detail = item.string("detail"),
        header = item.string("header"),
        options = parseOptions(item["options"]),
        multiSelect = item["multiSelect"]?.jsonPrimitive?.content == "true",
      )
    }
  }

  private fun parseOptions(element: kotlinx.serialization.json.JsonElement?): List<UserQuestionOption> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { entry ->
      val item = entry as? JsonObject ?: return@mapNotNull null
      val label = item.string("label")?.trim().orEmpty()
      if (label.isEmpty()) return@mapNotNull null
      UserQuestionOption(label = label, description = item.string("description"))
    }
  }

  private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

  /**
   * 读头，读到一个空行（或流结束）为止。
   *
   * 写成「先读一次、循环里再读」而不是 `while (true)` + 两个 break：后者的
   * break/continue 计数会触发 detekt 的 LoopWithTooManyJumpStatements
   * （与连接器桥同一处取舍，理由见那边的注释）。
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

package com.tappet.app.bridge

import android.util.Log
import com.tappet.app.accessibility.TappetAccessibilityService
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.device.BridgeHealth
import com.tappet.app.device.DEFAULT_LONG_PRESS_MS
import com.tappet.app.device.DeviceAction
import com.tappet.app.device.DeviceActionResult
import com.tappet.app.device.MAX_LONG_PRESS_MS
import com.tappet.app.device.MIN_LONG_PRESS_MS
import com.tappet.app.device.PressKey
import com.tappet.app.device.SwipeDirection
import com.tappet.app.device.SystemIntents
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
 * dsh 设备插件的本地桥：只监听 127.0.0.1，token 鉴权，把 JSON 动作转成
 * [DeviceAction] 交给无障碍执行层。
 *
 * 只服务本进程内的内嵌 Node（端口随机、token 每次进程启动随机），不对外暴露。
 */
object DeviceBridgeServer {
  private const val TAG = "TappetBridge"

  private val started = AtomicBoolean(false)
  private val token = UUID.randomUUID().toString()

  @Volatile private var port: Int = -1

  @Volatile private var appContext: android.content.Context? = null

  /** 启动（幂等），返回给 Node 的 base URL；token 通过 [authToken] 读取。 */
  fun start(context: android.content.Context): String {
    appContext = context.applicationContext
    if (started.compareAndSet(false, true)) {
      val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      port = socket.localPort
      Thread {
        Log.i(TAG, "bridge listening on 127.0.0.1:$port")
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
            Log.w(TAG, "request failed: ${throwable.javaClass.simpleName}")
            """{"ok":false,"summary":"桥处理失败：${throwable.javaClass.simpleName}"}"""
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

  /** 解析并执行一个请求；这里极简：只支持本地桥自己的 POST /action。 */
  private fun dispatch(input: BufferedInputStream): String {
    val requestLine = readLine(input) ?: return errorJson("空请求")
    val parts = requestLine.split(' ')
    if (parts.size < 2 || parts[0] != "POST" || !parts[1].startsWith("/action")) {
      return errorJson("不支持的请求：$requestLine")
    }
    var contentLength = 0
    var authorized = false
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val name = line.substring(0, colon).trim().lowercase()
      val value = line.substring(colon + 1).trim()
      when (name) {
        "content-length" -> contentLength = value.toIntOrNull() ?: 0
        "x-android-bridge-token" -> authorized = value == token
      }
    }
    if (!authorized) return errorJson("未授权")
    val body = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val count = input.read(body, read, contentLength - read)
      if (count < 0) break
      read += count
    }
    val root =
      runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
        ?: return errorJson("非法 JSON")
    val action = root.string("action") ?: return errorJson("缺少 action")
    val deviceAction = toDeviceAction(action, root) ?: return errorJson("未知或参数不全的 action：$action")
    val context = appContext ?: return errorJson("设备桥尚未初始化")
    // 闹钟与日程**不经过无障碍**（见 SystemIntents）：先把它们分出去，
    // 否则无障碍没开时这两个动作会被误判成失败。
    val result =
      when (deviceAction) {
        is DeviceAction.SetAlarm -> SystemIntents.setAlarm(context, deviceAction)

        is DeviceAction.AddCalendarEvent -> SystemIntents.addCalendarEvent(context, deviceAction)

        else -> {
          val service = TappetAccessibilityService.get()
          if (service == null) {
            AccessibilityStatus.unavailableToolResult()
          } else {
            runBlocking { service.execute(deviceAction) }
          }
        }
      }
    BridgeHealth.record(result.code, action)
    // 只记动作与结构化结果（计数），不记 payload 正文
    Log.i(TAG, "$action ok=${result.ok} code=${result.code ?: "-"} ${result.summary}")
    return resultJson(result)
  }

  private fun toDeviceAction(action: String, root: JsonObject): DeviceAction? =
    when (action) {
      "read_screen" -> DeviceAction.ReadScreen

      "tap_node" -> root.int("ref")?.let(DeviceAction::TapNode)

      "tap_point" -> {
        val x = root.int("x")
        val y = root.int("y")
        if (x == null || y == null) null else DeviceAction.TapPoint(x, y)
      }

      "long_press" -> {
        val duration =
          root.int("duration_ms")?.toLong()?.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
            ?: DEFAULT_LONG_PRESS_MS
        val ref = root.int("ref")
        if (ref != null) {
          DeviceAction.LongPressNode(ref, duration)
        } else {
          val x = root.int("x")
          val y = root.int("y")
          if (x == null || y == null) null else DeviceAction.LongPressPoint(x, y, duration)
        }
      }

      "type_text" -> root.string("text")?.let(DeviceAction::TypeText)

      "press" ->
        root.string("key")?.let { raw ->
          runCatching { DeviceAction.Press(PressKey.valueOf(raw.uppercase())) }.getOrNull()
        }

      "press_back" -> DeviceAction.Press(PressKey.BACK)

      "launch_app" -> root.string("query")?.let(DeviceAction::LaunchApp)

      // capture_frame 是给模型的那条路（工具名一致）；screenshot 留着给 debug 与旧脚本。
      "screenshot", "capture_frame" -> DeviceAction.Screenshot

      "tap_target" ->
        root.frameBox()?.let { DeviceAction.TapFrame(it.frameId, it.x1, it.y1, it.x2, it.y2) }

      "long_press_target" ->
        root.frameBox()?.let {
          val duration =
            root.int("duration_ms")?.toLong()?.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
              ?: DEFAULT_LONG_PRESS_MS
          DeviceAction.LongPressFrame(it.frameId, it.x1, it.y1, it.x2, it.y2, duration)
        }

      "swipe" ->
        root.string("direction")?.let { raw ->
          runCatching { DeviceAction.Swipe(SwipeDirection.valueOf(raw.uppercase())) }.getOrNull()
        }

      "set_alarm" ->
        root.int("hour")?.let { hour ->
          root.int("minute")?.let { minute -> DeviceAction.SetAlarm(hour, minute, root.string("label")) }
        }

      "add_calendar_event" -> calendarEvent(root)

      else -> null
    }

  /** 日程参数单独一个函数：四个可空字段凑一个对象，写成嵌套 `let` 会撞 detekt 的嵌套深度。 */
  private fun calendarEvent(root: JsonObject): DeviceAction.AddCalendarEvent? {
    val title = root.string("title")?.takeIf { it.isNotBlank() }
    val begin = root.long("begin")
    val end = root.long("end")
    return if (title != null && begin != null && end != null) {
      DeviceAction.AddCalendarEvent(title, begin, end, root.string("location"), root.string("description"))
    } else {
      null
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

  private fun resultJson(result: DeviceActionResult): String =
    buildJsonObject {
      put("ok", result.ok)
      put("summary", result.summary)
      result.payload?.let { put("payload", it) }
      // 机器可读的失败原因：插件 / UI / 日志都能用，不必去匹配上面的中文
      result.code?.let { put("code", it) }
      // 视觉路径的验证结论（true/false）。null = 验不了，不写进 JSON，别让插件把它当 false。
      result.changed?.let { put("changed", it) }
    }
      .toString()

  private fun errorJson(message: String): String =
    buildJsonObject {
      put("ok", false)
      put("summary", message)
    }
      .toString()
}

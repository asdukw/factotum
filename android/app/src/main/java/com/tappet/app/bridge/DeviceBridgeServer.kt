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
import com.tappet.app.proot.ProotRuntime
import com.tappet.app.schedule.ScheduledTaskStore
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
    val deviceAction = toDeviceAction(action, root) ?: return errorJson(actionParseError(action))
    val context = appContext ?: return errorJson("设备桥尚未初始化")
    val result = executeAction(context, deviceAction)
    BridgeHealth.record(result.code, action)
    // 只记动作与结构化结果（计数），不记 payload 正文。`run_command` 的 summary 就是**命令输出**
    // 本身（可能是私有文件内容），所以它只记长度 —— 与「绝不记录屏幕 / 聊天内容」同一条规矩。
    val logged = if (action == "run_command") "chars=${result.summary.length}" else result.summary
    Log.i(TAG, "$action ok=${result.ok} code=${result.code ?: "-"} $logged")
    return resultJson(result)
  }

  /**
   * 动作分发。
   *
   * 闹钟、日程、定时任务与内嵌 Linux 的 shell **不经过无障碍**（见 `SystemIntents` /
   * `docs/scheduled-tasks.md` / `docs/linux-on-phone.md`）：先把它们分出去，否则无障碍没开时
   * 这些动作会被误判成失败。
   */
  private fun executeAction(context: android.content.Context, action: DeviceAction): DeviceActionResult =
    when (action) {
      is DeviceAction.SetAlarm -> SystemIntents.setAlarm(context, action)

      is DeviceAction.AddCalendarEvent -> SystemIntents.addCalendarEvent(context, action)

      is DeviceAction.ListCalendarEvents -> SystemIntents.listCalendarEvents(context, action)

      is DeviceAction.UpdateCalendarEvent -> SystemIntents.updateCalendarEvent(context, action)

      is DeviceAction.DeleteCalendarEvent -> SystemIntents.deleteCalendarEvent(context, action)

      is DeviceAction.CreateScheduledTask ->
        runBlocking {
          ScheduledTaskStore.create(
            context,
            action.title,
            action.prompt,
            action.hour,
            action.minute,
          )
        }

      is DeviceAction.ListScheduledTasks ->
        runBlocking { ScheduledTaskStore.list(context, action.includeDisabled) }

      is DeviceAction.UpdateScheduledTask ->
        runBlocking {
          ScheduledTaskStore.update(
            context,
            action.id,
            action.title,
            action.prompt,
            action.hour,
            action.minute,
            action.enabled,
          )
        }

      is DeviceAction.DeleteScheduledTask ->
        runBlocking { ScheduledTaskStore.delete(context, action.id) }

      // 内嵌 Linux 的 shell：**不走无障碍**，也不经过 Termux —— 本进程域里起一次 proot。
      is DeviceAction.RunCommand -> runCommand(context, action)

      else -> {
        val service = TappetAccessibilityService.get()
        if (service == null) {
          AccessibilityStatus.unavailableToolResult()
        } else {
          runBlocking { service.execute(action) }
        }
      }
    }

  /** 桥认识的 action 名。必须与 [toDeviceAction] 的 `when` 分支同增同删（有测试盯着）。 */
  internal val KNOWN_ACTIONS: Set<String> =
    setOf(
      "read_ui_tree",
      "tap_node",
      "tap_point",
      "long_press",
      "type_text",
      "press",
      "press_back",
      "launch_app",
      "screenshot",
      "capture_screenshot",
      "tap_target",
      "long_press_target",
      "swipe",
      "set_alarm",
      "add_calendar_event",
      "list_calendar_events",
      "update_calendar_event",
      "delete_calendar_event",
      "schedule_task",
      "list_scheduled_tasks",
      "update_scheduled_task",
      "delete_scheduled_task",
      "run_command",
    )

  /** 每流最多回给模型多少字节；超出的部分留头留尾，中间截断。 */
  private const val MAX_SHELL_STREAM = 16 * 1024
  private const val MIN_SHELL_TIMEOUT_MS = 1_000L
  private const val DEFAULT_SHELL_TIMEOUT_MS = 60_000L
  private const val MAX_SHELL_TIMEOUT_MS = 600_000L

  /**
   * 跑一条 guest 命令，排成模型能读的文本（`$ cmd` / `exit=… | Nms` / stdout / `[stderr]`）。
   *
   * 两个边界：
   * - **输出要截断**：模型上下文不是日志桶。留头留尾，中间用一行标记截断。
   * - **环境没起来要和命令失败分开说**：前者重试同一条命令永远不会成功（`SHELL_UNAVAILABLE`），
   *   后者才是模型自己能处理的。
   * - 这里有意吞掉 `Exception`：起 proot 可能失败在解压（`IOException`）、符号链接（`ErrnoException`）、
   *   缺依赖（`IllegalStateException`）任何一层，而对模型而言它们都是同一件事。
   */
  @Suppress("TooGenericExceptionCaught")
  private fun runCommand(
    context: android.content.Context,
    action: DeviceAction.RunCommand,
  ): DeviceActionResult {
    val started = System.currentTimeMillis()
    val result =
      try {
        ProotRuntime.run(context, action.cmd, action.timeoutMs)
      } catch (exception: Exception) {
        Log.w(TAG, "run_command unavailable: ${exception.javaClass.simpleName}")
        return DeviceActionResult(
          ok = false,
          summary =
            "手机上的 Linux 环境没起来：${exception.message ?: exception.javaClass.simpleName}。" +
              "这不是命令本身的问题，重试同一条命令不会成功；把情况告诉用户。",
          code = DeviceAction.CODE_SHELL_UNAVAILABLE,
        )
      }
    val took = System.currentTimeMillis() - started
    val header = "$ ${action.cmd}"
    val timeoutNote = if (result.timedOut) " (killed on timeout)" else ""
    val status = "exit=${result.exitCode}$timeoutNote | ${took}ms"
    val parts = mutableListOf(header, status)
    truncateStream(result.stdout)?.let(parts::add)
    truncateStream(result.stderr)?.let { parts += "[stderr]\n$it" }
    if (result.stdout.isBlank() && result.stderr.isBlank()) parts += "(no output)"
    return DeviceActionResult(ok = result.exitCode == 0 && !result.timedOut, summary = parts.joinToString("\n"))
  }

  private fun truncateStream(text: String): String? {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return null
    if (trimmed.length <= MAX_SHELL_STREAM) return trimmed
    val head = trimmed.take(MAX_SHELL_STREAM / 2)
    val tail = trimmed.takeLast(MAX_SHELL_STREAM / 2)
    return "$head\n…（截断 ${trimmed.length - MAX_SHELL_STREAM} 字符）…\n$tail"
  }

  /**
   * 动作解析失败时回给模型的话。
   *
   * 两种失败要分开说：**名字不认识**是契约错误——本机装的是别的版本的插件，重试多少次都是同一个
   * 结果（实测模型会连着撞 6 次「未知或参数不全」才放弃）；**参数不全**是模型自己能补的，
   * 必须明说可以重试，否则它会连能修的错误一起放掉。
   */
  internal fun actionParseError(action: String): String =
    if (action in KNOWN_ACTIONS) {
      "参数不全：action「$action」缺少必填参数，补齐后可以重试。"
    } else {
      "工具契约错误（重试无效）：本 App 不认识 action「$action」——插件与本机 App 版本不匹配，" +
        "这个工具在当前安装里用不了。不要重试，也不要换参数再试；改用其它工具，或者直接把情况告诉用户。"
    }

  /**
   * 请求体 → 动作。[toDeviceAction] 保持 private：它的复杂度已经进了 detekt 基线，
   * 改可见性会让基线条目失配（见 config/detekt/baseline.xml 的规矩）。测试从这里进。
   */
  internal fun parseAction(action: String, root: JsonObject): DeviceAction? = toDeviceAction(action, root)

  private fun toDeviceAction(action: String, root: JsonObject): DeviceAction? =
    when (action) {
      // full=true 强制全量：模型看不到上一次读屏输出时（上下文被截断）用它。
      "read_ui_tree" -> DeviceAction.ReadScreen(full = root.boolean("full") == true)

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

      // capture_screenshot 是给模型的那条路（工具名一致）；screenshot 留着给 debug 与旧脚本。
      "screenshot", "capture_screenshot" -> DeviceAction.Screenshot

      "tap_target" ->
        root.frameBox()?.let { DeviceAction.TapScreenshot(it.screenshotId, it.x1, it.y1, it.x2, it.y2) }

      "long_press_target" ->
        root.frameBox()?.let {
          val duration =
            root.int("duration_ms")?.toLong()?.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
              ?: DEFAULT_LONG_PRESS_MS
          DeviceAction.LongPressScreenshot(it.screenshotId, it.x1, it.y1, it.x2, it.y2, duration)
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

      "list_calendar_events" ->
        root.long("begin")?.let { begin ->
          root.long("end")?.let { end -> DeviceAction.ListCalendarEvents(begin, end) }
        }

      "update_calendar_event" -> root.long("event_id")?.let { updateCalendarEvent(it, root) }

      "delete_calendar_event" -> root.long("event_id")?.let(DeviceAction::DeleteCalendarEvent)

      "schedule_task" -> createScheduledTask(root)

      "list_scheduled_tasks" ->
        DeviceAction.ListScheduledTasks(includeDisabled = root.boolean("include_disabled") != false)

      "update_scheduled_task" ->
        root.string("id")?.takeIf { it.isNotBlank() }?.let { updateScheduledTask(it, root) }

      "delete_scheduled_task" ->
        root.string("id")?.takeIf { it.isNotBlank() }?.let(DeviceAction::DeleteScheduledTask)

      "run_command" ->
        root.string("cmd")?.takeIf { it.isNotBlank() }?.let { cmd ->
          // 上限与插件声明一致：默认 60 s，最长 10 min。超时由 ProotRuntime 杀 proot，
          // `--kill-on-exit` 保证 guest 一起走。
          val timeout = (root.long("timeout_ms") ?: DEFAULT_SHELL_TIMEOUT_MS)
            .coerceIn(MIN_SHELL_TIMEOUT_MS, MAX_SHELL_TIMEOUT_MS)
          DeviceAction.RunCommand(cmd, timeout)
        }

      else -> null
    }

  /** 修改日程：除 event_id 外全是可空字段，null = 这一项不改。 */
  private fun updateCalendarEvent(eventId: Long, root: JsonObject): DeviceAction.UpdateCalendarEvent =
    DeviceAction.UpdateCalendarEvent(
      eventId = eventId,
      title = root.string("title"),
      beginMillis = root.long("begin"),
      endMillis = root.long("end"),
      location = root.string("location"),
      description = root.string("description"),
      reminderMinutes = root.int("reminder_minutes")?.takeIf { it >= 0 },
    )

  /** 定时任务参数：四个必填字段，缺一个整条动作就不成立。 */
  private fun createScheduledTask(root: JsonObject): DeviceAction.CreateScheduledTask? {
    val title = root.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val prompt = root.string("prompt")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hour = root.int("hour") ?: return null
    val minute = root.int("minute") ?: return null
    return DeviceAction.CreateScheduledTask(title, prompt, hour, minute)
  }

  /** 修改定时任务：除 id 外全是可空字段，null = 这一项不改。 */
  private fun updateScheduledTask(id: String, root: JsonObject): DeviceAction.UpdateScheduledTask =
    DeviceAction.UpdateScheduledTask(
      id = id,
      title = root.string("title"),
      prompt = root.string("prompt"),
      hour = root.int("hour"),
      minute = root.int("minute"),
      enabled = root.boolean("enabled"),
    )

  /** 日程参数单独一个函数：四个可空字段凑一个对象，写成嵌套 `let` 会撞 detekt 的嵌套深度。 */
  private fun calendarEvent(root: JsonObject): DeviceAction.AddCalendarEvent? {
    val title = root.string("title")?.takeIf { it.isNotBlank() }
    val begin = root.long("begin")
    val end = root.long("end")
    return if (title != null && begin != null && end != null) {
      DeviceAction.AddCalendarEvent(
        title,
        begin,
        end,
        root.string("location"),
        root.string("description"),
        root.int("reminder_minutes")?.takeIf { it >= 0 },
      )
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

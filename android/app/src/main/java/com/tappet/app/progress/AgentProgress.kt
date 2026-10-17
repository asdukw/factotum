package com.tappet.app.progress

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/** 悬浮球展示的一条执行进度。 */
enum class AgentProgressKind {
  STEP,
  OUTPUT,
  ERROR,
}

data class AgentProgressEntry(
  val id: Long,
  val kind: AgentProgressKind,
  val title: String,
  val detail: String? = null,
)

data class AgentProgressState(
  val sessionId: String,
  val task: String,
  val running: Boolean,
  val entries: List<AgentProgressEntry>,
)

/**
 * Agent 执行进度的进程内广播。
 *
 * DshStreamer 只在这里发布关键 step / 输出；无障碍服务订阅后把状态画到系统悬浮层。
 * 这里不落日志、不持久化，屏幕正文和聊天正文只在本进程内短暂经过。
 */
object AgentProgressCenter {
  private const val MAX_ENTRIES = 4
  private const val HIDE_DELAY_MS = 4_000L

  private val ids = AtomicLong()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _state = MutableStateFlow<AgentProgressState?>(null)
  private var hideJob: Job? = null

  val state: StateFlow<AgentProgressState?> = _state.asStateFlow()

  fun start(sessionId: String, prompt: String) {
    hideJob?.cancel()
    hideJob = null
    val task = summarizeText(prompt, 42).orEmpty()
    _state.value =
      AgentProgressState(
        sessionId = sessionId,
        task = task,
        running = true,
        entries = listOf(entry(AgentProgressKind.STEP, "任务已开始", task.ifBlank { null })),
      )
  }

  fun step(sessionId: String, title: String, detail: String? = null) {
    append(sessionId, entry(AgentProgressKind.STEP, title, detail))
  }

  fun toolStarted(sessionId: String, name: String, arguments: String) {
    val description = describeToolCall(name, arguments)
    append(sessionId, entry(AgentProgressKind.STEP, description.first, description.second))
  }

  fun toolFinished(sessionId: String, name: String?, ok: Boolean, result: String?) {
    val kind = if (ok) AgentProgressKind.OUTPUT else AgentProgressKind.ERROR
    append(sessionId, entry(kind, toolResultTitle(name, ok), summarizeToolResult(name, result)))
  }

  fun modelOutput(sessionId: String, text: String) {
    val summary = summarizeText(text, 110) ?: return
    append(sessionId, entry(AgentProgressKind.OUTPUT, "模型输出", summary))
  }

  fun complete(sessionId: String) {
    finish(sessionId, entry(AgentProgressKind.OUTPUT, "任务完成"))
  }

  fun fail(sessionId: String, message: String?) {
    finish(sessionId, entry(AgentProgressKind.ERROR, "任务中断", summarizeText(message.orEmpty(), 100) ?: "执行失败"))
  }

  fun dismiss() {
    hideJob?.cancel()
    hideJob = null
    _state.value = null
  }

  private fun append(sessionId: String, next: AgentProgressEntry) {
    _state.update { current ->
      if (current == null || current.sessionId != sessionId || !current.running) {
        current
      } else {
        current.copy(entries = (current.entries + next).takeLast(MAX_ENTRIES))
      }
    }
  }

  private fun finish(sessionId: String, next: AgentProgressEntry) {
    hideJob?.cancel()
    var finished: AgentProgressState? = null
    _state.update { current ->
      if (current == null || current.sessionId != sessionId || !current.running) {
        current
      } else {
        current.copy(running = false, entries = (current.entries + next).takeLast(MAX_ENTRIES)).also { finished = it }
      }
    }
    hideJob =
      scope.launch {
        delay(HIDE_DELAY_MS)
        finished?.let { snapshot -> if (_state.value == snapshot) _state.value = null }
      }
  }

  private fun entry(kind: AgentProgressKind, title: String, detail: String? = null) =
    AgentProgressEntry(id = ids.incrementAndGet(), kind = kind, title = title, detail = detail?.ifBlank { null })
}

/**
 * 悬浮层里那一行「正在做什么」：动作名 + 一句补充说明。
 *
 * 拆成两个函数是因为这个 `when` 已经长到撞 detekt 的圈复杂度阈值了 —— 名字与说明本来就是
 * 两件事（前者一望即知，后者要解析参数），分开写反而更清楚。
 */
internal fun describeToolCall(name: String, arguments: String): Pair<String, String?> =
  toolCallLabel(name) to toolCallDetail(name, parseArguments(arguments))

/** 动作名。认不出的工具如实显示原始名，不猜。 */
private fun toolCallLabel(name: String): String =
  when (name) {
    "read_screen" -> "读取当前屏幕"
    "tap_node" -> "点击控件"
    "tap_point" -> "按坐标点击"
    "capture_frame" -> "截取画面"
    "tap_target" -> "按图点击"
    "long_press_target" -> "按图长按"
    "long_press" -> "长按屏幕"
    "type_text" -> "输入文字"
    "press" -> "按下系统键"
    "launch_app" -> "启动应用"
    "swipe" -> "滑动屏幕"
    else -> "调用 $name"
  }

/** 补充说明（节点号 / 坐标 / 时长…）。没什么可说的就回 null。 */
private fun toolCallDetail(name: String, args: JsonObject?): String? =
  when (name) {
    "tap_node" -> args?.int("ref")?.let { "节点 #$it" }

    "tap_point" -> args?.point()

    "tap_target" -> args?.imageBox()

    "long_press", "long_press_target" ->
      listOfNotNull(
        args?.int("ref")?.let { "节点 #$it" },
        args?.imageBox(),
        args?.point(),
        args?.int("duration_ms")?.let { "${it}ms" },
      )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

    "type_text" -> args?.string("text")?.let { "${it.length} 个字符" }

    "press" -> args?.string("key")?.let(::pressLabel)

    "launch_app" -> summarizeText(args?.string("query").orEmpty(), 24)

    "swipe" -> args?.string("direction")?.let(::swipeLabel)

    else -> null
  }

internal fun toolResultTitle(name: String?, ok: Boolean): String {
  if (!ok) return "操作失败"
  return when (name) {
    "read_screen" -> "屏幕读取完成"
    "tap_node", "tap_point", "tap_target" -> "点击已完成"
    "long_press", "long_press_target" -> "长按已完成"
    "capture_frame" -> "画面已截取"
    "type_text" -> "文字已输入"
    "press" -> "按键已完成"
    "launch_app" -> "应用已启动"
    "swipe" -> "滑动已完成"
    else -> "操作已完成"
  }
}

internal fun summarizeToolResult(name: String?, text: String?): String? {
  val clean = text?.trim().orEmpty()
  if (clean.isEmpty()) return null
  return if (name == "read_screen") summarizeScreenResult(clean) else summarizeText(clean, 110)
}

internal fun summarizeScreenResult(text: String): String? {
  val header = Regex("""pkg=(\S+)\s+nodes=(\d+)""").find(text)
  val labels =
    Regex("\"([^\"\\n]{1,36})\"")
      .findAll(text)
      .map { it.groupValues[1].replace('\n', ' ').trim() }
      .filter { it.length >= 2 && it !in IGNORED_LABELS }
      .distinct()
      .take(3)
      .toList()

  val parts = mutableListOf<String>()
  labels.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" · ") }
  header?.groupValues?.getOrNull(2)?.let { parts += "$it 个控件" }
  header?.groupValues?.getOrNull(1)?.substringAfterLast('.')?.let { parts += it }
  return parts.joinToString(" · ").take(110).ifBlank { null }
}

internal fun summarizeText(text: String, maxChars: Int): String? {
  val line =
    text
      .replace("```", "")
      .lineSequence()
      .map { it.trim().trimStart('#', '-', '*', '>', ' ') }
      .map { it.replace(Regex("""\s+"""), " ") }
      .firstOrNull { it.isNotBlank() }
      ?: return null
  return line.take(maxChars).let { if (line.length > maxChars) "$it…" else it }
}

private fun parseArguments(arguments: String): JsonObject? =
  runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()

private fun JsonObject.string(key: String): String? =
  this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.int(key: String): Int? =
  this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull() }

private fun JsonObject.point(): String? {
  val x = int("x") ?: return null
  val y = int("y") ?: return null
  return "($x,$y)"
}

/** 视觉路径的 bbox（**图像像素**）→ 显示成中心点。四个数缺一个就当没有，不猜。 */
private fun JsonObject.imageBox(): String? {
  val x1 = int("x1") ?: return null
  val y1 = int("y1") ?: return null
  val x2 = int("x2") ?: return null
  val y2 = int("y2") ?: return null
  return "图像 (${(x1 + x2) / 2},${(y1 + y2) / 2})"
}

private fun pressLabel(key: String): String =
  when (key) {
    "back" -> "返回"
    "home" -> "主页"
    "recents" -> "最近任务"
    "enter" -> "回车"
    else -> key
  }

private fun swipeLabel(direction: String): String =
  when (direction) {
    "up" -> "向上"
    "down" -> "向下"
    "left" -> "向左"
    "right" -> "向右"
    else -> direction
  }

private val IGNORED_LABELS = setOf("clickable", "editable", "scrollable", "focused", "long_clickable")

package com.tappet.app.device

/**
 * 设备执行层的原子动作。App 无关：工具循环把 LLM 的 function call 映射到这里，
 * 具体实现（无障碍 / 将来的其它执行器）不参与语义判断。
 */
sealed interface DeviceAction {
  data object ReadScreen : DeviceAction

  /** 点最近一次 [ReadScreen] 快照里的节点，ref 对应渲染行首的 [n]。 */
  data class TapNode(val ref: Int) : DeviceAction

  data class TapPoint(val x: Int, val y: Int) : DeviceAction

  /** 长按最近一次 [ReadScreen] 快照里的节点。 */
  data class LongPressNode(val ref: Int, val durationMs: Long = DEFAULT_LONG_PRESS_MS) : DeviceAction

  data class LongPressPoint(val x: Int, val y: Int, val durationMs: Long = DEFAULT_LONG_PRESS_MS) :
    DeviceAction

  data class TypeText(val text: String) : DeviceAction

  data class Press(val key: PressKey) : DeviceAction

  /** 按应用名（模糊）或包名启动 App。 */
  data class LaunchApp(val query: String) : DeviceAction

  data class Swipe(val direction: SwipeDirection) : DeviceAction

  /**
   * 截当前屏幕。payload 不是给模型看的文本，而是传输用的 JSON
   * （mediaType / width / height / base64），由 dsh 侧存成附件再交给模型。
   */
  data object Screenshot : DeviceAction
}

enum class SwipeDirection {
  UP,
  DOWN,
  LEFT,
  RIGHT,
}

enum class PressKey {
  BACK,
  HOME,
  RECENTS,
  ENTER,
}

const val DEFAULT_LONG_PRESS_MS = 700L
const val MIN_LONG_PRESS_MS = 300L
const val MAX_LONG_PRESS_MS = 2000L

data class DeviceActionResult(
  val ok: Boolean,
  val summary: String,
  /** 给 LLM 看的结构化结果（如读屏文本）；日志只打 summary，不打 payload。 */
  val payload: String? = null,
)

data class NodeBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val centerX: Int get() = (left + right) / 2

  val centerY: Int get() = (top + bottom) / 2
}

data class ScreenNode(
  val ref: Int,
  val className: String,
  val text: String?,
  val contentDescription: String?,
  val bounds: NodeBounds,
  val depth: Int,
  val clickable: Boolean = false,
  val longClickable: Boolean = false,
  val editable: Boolean = false,
  val scrollable: Boolean = false,
  val focused: Boolean = false,
  val visible: Boolean = true,
) {
  val hasLabel: Boolean get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

  val interesting: Boolean
    get() = (hasLabel || clickable || editable || scrollable || focused) && visible
}

data class ScreenSnapshot(
  val packageName: String?,
  val nodes: List<ScreenNode>,
  val truncated: Boolean = false,
)

/**
 * 把快照渲染成给 LLM 的紧凑文本：
 *
 * ```
 * pkg=com.tencent.mobileqq nodes=45
 * [3] EditText "搜索" editable clickable @(490,2225)
 * [7] TextView "发送" clickable @(900,2200)
 * ```
 *
 * 纯函数，便于单测。页面正文会进 LLM（用户任务所需），但不落日志、不落盘。
 */
fun renderSnapshot(
  snapshot: ScreenSnapshot,
  maxTextChars: Int = 80,
  maxLines: Int = 240,
): String {
  val out = StringBuilder()
  out.append("pkg=").append(snapshot.packageName ?: "?").append(" nodes=").append(snapshot.nodes.size)
  if (snapshot.truncated) out.append('+')
  out.append('\n')

  var lines = 0
  for (node in snapshot.nodes) {
    if (!node.interesting) continue
    if (lines >= maxLines) {
      out.append("…（截断）\n")
      break
    }
    repeat(node.depth) { out.append("  ") }
    out.append('[').append(node.ref).append("] ")
    out.append(node.className.substringAfterLast('.'))
    val label =
      listOfNotNull(
        node.text?.takeIf { it.isNotBlank() },
        node.contentDescription?.takeIf { it.isNotBlank() },
      )
        .joinToString(" ")
        .replace('\n', ' ')
        .trim()
        .take(maxTextChars)
    if (label.isNotEmpty()) out.append(" \"").append(label).append('"')
    val flags =
      buildList {
        if (node.clickable) add("clickable")
        if (node.longClickable) add("long_clickable")
        if (node.editable) add("editable")
        if (node.scrollable) add("scrollable")
        if (node.focused) add("focused")
      }
    if (flags.isNotEmpty()) out.append(' ').append(flags.joinToString(","))
    out.append(" @(").append(node.bounds.centerX).append(',').append(node.bounds.centerY).append(')')
    out.append('\n')
    lines++
  }
  return out.toString().trimEnd()
}

/**
 * 控件树为空时的提示：告诉模型这是什么情况、下一步该做什么。
 *
 * 实测微信（8.0.78）只在「触摸探索」处于开启状态时启动的进程里才向无障碍暴露节点，
 * 且进程存活期间切换该状态不会恢复（最近任务划卡片、旋转、CLEAR_TASK 都试过，无效）；
 * App 内又没有强杀别的应用的手段（killBackgroundProcesses 对这种带前台服务的进程
 * 不起作用）。所以只能请用户彻底结束该 App 的进程后再来一遍。提示进 LLM，不落日志。
 */
const val EMPTY_TREE_HINT =
  "（当前界面几乎没有可读节点：该 App 对无障碍隐藏了控件树。它只在「触摸探索」开启后" +
    "启动的进程里才暴露节点，本进程启动得更早，无法自动恢复；App 内也没有强杀别的应用的" +
    "手段。如果任务只是要看清界面，可以改用 screenshot 从图上读；要点击/输入就别硬试，" +
    "停下来把原因和下面这一步告诉用户：在系统设置 → 应用管理里找到该 App、点「强行停止」" +
    "把它彻底结束，然后重新发起任务。）"

/** 判定「树基本为空」的阈值：正常界面至少有几十个节点。 */
const val EMPTY_TREE_NODE_THRESHOLD = 2

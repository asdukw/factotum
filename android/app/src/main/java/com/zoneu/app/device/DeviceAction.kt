package com.zoneu.app.device

import com.zoneu.app.schedule.ScheduleRule
import com.zoneu.app.schedule.ScheduleRulePatch

/**
 * 设备执行层的原子动作。App 无关：工具循环把 LLM 的 function call 映射到这里，
 * 具体实现（无障碍 / 将来的其它执行器）不参与语义判断。
 */
sealed interface DeviceAction {
  /**
   * 读当前屏幕。默认**增量**：与上一次读屏相同的部分不再重发（roadmap §1.2）。
   * [full] 强制全量，用于模型已经看不到上一次输出的情况（上下文被截断）。
   */
  data class ReadScreen(val full: Boolean = false) : DeviceAction

  /** 点最近一次 [ReadScreen] 快照里的节点，ref 对应渲染行首的 [n]。 */
  data class TapNode(val ref: Int) : DeviceAction

  /** 按屏幕像素点击。**模型可见的工具面里没有它**（那是视觉路径的坐标陷阱），桥与 debug 广播仍可用。 */
  data class TapPoint(val x: Int, val y: Int) : DeviceAction

  /**
   * 按最近一次 [Screenshot]（工具名 `capture_screenshot`）的**图像像素** bbox 点击 —— 视觉路径的主入口。
   *
   * 模型给的坐标是它实际看到的那张图的像素；屏幕像素由执行层用 [ScreenFrame.toScreen] 换算，
   * 模型不参与换算。树可读时优先用 [TapNode]，那条路不需要换算。
   *
   * （将来接专用 grounding 模型时，这里再加 source / confidence 字段 —— 现在加了也只是没人读。）
   */
  data class TapScreenshot(
    val screenshotId: String,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
  ) : DeviceAction

  /** 长按最近一次 [ReadScreen] 快照里的节点。 */
  data class LongPressNode(val ref: Int, val durationMs: Long = DEFAULT_LONG_PRESS_MS) : DeviceAction

  data class LongPressPoint(val x: Int, val y: Int, val durationMs: Long = DEFAULT_LONG_PRESS_MS) :
    DeviceAction

  /** [TapScreenshot] 的长按版本，坐标同样是最近一次截图的图像像素。 */
  data class LongPressScreenshot(
    val screenshotId: String,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long = DEFAULT_LONG_PRESS_MS,
  ) : DeviceAction

  data class TypeText(val text: String) : DeviceAction

  data class Press(val key: PressKey) : DeviceAction

  /** 按应用名（模糊）或包名启动 App。 */
  data class LaunchApp(val query: String) : DeviceAction

  data class Swipe(val direction: SwipeDirection) : DeviceAction

  /**
   * 截当前屏幕并产出一**帧**（工具名 `capture_screenshot`）。payload 不是给模型看的文本，而是传输用的
   * JSON（mediaType / width / height / screenshotId / screenWidth / screenHeight / offset / base64）：
   * 图由 dsh 侧存成附件交给模型，帧字段由插件渲染成模型可见的图例（见 [ScreenFrame]）。
   */
  data object Screenshot : DeviceAction

  /**
   * 设置闹钟。**走系统 intent**（`AlarmClock.ACTION_SET_ALARM`）—— 不需要任何权限，
   * 也**不需要无障碍**：静默设置（`EXTRA_SKIP_UI=true`），不弹确认界面。
   *
   * 为什么不用 AlarmManager 直接建：那只是「几小时后响一次」的定时器，不是用户在时钟 App 里
   * 看得见、能改、能关的闹钟。用户要的是后者。
   */
  data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : DeviceAction

  /**
   * 在手机上的 Linux（内嵌 proot + Debian）里执行一条命令。
   *
   * **不是 UI 动作**：不过无障碍、不上屏、也不交给别的 App —— [com.zoneu.app.proot.ProotRuntime]
   * 在本进程域里起一次 proot。每条命令一个独立会话：cwd / 环境变量 / 后台进程都不保留。
   * 它改的是文件系统，没有撤销。
   */
  data class RunCommand(val cmd: String, val timeoutMs: Long) : DeviceAction

  /**
   * 新建日程。**直接写 `CalendarContract`**（`ContentResolver.insert`）—— 不开界面、不用点保存。
   *
   * 代价是 `READ_CALENDAR` + `WRITE_CALENDAR` 两个运行时权限（READ 只为查可写日历的 id）。
   * 授权入口在聊天里的提示条（`ChatScreen`），没有权限时动作回 [CODE_CALENDAR_PERMISSION]。
   */
  data class AddCalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String?,
    /** 提前多少分钟提醒；null = 不设提醒。 */
    val reminderMinutes: Int? = null,
  ) : DeviceAction

  companion object {
    /** 没有日历权限：UI 据此弹授权提示，模型据此知道该让用户授权而不是重试。 */
    const val CODE_CALENDAR_PERMISSION = "CALENDAR_PERMISSION_REQUIRED"

    /** 设备上没有可写的日历（没有登录账号 / 全是只读日历）。 */
    const val CODE_NO_WRITABLE_CALENDAR = "NO_WRITABLE_CALENDAR"

    /** 内嵌 Linux（proot + rootfs）没起来：重试同一条命令不会成功。 */
    const val CODE_SHELL_UNAVAILABLE = "SHELL_UNAVAILABLE"

    /** 日程 id 不存在（已被删 / 记错了）。让模型重新 list，不要按标题猜。 */
    const val CODE_EVENT_NOT_FOUND = "EVENT_NOT_FOUND"

    /** 定时任务 id 不存在。同样先 list 再操作。 */
    const val CODE_SCHEDULE_NOT_FOUND = "SCHEDULE_NOT_FOUND"

    /** 时间不在合法钟点上（hour 0-23 / minute 0-59）。 */
    const val CODE_SCHEDULE_INVALID_TIME = "SCHEDULE_INVALID_TIME"
  }

  /**
   * 列出时间窗内的日程。**这是改 / 删日程的前置**：模型要拿到真实的 event id，
   * 而不是靠标题猜、更不是跑到日历界面里翻。
   */
  data class ListCalendarEvents(val beginMillis: Long, val endMillis: Long) : DeviceAction

  /**
   * 修改日程。字段为 null 表示「不改这一项」—— 全量替换会把模型没提到的字段抹掉。
   */
  data class UpdateCalendarEvent(
    val eventId: Long,
    val title: String? = null,
    val beginMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String? = null,
    val description: String? = null,
    val reminderMinutes: Int? = null,
  ) : DeviceAction

  /** 删除日程。破坏性操作，必须经审批闸门。 */
  data class DeleteCalendarEvent(val eventId: Long) : DeviceAction

  /**
   * 语义化创建定时任务：按 [rule] 在 [hour]:[minute] 让 agent 跑 [prompt]。
   *
   * 模型把「每天早上八点提醒我看天气」拆成时间与指令，这里只负责落库与排闹钟。
   * 支持每日、每周、每月和指定日期单次（见 `docs/scheduled-tasks.md`）。
   */
  data class CreateScheduledTask(
    val title: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val rule: ScheduleRule = ScheduleRule(),
  ) : DeviceAction

  /** 列出定时任务。id 是改 / 删的前置 —— 不让模型按标题猜。 */
  data class ListScheduledTasks(val includeDisabled: Boolean = true) : DeviceAction

  /** 修改定时任务；null = 这一项不改。 */
  data class UpdateScheduledTask(
    val id: String,
    val title: String? = null,
    val prompt: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val enabled: Boolean? = null,
    val rule: ScheduleRulePatch = ScheduleRulePatch(),
  ) : DeviceAction

  /** 删除定时任务。破坏性操作，必须经审批闸门。 */
  data class DeleteScheduledTask(val id: String) : DeviceAction
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
  /**
   * 机器可读的失败原因（如 [AccessibilityStatus.CODE_NOT_ENABLED]），成功时为 null。
   *
   * summary 是给人 / 给模型看的中文，**不要拿它当判断依据** —— 文案会改，码不会。
   * 桥会把它一并放进返回的 JSON 里。
   */
  val code: String? = null,
  /**
   * 动作下发后界面**看起来**有没有变化（视觉路径的验证闭环）。
   *
   * 三态：true 变了 / false 没观察到变化 / null 验不了（截图被节流或窗口禁截）。
   * 它是**启发式**：`false` 只能说「似乎没变」，不能说「点击失败」—— 小面积变化（勾选框、
   * 行高亮）在 8×8 指纹上看不出来。所以它只进 summary 与这个字段，**不影响 [ok]**。
   */
  val changed: Boolean? = null,
)

/**
 * 视觉路径的机器可读失败原因。
 *
 * 与 [DeviceActionResult.summary] 分开的理由和 [AccessibilityStatus.CODE_NOT_ENABLED] 一样：
 * 文案会改，码不会 —— 插件、界面与审计都按码判断。
 */
object VisionCode {
  /** 帧 id 与当前帧不符：界面已经变了，旧坐标作废，必须重新取帧。 */
  const val STALE_SCREENSHOT = "STALE_SCREENSHOT"

  /** 还没取过帧（或服务重启过），没有坐标系可用。 */
  const val FRAME_UNKNOWN = "FRAME_UNKNOWN"

  /** bbox 越出图像范围。直接拒、不 clamp —— clamp 会让模型以为点对了。 */
  const val OUT_OF_SCREENSHOT = "OUT_OF_SCREENSHOT"

  /** 手势下发成功但界面看起来没变。这是**提示**不是失败，`ok` 仍为 true。 */
  const val NO_VISIBLE_CHANGE = "NO_VISIBLE_CHANGE"
}

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
    out.append(renderNodeLine(node, maxTextChars)).append('\n')
    lines++
  }
  return out.toString().trimEnd()
}

/**
 * 单个节点的渲染行：缩进 + `[ref]` + 类名 + 标签 + 状态 + 中心点。
 *
 * 全量、增量、targets 三段共用同一个函数 —— 行格式只有一处定义，
 * 模型看到的格式才不会因为走了哪条渲染路径而变。
 */
fun renderNodeLine(node: ScreenNode, maxTextChars: Int = 80): String {
  val out = StringBuilder()
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
  return out.toString()
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
  "（当前界面几乎没有可读节点：该 App 对无障碍隐藏了控件树，且这个状态在进程存活期间不会" +
    "恢复，App 内也没有强杀别的应用的手段。改用视觉路径：capture_screenshot 取一帧（图 + 帧信息），" +
    "从图上找到目标后用 tap_target 给出**该帧的图像像素** bbox 点击，动作后再取一帧确认；" +
    "tap_node 这类按控件编号的工具在这里没有节点可用。**输入文字可以**：先 tap_target 点一下" +
    "输入框让它拿到焦点，再调 type_text（发送/搜索用 press enter）—— 这条路走无障碍的文本注入" +
    "通道，与控件树可不可读无关；没点中输入框时它会回 text_no_channel。）"

/**
 * 判定「树基本为空」：**没有任何一个**节点是可见且有内容 / 可交互的。
 *
 * 不用节点总数判定 —— 有的 App 只暴露一层没有标签的容器：节点数不少、可读的行为零。
 * 按总数判会把这种「假阴性」漏掉，模型于是以为树是可用的，一直读到空行却不知道
 * 该换视觉路径。正常界面至少有一个带文字或可点的节点。
 */
fun ScreenSnapshot.hasNoReadableNode(): Boolean = nodes.none { it.interesting }

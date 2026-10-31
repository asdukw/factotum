package com.zoneu.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.zoneu.app.device.DeviceAction
import com.zoneu.app.device.DeviceActionResult
import com.zoneu.app.device.DeviceBridge
import com.zoneu.app.device.EMPTY_TREE_HINT
import com.zoneu.app.device.NodeBounds
import com.zoneu.app.device.PageMemoryStore
import com.zoneu.app.device.PressKey
import com.zoneu.app.device.ScreenFrame
import com.zoneu.app.device.ScreenNode
import com.zoneu.app.device.ScreenSnapshot
import com.zoneu.app.device.SwipeDirection
import com.zoneu.app.device.VisionCode
import com.zoneu.app.device.contentHash
import com.zoneu.app.device.diffSnapshots
import com.zoneu.app.device.hasNoReadableNode
import com.zoneu.app.device.isChurnHeavy
import com.zoneu.app.device.renderDiff
import com.zoneu.app.device.renderPageHint
import com.zoneu.app.device.renderSnapshot
import com.zoneu.app.device.renderUnchanged
import com.zoneu.app.device.screenFingerprint
import com.zoneu.app.device.textInputFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * `DeviceBridge` 的 Android 无障碍实现（AOSP API，不绑 ROM）。
 *
 * 探针期只实现「四问」对应的四个能力，不采集、不常驻处理事件；所有探针都必须由
 * 用户显式触发（探针页按钮 + 倒计时切到目标 App），没有后台自动执行。
 * 隐私红线：只记计数 / 类型 / 包名 / 坐标 / 错误码，绝不落页面正文。
 */
class ZoneuAccessibilityService : AccessibilityService(), DeviceBridge {
  private var agentOverlay: AgentOverlayController? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    agentOverlay?.stop()
    agentOverlay =
      AgentOverlayController(this).also {
        it.start()
        // 初始判据：读不到前台包名时**按「在 zoneu 里」处理**（= 不显示）。
        // 宁可晚一点出现，也不要在应用内闪一下。
        it.setForeground(rootInActiveWindow?.packageName?.toString().let { pkg -> pkg == null || pkg == packageName })
      }
    instance = this
    // 默认不带触摸探索标志：带着会让用户的手动点击变成「探索 + 双击」。
    setTouchExplorationForTask(false)
    Log.i(TAG, "service connected (touchExploration=$touchExplorationActive)")
  }

  /**
   * 只为一件事订阅事件：**知道用户什么时候离开了 Zoneu**。
   *
   * 悬浮球只在任务跑着、而用户不在 zoneu 里时出现（在聊天页弹一个「执行中」的气泡
   * 没有意义，它挡的正是用户正在看的内容）。这里只看窗口切换，不读任何页面内容 ——
   * 与控制台那套「只记类型 / 包名」的隐私规矩一致。
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
    val pkg = event.packageName?.toString() ?: return
    agentOverlay?.setForeground(pkg == packageName)
  }

  /** 设置页关掉 / 打开悬浮球时调用，让**正在跑**的服务立刻生效。 */
  fun applyOverlayEnabled(enabled: Boolean) {
    agentOverlay?.setEnabled(enabled)
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: Intent?): Boolean {
    agentOverlay?.stop()
    agentOverlay = null
    if (instance === this) {
      instance = null
    }
    Log.i(TAG, "service unbound")
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    agentOverlay?.stop()
    agentOverlay = null
    // ColorOS 会周期性 unbind/rebind：旧实例的 destroy 可能晚于新实例的 connect，
    // 只有自己仍是当前实例时才允许把状态置为断开。
    if (instance === this) {
      instance = null
    }
    Log.i(TAG, "service destroyed")
    super.onDestroy()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    agentOverlay?.onConfigurationChanged()
  }

  // ---------- 执行层：原子动作 ----------

  @Volatile private var lastSnapshot: ScreenSnapshot? = null

  @Volatile private var currentPage: String? = null
  private val pageMemory by lazy { PageMemoryStore(this) }

  /**
   * 清掉跨任务的读屏状态：下一个任务的第一次读屏必须是全量。
   *
   * `lastSnapshot` 只对「同一个任务里的连续操作」有意义。跨任务沿用会让新任务的第一次
   * 读屏回一句 `unchanged` —— 而模型在新会话里根本看不到上一次的输出（实测 scroll 任务
   * 就是这样卡住的）。debug 广播在 TASK 前调用；`press home` 成功后也清一次。
   */
  fun resetScreenState() {
    lastSnapshot = null
    lastNodeHandles = emptyMap()
    currentPage = null
  }

  override suspend fun execute(action: DeviceAction): DeviceActionResult {
    // 执行前先记「触摸探索实际是否生效」——「点了没反应」是不是预期行为全靠它区分
    observeExploration()
    val result = dispatch(action)
    // 页面记忆只记「哪个页面、做了什么类型、落在哪」，不记任何正文（见 PageMemoryStore）。
    if (result.ok && action !is DeviceAction.ReadScreen) rememberAction(action)
    // 回桌面意味着一个任务告一段落：别让下一个任务继承上一屏的快照。
    if (result.ok && action is DeviceAction.Press && action.key == PressKey.HOME) resetScreenState()
    return result
  }

  private suspend fun dispatch(action: DeviceAction): DeviceActionResult =
    when (action) {
      // 这些动作**不经过无障碍**（桥直接交给 SystemIntents）。
      // 正常路径走不到这里；留一条明确的失败，比让 when 编译不过或静默落到别处好。
      is DeviceAction.SetAlarm,
      is DeviceAction.AddCalendarEvent,
      is DeviceAction.ListCalendarEvents,
      is DeviceAction.UpdateCalendarEvent,
      is DeviceAction.DeleteCalendarEvent,
      is DeviceAction.CreateScheduledTask,
      is DeviceAction.ListScheduledTasks,
      is DeviceAction.UpdateScheduledTask,
      is DeviceAction.DeleteScheduledTask,
      is DeviceAction.RunCommand,
      is DeviceAction.CreateCharacterCard,
      is DeviceAction.ImportCharacterCard,
      is DeviceAction.CancelCharacterCard,
      is DeviceAction.ListCharacterCards,
      is DeviceAction.ReadCharacterCard,
      is DeviceAction.PreviewCharacterCardUpdate,
      is DeviceAction.UpdateCharacterCard,
      is DeviceAction.WorldBookOperation,
      is DeviceAction.WorldRuntimeAction,
      ->
        DeviceActionResult(ok = false, summary = "这个动作由桥直接处理，不经过无障碍", code = "NOT_HANDLED_HERE")

      is DeviceAction.ReadScreen -> executeReadScreen(action.full)

      is DeviceAction.TapNode -> executeTapNode(action.ref)

      is DeviceAction.TapPoint -> executeTapPoint(action.x, action.y)

      is DeviceAction.TapScreenshot -> executeTapScreenshot(action)

      is DeviceAction.LongPressNode -> executeLongPressNode(action.ref, action.durationMs)

      is DeviceAction.LongPressPoint -> executeLongPressPoint(action.x, action.y, action.durationMs)

      is DeviceAction.LongPressScreenshot -> executeLongPressScreenshot(action)

      is DeviceAction.TypeText -> executeTypeText(action.text)

      is DeviceAction.Press -> executePress(action.key)

      is DeviceAction.LaunchApp -> executeLaunchApp(action.query)

      is DeviceAction.Swipe -> executeSwipe(action.direction)

      DeviceAction.Screenshot -> executeScreenshot()
    }

  /**
   * 增量读屏：默认只回与上一次读屏的差异；[full] 强制全量。
   *
   * 三件事必须一起做，缺一个就会让模型点到旧位置：
   * 1. 稳定 ref —— 未变节点沿用上一次编号，新节点往后接；
   * 2. 句柄按 ref 重排 —— 触摸探索下的语义点击靠它；
   * 3. 变化过大或换了 App 时退回全量 —— 弱匹配在整页替换时不可靠。
   */
  private suspend fun executeReadScreen(full: Boolean): DeviceActionResult =
    withContext(Dispatchers.Default) {
      val root = applicationRoot() ?: return@withContext DeviceActionResult(false, "rootInActiveWindow = null")
      val collected = collectScreen(root)
      val pkg = root.packageName?.toString()
      val fresh = ScreenSnapshot(pkg, collected.nodes, collected.truncated)
      val fingerprint = screenFingerprint(fresh)
      currentPage = fingerprint
      val visit = pageMemory.visit(fingerprint)

      val previous = lastSnapshot
      // 变化过大时也当没有增量：逐行 diff 在整页替换时既难读、弱匹配也不可靠。
      val delta =
        previous
          ?.takeIf { canDiff(it, fresh, full) }
          ?.let { diffSnapshots(it, fresh) }
          ?.takeUnless { it.isChurnHeavy() }
      val snapshot = delta?.let { ScreenSnapshot(pkg, it.nodes, collected.truncated) } ?: fresh
      val body =
        when {
          delta == null -> renderSnapshot(snapshot)
          delta.churn == 0 -> renderUnchanged(snapshot)
          else -> renderDiff(snapshot, delta)
        }
      val handlesByRef = snapshot.nodes.mapIndexed { i, node -> node.ref to collected.handles[i] }.toMap()

      lastSnapshot = snapshot
      lastNodeHandles = handlesByRef

      val labeled = snapshot.nodes.count { it.hasLabel }
      val editable = snapshot.nodes.count { it.editable }
      val change = delta?.let { "+${it.added.size} -${it.removed.size} ~${it.changed.size}" } ?: "全量"
      DeviceActionResult(
        ok = true,
        summary = "读到 ${snapshot.nodes.size} 个节点（$change；有文字 $labeled，可编辑 $editable）",
        payload =
          buildString {
            append(body)
            if (snapshot.hasNoReadableNode()) append('\n').append(EMPTY_TREE_HINT)
            append('\n').append(renderPageHint(fingerprint, visit))
          },
      )
    }

  /** 一次前序遍历：节点、与之同序的活句柄、是否因为上限被截断。 */
  private fun collectScreen(root: AccessibilityNodeInfo): CollectedScreen {
    val nodes = mutableListOf<ScreenNode>()
    val handles = mutableListOf<AccessibilityNodeInfo>()
    var truncated = false
    val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
      if (nodes.size >= MAX_SCREEN_NODES) {
        truncated = true
        break
      }
      val (node, depth) = stack.removeLast()
      nodes += node.toScreenNode(nodes.size, depth)
      handles += node
      for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
    }
    return CollectedScreen(nodes, handles, truncated)
  }

  private fun AccessibilityNodeInfo.toScreenNode(ref: Int, depth: Int): ScreenNode {
    val rect = Rect().also { getBoundsInScreen(it) }
    return ScreenNode(
      ref = ref,
      className = className?.toString().orEmpty(),
      text = text?.toString(),
      contentDescription = contentDescription?.toString(),
      bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
      depth = depth,
      clickable = isClickable,
      longClickable = isLongClickable,
      editable = isEditable,
      scrollable = isScrollable,
      focused = isFocused,
      visible = isVisibleToUser,
    )
  }

  /** 只有「同一 App、两次都没截断、且不是强制全量」时才敢做增量。 */
  private fun canDiff(previous: ScreenSnapshot, fresh: ScreenSnapshot, full: Boolean): Boolean =
    !full &&
      !previous.truncated &&
      !fresh.truncated &&
      previous.packageName == fresh.packageName &&
      fresh.nodes.isNotEmpty()

  private class CollectedScreen(
    val nodes: List<ScreenNode>,
    val handles: List<AccessibilityNodeInfo>,
    val truncated: Boolean,
  )

  /**
   * 把刚成功的动作记到**动作发生时所在页面**上。只保留类型与坐标：
   * 输入的文字、点到的控件文字都不进来 —— 与 PageMemoryStore 的隐私边界一致。
   */
  private fun rememberAction(action: DeviceAction) {
    val page = currentPage ?: return
    val descriptor =
      when (action) {
        is DeviceAction.TapNode -> nodeAction("tap", action.ref)
        is DeviceAction.LongPressNode -> nodeAction("long", action.ref)
        is DeviceAction.TapPoint -> "tap@(${action.x},${action.y})"
        is DeviceAction.LongPressPoint -> "long@(${action.x},${action.y})"
        is DeviceAction.TypeText -> "type"
        is DeviceAction.Press -> "press ${action.key.name.lowercase()}"
        is DeviceAction.Swipe -> "swipe ${action.direction.name.lowercase()}"
        else -> null
      }
    if (descriptor != null) pageMemory.recordAction(page, descriptor)
  }

  /** 节点中心点；节点已经不在快照里时退回类型名（仍然不含正文）。 */
  private fun nodeAction(prefix: String, ref: Int): String =
    lastSnapshot?.nodes
      ?.firstOrNull { it.ref == ref }
      ?.bounds
      ?.let { "$prefix@(${it.centerX},${it.centerY})" }
      ?: prefix

  private suspend fun executeTapNode(ref: Int): DeviceActionResult {
    val node =
      lastSnapshot?.nodes?.firstOrNull { it.ref == ref }
        ?: return DeviceActionResult(false, "ref=$ref 不存在：先 read_ui_tree 再按行首编号点")
    if (touchExplorationActive) return semanticClick(ref, long = false)
    return tapPixel(node.bounds.centerX, node.bounds.centerY)
  }

  /** 桥与 debug 广播用的屏幕像素入口。**模型可见的工具面里没有它**（见 [DeviceAction.TapPoint]）。 */
  private suspend fun executeTapPoint(x: Int, y: Int): DeviceActionResult = tapPixel(x, y)

  /**
   * 屏幕像素点击的**唯一**落地点。
   *
   * 换算只在它的上游发生一次：控件树路径传 `getBoundsInScreen` 的中心（本来就是屏幕像素），
   * 视觉路径传 [ScreenFrame.toScreen] 换算后的结果。两个入口共用一个函数，
   * 免得「哪条路用哪个坐标系」变成要靠人记住的事。
   */
  private suspend fun tapPixel(x: Int, y: Int): DeviceActionResult {
    if (touchExplorationActive) {
      // 触摸探索下注入手势会被系统转成探索事件吞掉，只能退化成「点中坐标的语义点击」。
      val hit = nodeAt(x, y, long = false)
      if (hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        delay(ACTION_SETTLE_DELAY_MS)
        return DeviceActionResult(true, "已语义点击 @($x,$y) 命中的节点")
      }
      return DeviceActionResult(false, "触摸探索开启：@($x,$y) 没有可语义点击的节点")
    }
    val path =
      Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(x + 1f, y.toFloat())
      }
    return dispatchPath(path, TAP_DURATION_MS, "点击 @($x,$y)")
  }

  private suspend fun executeLongPressNode(ref: Int, durationMs: Long): DeviceActionResult {
    val node =
      lastSnapshot?.nodes?.firstOrNull { it.ref == ref }
        ?: return DeviceActionResult(false, "ref=$ref 不存在：先 read_ui_tree 再按行首编号长按")
    if (touchExplorationActive) return semanticClick(ref, long = true)
    return longPressPixel(node.bounds.centerX, node.bounds.centerY, durationMs)
  }

  /** 桥与 debug 广播用的屏幕像素入口（同 [executeTapPoint]）。 */
  private suspend fun executeLongPressPoint(x: Int, y: Int, durationMs: Long): DeviceActionResult =
    longPressPixel(x, y, durationMs)

  /** 屏幕像素长按的唯一落地点，与 [tapPixel] 对称。 */
  private suspend fun longPressPixel(x: Int, y: Int, durationMs: Long): DeviceActionResult {
    if (touchExplorationActive) {
      val hit = nodeAt(x, y, long = true)
      if (hit != null && hit.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
        delay(ACTION_SETTLE_DELAY_MS)
        return DeviceActionResult(true, "已语义长按 @($x,$y) 命中的节点")
      }
      return DeviceActionResult(false, "触摸探索开启：@($x,$y) 没有可语义长按的节点")
    }
    val path =
      Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(x + 1f, y.toFloat())
      }
    return dispatchPath(path, durationMs, "长按 @($x,$y)")
  }

  // ---------- 视觉路径：按帧坐标操作 ----------

  private suspend fun executeTapScreenshot(action: DeviceAction.TapScreenshot): DeviceActionResult =
    actOnFrame(action.screenshotId, action.x1, action.y1, action.x2, action.y2, long = false, durationMs = 0L)

  private suspend fun executeLongPressScreenshot(action: DeviceAction.LongPressScreenshot): DeviceActionResult =
    actOnFrame(
      action.screenshotId,
      action.x1,
      action.y1,
      action.x2,
      action.y2,
      long = true,
      durationMs = action.durationMs,
    )

  /**
   * 视觉路径的落地点：把**图像像素 bbox** 换算成屏幕像素下发，然后验证界面变没变。
   *
   * 三道校验都回**机器可读的码**（模型、界面、审计都不去匹配中文）：没取过帧 / 帧作废 / bbox 越界。
   * 越界直接拒、不 clamp —— clamp 会把「模型给错了坐标」变成「点了个别的地方」，更难查。
   */
  private suspend fun actOnFrame(
    screenshotId: String,
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    long: Boolean,
    durationMs: Long,
  ): DeviceActionResult {
    val frame = currentFrame
      ?: return DeviceActionResult(false, "还没有取过帧：先 capture_screenshot", code = VisionCode.FRAME_UNKNOWN)
    if (frame.id != screenshotId || frameStale) {
      val why = if (frameStale) "，界面已变化" else ""
      return DeviceActionResult(
        false,
        "帧 $screenshotId 已作废（当前 ${frame.id}$why）：重新 capture_screenshot 再给坐标",
        code = VisionCode.STALE_SCREENSHOT,
      )
    }
    if (!frame.containsBox(x1, y1, x2, y2)) {
      return DeviceActionResult(
        false,
        "bbox ($x1,$y1)-($x2,$y2) 越出图像 ${frame.imageWidth}×${frame.imageHeight}",
        code = VisionCode.OUT_OF_SCREENSHOT,
      )
    }
    val centerX = (x1 + x2) / 2
    val centerY = (y1 + y2) / 2
    val (screenX, screenY) = frame.toScreen(centerX, centerY)
    val baseline = currentFrameHash
    val gesture = if (long) longPressPixel(screenX, screenY, durationMs) else tapPixel(screenX, screenY)
    if (!gesture.ok) return gesture
    val changed = verifyChanged(baseline)
    // 界面变了 ⇒ 这一帧的坐标作废：模型必须重新取帧，不能拿着旧坐标接着点。
    if (changed == true) frameStale = true
    val verdict =
      when (changed) {
        true -> "界面已变化"
        false -> "界面似乎没有变化（可能没点中）"
        null -> "无法验证界面是否变化"
      }
    return DeviceActionResult(
      ok = true,
      summary = "${gesture.summary}；图像 ($centerX,$centerY) → 屏幕 ($screenX,$screenY)；$verdict",
      code = if (changed == false) VisionCode.NO_VISIBLE_CHANGE else null,
      changed = changed,
    )
  }

  /**
   * 输入文本：**控件树优先，无障碍文本注入通道兜底**（见 [TextChannel]）。
   *
   * 顺序不能反 —— 控件树那条路自己会 `ACTION_FOCUS`，不需要输入框先有焦点；而通道必须有焦点
   * 才有连接。先试能自己搞定焦点的那条，成功率更高；两条都试过才算失败。
   *
   * 树不可读的 App（微信一类）以前到这里就结束了（「没有可见输入框」），
   * 视觉路径于是**能点不能打字**。现在这条路补上了（见 `docs/android-accessibility.md` §4）。
   */
  private suspend fun executeTypeText(text: String): DeviceActionResult = withContext(Dispatchers.Default) {
    val target = applicationRoot()?.let { findEditable(it) }
    if (target != null) {
      if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArguments(text))
      if (accepted) {
        // Compose / 自绘输入框异步刷新，等一拍再读回
        delay(TEXT_VERIFY_DELAY_MS)
        target.refresh()
        val readBack = target.text?.toString()
        audit("text_input", "via" to "tree", "chars" to text.length, "readback" to (readBack == text))
        return@withContext if (readBack == text) {
          DeviceActionResult(true, "已输入 ${text.length} 字符（读回一致）")
        } else {
          DeviceActionResult(true, "已尝试输入 ${text.length} 字符（App 未回读一致，实际可能已生效）")
        }
      }
    }
    if (TextChannel.commit(text)) {
      // 这条通道只写不读（不碰 getSurroundingText），所以没有读回验证 —— 别在文案里假装有
      audit("text_input", "via" to "ime_channel", "chars" to text.length)
      return@withContext DeviceActionResult(true, "已输入 ${text.length} 字符（经无障碍文本注入通道，未读回验证）")
    }
    val failure =
      textInputFailure(
        hadEditableNode = target != null,
        channelSupported = TextChannel.supported(),
        flagActive = TextChannel.flagActive(),
        channelReady = TextChannel.isReady(),
      )
    audit("text_input", "via" to "none", "code" to failure.code)
    DeviceActionResult(false, failure.summary, code = failure.code)
  }

  private fun setTextArguments(text: String): Bundle =
    Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }

  /**
   * 截屏 → 缩到最长边 [MAX_SCREENSHOT_EDGE] → PNG（超预算转 JPEG）→ base64，并产出**一帧**。
   *
   * 缩图与转码都在设备侧做：请求图像预算按 base64 长度算（pi-ai 默认 20MiB，
   * 且每轮请求都会重发历史图），原图直传会把预算吃穿。
   *
   * 帧是感知与执行之间的坐标系（见 [ScreenFrame]）：**图像尺寸**给模型看图，**屏幕尺寸**给
   * 执行层换算。屏幕尺寸取缩放**前**的捕获位图，不用 displayMetrics —— 分屏与系统栏会让后者对不上。
   */
  private suspend fun executeScreenshot(): DeviceActionResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return DeviceActionResult(false, "截图需要 Android 11+")
    }
    val captured = captureBitmap() ?: return DeviceActionResult(false, "截图失败（窗口可能禁止截屏）")
    val screenWidth = captured.width
    val screenHeight = captured.height
    // 指纹取**缩放前**的位图：验证时也走同一条路（全分辨率 → 8×8）。
    // 两边处理方式不同的话（一边先缩 2048 再缩 8），同一屏也会算出不同的指纹，自己造出「变化」。
    val hash = aHash(captured)
    auditFrameSizeMismatch(screenWidth, screenHeight)
    val scaled = scaleToLongEdge(captured, MAX_SCREENSHOT_EDGE)
    val bytes: ByteArray
    val mediaType: String
    val width: Int
    val height: Int
    try {
      width = scaled.width
      height = scaled.height
      val encoded = encodeScreenshot(scaled)
      bytes = encoded.first
      mediaType = encoded.second
    } finally {
      if (scaled !== captured) captured.recycle()
      scaled.recycle()
    }
    val frame =
      ScreenFrame(
        id = "f${++frameSeq}",
        imageWidth = width,
        imageHeight = height,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
      )
    currentFrame = frame
    currentFrameHash = hash
    frameStale = false
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    // 手拼 JSON：字段都是受控值（固定字面量 / 整数 / base64 字母表），
    // 不值得为它给服务层引入序列化依赖。
    val payload =
      "{\"mediaType\":\"$mediaType\",\"width\":$width,\"height\":$height," +
        "\"screenshotId\":\"${frame.id}\",\"screenWidth\":$screenWidth,\"screenHeight\":$screenHeight," +
        "\"offsetX\":0,\"offsetY\":0,\"base64\":\"$base64\"}"
    return DeviceActionResult(
      ok = true,
      summary = "${frame.legend()}｜${mediaType.substringAfter('/')} ${bytes.size / 1024}KB",
      payload = payload,
    )
  }

  // ---------- 视觉路径：帧与验证 ----------

  /** 当前帧。**只有一帧**：坐标只对最近一次截图有效，界面变了旧坐标就作废。 */
  private var currentFrame: ScreenFrame? = null

  /** 当前帧的 8×8 指纹，动作后拿它比「界面变了没有」。 */
  private var currentFrameHash: Long? = null

  /** 动作后观察到界面变化 ⇒ 当前帧作废（旧坐标不再可信）。 */
  private var frameStale = false

  /** 帧序号，单调递增（`f1`、`f2`…）。 */
  private var frameSeq = 0

  /** 截图串行化：动作后的验证截图与前一次截图背靠背会撞平台的频率限制。 */
  private val captureLock = Mutex()

  /** 上一次截图失败的原始错误码，用来判断「值不值得退避重试」。 */
  private var lastCaptureErrorCode: Int? = null

  /**
   * 截图，撞上最小间隔限制就退避重试一次。
   *
   * `takeScreenshot` 有频率限制（`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`），而视觉路径是
   * 「动作 → 立刻截图验证」的节奏，必然会撞上。串行化 + 退避一次；再失败就把 null 交给调用方 ——
   * 验证环节会如实回「无法验证」，不会因此把动作判成失败。
   */
  private suspend fun captureBitmap(): Bitmap? {
    // 截图要 Android 11+（`AccessibilityService.takeScreenshot`）。低版本直接回 null：
    // 调用方一律按「截不到 / 验不了」处理，不会因此把动作判成失败。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return captureLock.withLock {
      lastCaptureErrorCode = null
      captureScreen()?.let { return@withLock it }
      if (lastCaptureErrorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return@withLock null
      delay(SCREENSHOT_RETRY_DELAY_MS)
      lastCaptureErrorCode = null
      captureScreen()
    }
  }

  /**
   * 动作之后界面变了没有：再截一张、比 8×8 指纹。**只回布尔，不回图** ——
   * 图会进会话历史并被每轮重发，把请求图像预算吃穿（见 docs/dsh-android.md P10）。
   *
   * null = 验不了（截图被限流或窗口禁截）。**不要把 null 当 false**：前者是「不知道」，
   * 后者是「看着没变」。
   */
  private suspend fun verifyChanged(baseline: Long?): Boolean? {
    if (baseline == null) return null
    delay(ACTION_SETTLE_DELAY_MS)
    val after = captureBitmap() ?: return null
    val hash =
      try {
        aHash(after)
      } finally {
        after.recycle()
      }
    return hash != baseline
  }

  /**
   * 8×8 均值哈希 —— 回答「界面变了没有」，**不是**精确比对。
   *
   * 缩到 8×8 灰度、按均值取 64 位。整屏级变化（翻页、弹窗、换界面）它看得见；
   * 小面积变化（勾选框、行高亮）看不见 —— 所以 false 只能读成「似乎没变」，不能读成「点击失败」。
   */
  private fun aHash(bitmap: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(bitmap, HASH_EDGE, HASH_EDGE, true)
    val pixels = IntArray(HASH_EDGE * HASH_EDGE)
    small.getPixels(pixels, 0, HASH_EDGE, 0, 0, HASH_EDGE, HASH_EDGE)
    if (small !== bitmap) small.recycle()
    var total = 0
    for (pixel in pixels) total += luma(pixel)
    val mean = total / pixels.size
    var hash = 0L
    for (index in pixels.indices) {
      if (luma(pixels[index]) > mean) hash = hash or (1L shl index)
    }
    return hash
  }

  private fun luma(pixel: Int): Int {
    val red = (pixel shr 16) and 0xFF
    val green = (pixel shr 8) and 0xFF
    val blue = pixel and 0xFF
    return (red * 299 + green * 587 + blue * 114) / 1000
  }

  /**
   * 捕获位图尺寸与 displayMetrics 不一致时记一条（**只记两个尺寸，不记内容**）。
   *
   * 换算用的是捕获尺寸，而别处（swipe 的半屏、悬浮层定位）用的是 displayMetrics。
   * ColorOS 的显示缩放 / 异形屏下两者可能不同，那时坐标会「差一点」—— 有这条标记，
   * 这类偏差在审计里看得见，而不是靠人猜。
   */
  private fun auditFrameSizeMismatch(width: Int, height: Int) {
    val metrics = resources.displayMetrics
    if (metrics.widthPixels == width && metrics.heightPixels == height) return
    audit(
      "frame_mismatch",
      "capture" to "${width}x$height",
      "metrics" to "${metrics.widthPixels}x${metrics.heightPixels}",
    )
  }

  /** 只在 [captureBitmap] 里调用（它负责串行化与退避）；注解是给 lint 看的不变量。 */
  @RequiresApi(Build.VERSION_CODES.R)
  private suspend fun captureScreen(): Bitmap? =
    suspendCancellableCoroutine { cont ->
      val dispatched =
        runCatching {
          takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
              override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = bitmapFrom(screenshot)
                if (cont.isActive) cont.resume(bitmap)
              }

              override fun onFailure(errorCode: Int) {
                lastCaptureErrorCode = errorCode
                Log.w(TAG, "takeScreenshot failed: ${screenshotErrorName(errorCode)}")
                if (cont.isActive) cont.resume(null)
              }
            },
          )
          true
        }.getOrElse { throwable ->
          Log.w(TAG, "takeScreenshot threw: ${throwable.javaClass.simpleName}")
          false
        }
      if (!dispatched && cont.isActive) cont.resume(null)
    }

  /** HardwareBuffer → 软件位图；句柄无论如何都要关掉。只在 API 30+ 调用。 */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun bitmapFrom(screenshot: ScreenshotResult): Bitmap? {
    val hardware =
      runCatching { Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace) }
        .getOrNull()
    val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
    hardware?.recycle()
    runCatching { screenshot.hardwareBuffer.close() }
    return bitmap
  }

  private fun scaleToLongEdge(source: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = maxOf(source.width, source.height)
    if (longEdge <= maxEdge) return source
    val ratio = maxEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
      source,
      (source.width * ratio).toInt().coerceAtLeast(1),
      (source.height * ratio).toInt().coerceAtLeast(1),
      true,
    )
  }

  /** PNG 优先（界面文字无损）；超过预算再退回 JPEG，避免把请求图像预算吃穿。 */
  private fun encodeScreenshot(bitmap: Bitmap): Pair<ByteArray, String> {
    val png =
      ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
    if (png.size <= SCREENSHOT_PNG_BUDGET_BYTES) return png to "image/png"
    val jpeg =
      ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, out)
        out.toByteArray()
      }
    return jpeg to "image/jpeg"
  }

  private suspend fun executePress(key: PressKey): DeviceActionResult =
    when (key) {
      PressKey.BACK -> executeGlobalAction(GLOBAL_ACTION_BACK, "返回")
      PressKey.HOME -> executeGlobalAction(GLOBAL_ACTION_HOME, "主页")
      PressKey.RECENTS -> executeGlobalAction(GLOBAL_ACTION_RECENTS, "最近任务")
      PressKey.ENTER -> executeImeEnter()
    }

  private fun executeGlobalAction(action: Int, label: String): DeviceActionResult {
    val ok = performGlobalAction(action)
    return DeviceActionResult(ok, if (ok) "已按$label" else "$label 失败")
  }

  /**
   * 「按输入框自己的动作键」（发送 / 搜索 / 前往）—— 与 [executeTypeText] 同一条顺序：
   * 控件树优先，无障碍文本注入通道兜底。
   *
   * 这一步值得有兜底，因为「发一条消息」的最后一下往往就是它：只补上打字、补不上发送，
   * 任务还是做不完。
   */
  private suspend fun executeImeEnter(): DeviceActionResult {
    val target = applicationRoot()?.let { findEditable(it) }
    // ACTION_IME_ENTER 是 API 30 起才有的语义动作，更早的系统只能走下面的通道。
    if (target != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val ok = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      if (ok) {
        audit("ime_enter", "via" to "tree")
        return DeviceActionResult(true, "已触发输入框发送/搜索")
      }
    }
    if (TextChannel.enter()) {
      audit("ime_enter", "via" to "ime_channel")
      return DeviceActionResult(true, "已触发输入框发送/搜索（经无障碍文本注入通道）")
    }
    val failure =
      textInputFailure(
        hadEditableNode = target != null,
        channelSupported = TextChannel.supported(),
        flagActive = TextChannel.flagActive(),
        channelReady = TextChannel.isReady(),
      )
    audit("ime_enter", "via" to "none", "code" to failure.code)
    return DeviceActionResult(false, failure.summary, code = failure.code)
  }

  private suspend fun executeLaunchApp(query: String): DeviceActionResult = withContext(Dispatchers.Default) {
    val pm = packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Suppress("DEPRECATION")
    val activities = pm.queryIntentActivities(launcher, 0)
    val match =
      activities.firstOrNull { it.loadLabel(pm).toString().contains(query, ignoreCase = true) }
        ?: activities.firstOrNull { it.activityInfo.packageName.contains(query, ignoreCase = true) }
        ?: return@withContext DeviceActionResult(false, "找不到 App：$query")
    val label = match.loadLabel(pm).toString()
    val launch =
      Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(match.activityInfo.packageName, match.activityInfo.name))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val failure = runCatching { startActivity(launch) }.exceptionOrNull()
    if (failure == null) {
      DeviceActionResult(true, "已启动 $label（${match.activityInfo.packageName}）")
    } else {
      DeviceActionResult(false, "启动 $label 失败：${failure.javaClass.simpleName}")
    }
  }

  private suspend fun executeSwipe(direction: SwipeDirection): DeviceActionResult {
    if (touchExplorationActive) {
      // 探索态下注入的手势会被系统吞掉，所以有语义动作（可滚动节点）就先走它。
      semanticScroll(direction)?.let { return it }
      // 没有可滚动节点时以前会落到 dispatchPath：手势被吞、onCompleted 照样回调，
      // 于是回一句 ok=true 的「已滑动」—— 模型以为滑了。这里如实报失败。
      return DeviceActionResult(false, "触摸探索开启：没有可语义滚动的节点，注入手势也会被系统吞掉")
    }
    val dm = resources.displayMetrics
    val w = dm.widthPixels
    val h = dm.heightPixels
    val (x1, y1, x2, y2) =
      when (direction) {
        SwipeDirection.UP -> listOf(w / 2, h * 3 / 4, w / 2, h / 4)
        SwipeDirection.DOWN -> listOf(w / 2, h / 4, w / 2, h * 3 / 4)
        SwipeDirection.LEFT -> listOf(w * 3 / 4, h / 2, w / 4, h / 2)
        SwipeDirection.RIGHT -> listOf(w / 4, h / 2, w * 3 / 4, h / 2)
      }
    val path =
      Path().apply {
        moveTo(x1.toFloat(), y1.toFloat())
        lineTo(x2.toFloat(), y2.toFloat())
      }
    // 滑动前记一份内容哈希：模型因此知道「这一滑到底有没有生效」，不必每次滑动后都读屏。
    // 用控件树而不是截图 —— 截图有频率限制，滑动又天然是连续动作。
    val before = treeContentHash()
    val gesture = dispatchPath(path, SWIPE_DURATION_MS, "滑动 ${direction.name}")
    if (!gesture.ok) return gesture
    val changed = verifySwipeChanged(before)
    val verdict =
      when (changed) {
        true -> "界面已变化"
        false -> "界面似乎没有变化（可能已经到头，或手势被系统吞掉）"
        null -> "无法验证界面是否变化"
      }
    return gesture.copy(summary = "${gesture.summary}；$verdict", changed = changed)
  }

  /** 当前屏的内容哈希；读不到根节点（安全窗口 / 无窗口）时返回 null，按「验不了」处理。 */
  private fun treeContentHash(): Long? = applicationRoot()?.let { contentHash(collectScreen(it).nodes) }

  private suspend fun verifySwipeChanged(baseline: Long?): Boolean? {
    if (baseline == null) return null
    // 滑动带惯性，350ms 可能还在动画中；等久一点再比，避免把「还在滚」判成「没变」。
    delay(SWIPE_SETTLE_DELAY_MS)
    return treeContentHash()?.let { it != baseline }
  }

  private suspend fun dispatchPath(path: Path, durationMs: Long, label: String): DeviceActionResult =
    suspendCancellableCoroutine { cont ->
      val gesture =
        GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
      val dispatched =
        runCatching {
          dispatchGesture(
            gesture,
            object : GestureResultCallback() {
              override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(DeviceActionResult(true, "$label 完成"))
              }

              override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(DeviceActionResult(false, "$label 被系统取消"))
              }
            },
            null,
          )
        }.getOrElse { throwable ->
          if (cont.isActive) cont.resume(DeviceActionResult(false, "$label 异常：${throwable.javaClass.simpleName}"))
          false
        }
      if (!dispatched && cont.isActive) cont.resume(DeviceActionResult(false, "$label 下发失败"))
    }

  // ---------- 审计标记 ----------

  /**
   * 往 logcat 打一行**给机器读**的审计标记，tag 固定 [AUDIT_TAG]。
   * `tools/audit/collect.mjs` 抓这段 logcat，按时间戳并进 events.jsonl。
   *
   * 为什么需要它：触摸探索开着时 `dispatchGesture` 注入的手势会被系统吞掉 —— 此时
   * 点击/滑动失效是**预期行为**（模型应当改用语义动作），关着却失效才是故障。
   * 这个状态不在 dsh 会话里，不进 logcat 就分不清这两类，审计无从归因。
   *
   * 格式：`ev=<事件名> <字段>=<值> …`，空格分隔，**值里不要有空格**。
   * 只记状态与计数，**不记屏幕内容、不记任务正文**（AGENTS.md 隐私红线）。
   */
  private fun audit(event: String, vararg fields: Pair<String, Any?>) {
    Log.i(AUDIT_TAG, "ev=$event" + fields.joinToString("") { " ${it.first}=${it.second}" })
  }

  /** 上一次实际观测到的触摸探索状态；只在变化时打标记，避免每个动作都刷一行。 */
  private var lastObservedExploration: Boolean? = null

  /**
   * 动作执行前记一次「触摸探索**实际**是否生效」。
   *
   * 与 [setTouchExplorationForTask] 记的「我们请求了什么」是两回事：用户自己开着
   * 屏幕阅读器（TalkBack 等）时，全局触摸探索一直是开的 —— 我们置位或清零自己的
   * flag 都不改变实际状态（系统取的是所有已启用服务的**或**）。
   *
   * 审计要的是实际状态：拿请求值当实际值，会在用户开着屏幕阅读器时把
   * **预期的手势失效**误判成故障，方向正好反了。
   * 在动作前读、而不是在切换后回读，是因为 serviceInfo 由系统异步生效，
   * 刚写完的那一瞬读到的可能是旧值。
   */
  private fun observeExploration() {
    val active = touchExplorationActive
    if (active == lastObservedExploration) return
    lastObservedExploration = active
    audit("exploration_observed", "active" to active)
  }

  // ---------- 触摸探索模式 ----------

  /**
   * 触摸探索是否开启。
   *
   * 两个后果：① 部分 App（实测微信 8.0.78）只在它为真时向无障碍暴露控件树；
   * ② 它为真时 dispatchPath 注入的手势会被系统当成探索事件吞掉 —— 点击 / 长按 / 上下滑动
   * 必须改走 performAction 语义动作。开关由 [setTouchExplorationForTask] 按任务切换。
   */
  val touchExplorationActive: Boolean
    get() =
      runCatching {
        (getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled ==
          true
      }
        .getOrDefault(false)

  /**
   * 任务期触摸探索：开始时请求，结束时关掉。
   *
   * 不能常开 —— 开着时用户的手动点击会变成「先探索再双击」。所以只在任务期间打开：
   * 打开后隐藏控件树的 App（微信）会暴露节点，代价是这期间手势失效、只能走语义动作。
   */
  fun setTouchExplorationForTask(enabled: Boolean) {
    runCatching {
      val info = serviceInfo ?: return
      val flag = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
      val wanted = if (enabled) info.flags or flag else info.flags and flag.inv()
      val changed = wanted != info.flags
      if (changed) {
        info.flags = wanted
        serviceInfo = info
        Log.i(TAG, "touch exploration for task=$enabled flags=$wanted")
      }
      // 即使这次没改动也留一条：审计要回答的是「那一步执行时是什么状态」，
      // 不是「这次调用有没有改它」。只在变化时记，单次切换的任务就会完全没有状态记录。
      // 注意这里记的是**请求**值；实际是否生效由 observeExploration 在动作前观测。
      audit("exploration_request", "want" to enabled, "changed" to changed)
    }
  }

  // 增量读屏后 ref 不再等于下标：稳定 ref 可能来自更早的一次读屏，所以按 ref 建映射。
  // 插入顺序与当前快照的前序一致（nodeAt 依赖「同坐标最后一个 = 最深」）。
  @Volatile private var lastNodeHandles: Map<Int, AccessibilityNodeInfo> = emptyMap()

  /** 语义点击：ref 的节点自身或最近的 clickable 祖先执行 ACTION_CLICK / ACTION_LONG_CLICK。 */
  private suspend fun semanticClick(ref: Int, long: Boolean): DeviceActionResult {
    val handle =
      lastNodeHandles[ref]
        ?: return DeviceActionResult(false, "ref=$ref 没有活的节点句柄：重新 read_ui_tree")
    val label = if (long) "长按" else "点击"
    val target =
      actionableAncestor(handle, long)
        ?: return DeviceActionResult(false, "ref=$ref 自身与祖先都不可$label（触摸探索下只能走语义动作）")
    val action =
      if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
    if (!target.performAction(action)) {
      return DeviceActionResult(false, "ref=$ref 语义${label}被系统拒绝")
    }
    delay(ACTION_SETTLE_DELAY_MS)
    val targetClass = target.className?.toString()?.substringAfterLast('.') ?: "?"
    return DeviceActionResult(true, "已语义$label [$ref]（$targetClass）")
  }

  /** 从 node 自身往上找第一个可点的祖先（长按看 long_clickable，退回 clickable）。 */
  private fun actionableAncestor(node: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    var climbed = 0
    while (current != null && climbed <= MAX_ANCESTOR_CLIMB) {
      val usable = if (long) current.isLongClickable || current.isClickable else current.isClickable
      if (usable) return current
      current = current.parent
      climbed++
    }
    return null
  }

  /**
   * 坐标命中的可语义操作节点。
   *
   * 读屏是前序遍历，同坐标上最后出现的即最深（最具体）的节点；再往上找可点祖先。
   */
  private fun nodeAt(x: Int, y: Int, long: Boolean): AccessibilityNodeInfo? {
    val deepest =
      lastNodeHandles.values.lastOrNull { handle ->
        Rect().also { handle.getBoundsInScreen(it) }.contains(x, y)
      } ?: return null
    return actionableAncestor(deepest, long)
  }

  /** 上下滑动的语义兜底：对面积最大的可滚动节点做 SCROLL_FORWARD / BACKWARD。 */
  private fun semanticScroll(direction: SwipeDirection): DeviceActionResult? {
    val action =
      when (direction) {
        SwipeDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        SwipeDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else -> return null
      }
    val target =
      lastNodeHandles.values
        .filter { it.isScrollable && it.isVisibleToUser }
        .maxByOrNull { handle ->
          val rect = Rect().also { handle.getBoundsInScreen(it) }
          rect.width() * rect.height()
        } ?: return null
    val ok = target.performAction(action)
    val targetClass = target.className?.toString()?.substringAfterLast('.') ?: "?"
    return DeviceActionResult(
      ok,
      if (ok) "已语义滚动 ${direction.name}（$targetClass）" else "语义滚动 ${direction.name} 被拒绝",
    )
  }

  /** takeScreenshot 的错误码 → 可读名（执行层的截图也用它记日志）。 */
  private fun screenshotErrorName(code: Int): String =
    when (code) {
      AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
      AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
      AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
      AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
      AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
      AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
      else -> "UNKNOWN"
    }

  // ---------- 工具 ----------

  /**
   * 取「应用窗口」的根节点。
   *
   * 输入法弹出时 `rootInActiveWindow` 会指向 IME 窗口（键盘），读到的树里没有聊天内容。
   * 执行层一律优先用应用窗口：TYPE_APPLICATION 且 active 的，其次任取一个 TYPE_APPLICATION。
   */
  private fun applicationRoot(): AccessibilityNodeInfo? {
    val windows = runCatching { getWindows() }.getOrNull().orEmpty()
    val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    return applicationWindows.firstOrNull { it.isActive }?.root
      ?: applicationWindows.firstOrNull()?.root
      ?: rootInActiveWindow
  }

  private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (focused != null && focused.isEditable && focused.isVisibleToUser) return focused

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES) {
      val node = queue.removeFirst()
      visited++
      if (node.isEditable && node.isVisibleToUser) return node
      for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
    }
    return null
  }

  companion object {
    private const val TAG = "ZoneuDevice"

    /**
     * 审计标记的 logcat tag（见 [audit]）。`tools/audit/collect.mjs` 按它过滤，
     * 改这里必须同步改那边，否则 run 目录里会静默地一条标记都没有。
     */
    const val AUDIT_TAG = "ZoneuAudit"

    /** 遍历节点上限，防止超大界面把主线程拖死。 */
    private const val MAX_NODES = 4000

    /** read_ui_tree 快照的节点上限（给 LLM 的行数是另一层截断；聊天列表节点很多）。 */
    private const val MAX_SCREEN_NODES = 1500

    private const val TAP_DURATION_MS = 60L

    private const val SWIPE_DURATION_MS = 300L

    /** 语义动作后等界面反应一拍（与 FOCUS_CHECK 同量级）。 */
    private const val ACTION_SETTLE_DELAY_MS = 350L
    private const val SWIPE_SETTLE_DELAY_MS = 500L

    /** 往上找可点祖先的最大层数，避免爬到整棵树的根。 */
    private const val MAX_ANCESTOR_CLIMB = 6

    /**
     * 截图长边上限。
     *
     * 取 2048 是为了对齐 dsh 的默认归一化/像素预算（2048px、2048×2048），
     * 1080×2344 的机器缩到 943×2048 —— 宽度接近原分辨率，界面文字才读得清；
     * 再大就会被路由的像素预算再缩一次，白传。
     */
    private const val MAX_SCREENSHOT_EDGE = 2048

    /** PNG 超过它就转 JPEG（界面截图多为大片纯色，PNG 通常远低于此）。 */
    private const val SCREENSHOT_PNG_BUDGET_BYTES = 900 * 1024

    /** 界面指纹的边长：8×8 = 64 位，够分辨整屏级变化，成本可忽略。 */
    private const val HASH_EDGE = 8

    /** 截图撞上最小间隔限制后的退避时长（限流窗口是秒级，退避一次就够）。 */
    private const val SCREENSHOT_RETRY_DELAY_MS = 400L

    private const val SCREENSHOT_JPEG_QUALITY = 80

    /** 输入框文本异步刷新，读回验证前等一拍。 */
    private const val TEXT_VERIFY_DELAY_MS = 400L

    @Volatile private var instance: ZoneuAccessibilityService? = null

    fun get(): ZoneuAccessibilityService? = instance
  }
}

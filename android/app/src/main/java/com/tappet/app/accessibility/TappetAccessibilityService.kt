package com.tappet.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.tappet.app.device.CapabilityResult
import com.tappet.app.device.CapabilityStatus
import com.tappet.app.device.DeviceAction
import com.tappet.app.device.DeviceActionResult
import com.tappet.app.device.DeviceBridge
import com.tappet.app.device.DeviceBridgeState
import com.tappet.app.device.DeviceCapability
import com.tappet.app.device.EMPTY_TREE_HINT
import com.tappet.app.device.EMPTY_TREE_NODE_THRESHOLD
import com.tappet.app.device.NodeBounds
import com.tappet.app.device.PressKey
import com.tappet.app.device.ScreenNode
import com.tappet.app.device.ScreenSnapshot
import com.tappet.app.device.SwipeDirection
import com.tappet.app.device.renderSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
class TappetAccessibilityService : AccessibilityService(), DeviceBridge {
  private var agentOverlay: AgentOverlayController? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    agentOverlay?.stop()
    agentOverlay = AgentOverlayController(this).also { it.start() }
    instance = this
    DeviceBridgeState.setConnected(true)
    // 默认不带触摸探索标志：带着会让用户的手动点击变成「探索 + 双击」。
    setTouchExplorationForTask(false)
    Log.i(TAG, "service connected (touchExploration=$touchExplorationActive)")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: Intent?): Boolean {
    agentOverlay?.stop()
    agentOverlay = null
    if (instance === this) {
      instance = null
      DeviceBridgeState.setConnected(false)
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
      DeviceBridgeState.setConnected(false)
    }
    Log.i(TAG, "service destroyed")
    super.onDestroy()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    agentOverlay?.onConfigurationChanged()
  }

  override fun probe(capability: DeviceCapability) {
    when (capability) {
      DeviceCapability.READ_TREE -> probeReadTree()

      DeviceCapability.SCREENSHOT ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          probeScreenshot()
        } else {
          publish(failed(DeviceCapability.SCREENSHOT, "API < 30，系统没有 takeScreenshot"))
        }

      DeviceCapability.SET_TEXT -> probeSetText()

      DeviceCapability.TAP -> probeTap()
    }
  }

  // ---------- 执行层：原子动作 ----------

  private var lastSnapshot: ScreenSnapshot? = null

  override suspend fun execute(action: DeviceAction): DeviceActionResult {
    // 执行前先记「触摸探索实际是否生效」——「点了没反应」是不是预期行为全靠它区分
    observeExploration()
    return when (action) {
      DeviceAction.ReadScreen -> executeReadScreen()
      is DeviceAction.TapNode -> executeTapNode(action.ref)
      is DeviceAction.TapPoint -> executeTapPoint(action.x, action.y)
      is DeviceAction.LongPressNode -> executeLongPressNode(action.ref, action.durationMs)
      is DeviceAction.LongPressPoint -> executeLongPressPoint(action.x, action.y, action.durationMs)
      is DeviceAction.TypeText -> executeTypeText(action.text)
      is DeviceAction.Press -> executePress(action.key)
      is DeviceAction.LaunchApp -> executeLaunchApp(action.query)
      is DeviceAction.Swipe -> executeSwipe(action.direction)
      DeviceAction.Screenshot -> executeScreenshot()
    }
  }

  private suspend fun executeReadScreen(): DeviceActionResult = withContext(Dispatchers.Default) {
    val root = applicationRoot() ?: return@withContext DeviceActionResult(false, "rootInActiveWindow = null")
    val nodes = mutableListOf<ScreenNode>()
    // 与 nodes 同序：ref 即下标。触摸探索下点击只能走 performAction，需要活句柄。
    val handles = mutableListOf<AccessibilityNodeInfo>()
    var truncated = false
    var ref = 0
    val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
      if (nodes.size >= MAX_SCREEN_NODES) {
        truncated = true
        break
      }
      val (node, depth) = stack.removeLast()
      val rect = Rect().also { node.getBoundsInScreen(it) }
      nodes +=
        ScreenNode(
          ref = ref++,
          className = node.className?.toString().orEmpty(),
          text = node.text?.toString(),
          contentDescription = node.contentDescription?.toString(),
          bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
          depth = depth,
          clickable = node.isClickable,
          longClickable = node.isLongClickable,
          editable = node.isEditable,
          scrollable = node.isScrollable,
          focused = node.isFocused,
          visible = node.isVisibleToUser,
        )
      handles += node
      for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it to depth + 1) }
    }
    val snapshot = ScreenSnapshot(root.packageName?.toString(), nodes, truncated)
    lastSnapshot = snapshot
    lastNodeHandles = handles
    lastNodeHandles = handles
    val labeled = nodes.count { it.hasLabel }
    val editable = nodes.count { it.editable }
    DeviceActionResult(
      ok = true,
      summary = "读到 ${nodes.size} 个节点（有文字 $labeled，可编辑 $editable）",
      payload =
        renderSnapshot(snapshot) +
          if (nodes.size < EMPTY_TREE_NODE_THRESHOLD) "\n" + EMPTY_TREE_HINT else "",
    )
  }

  private suspend fun executeTapNode(ref: Int): DeviceActionResult {
    val node =
      lastSnapshot?.nodes?.firstOrNull { it.ref == ref }
        ?: return DeviceActionResult(false, "ref=$ref 不存在：先 read_screen 再按行首编号点")
    if (touchExplorationActive) return semanticClick(ref, long = false)
    return executeTapPoint(node.bounds.centerX, node.bounds.centerY)
  }

  private suspend fun executeTapPoint(x: Int, y: Int): DeviceActionResult {
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
        ?: return DeviceActionResult(false, "ref=$ref 不存在：先 read_screen 再按行首编号长按")
    if (touchExplorationActive) return semanticClick(ref, long = true)
    return executeLongPressPoint(node.bounds.centerX, node.bounds.centerY, durationMs)
  }

  private suspend fun executeLongPressPoint(x: Int, y: Int, durationMs: Long): DeviceActionResult {
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

  private suspend fun executeTypeText(text: String): DeviceActionResult = withContext(Dispatchers.Default) {
    val root = applicationRoot() ?: return@withContext DeviceActionResult(false, "应用窗口不可读")
    val target = findEditable(root) ?: return@withContext DeviceActionResult(false, "当前界面没有可见输入框")
    if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArguments(text))
    if (!accepted) return@withContext DeviceActionResult(false, "ACTION_SET_TEXT 被拒绝")
    // Compose / 自绘输入框异步刷新，等一拍再读回
    delay(TEXT_VERIFY_DELAY_MS)
    target.refresh()
    val readBack = target.text?.toString()
    if (readBack == text) {
      DeviceActionResult(true, "已输入 ${text.length} 字符（读回一致）")
    } else {
      DeviceActionResult(true, "已尝试输入 ${text.length} 字符（App 未回读一致，实际可能已生效）")
    }
  }

  private fun setTextArguments(text: String): Bundle =
    Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }

  /**
   * 截屏 → 缩到最长边 [MAX_SCREENSHOT_EDGE] → PNG（超预算转 JPEG）→ base64。
   *
   * 缩图与转码都在设备侧做：请求图像预算按 base64 长度算（pi-ai 默认 20MiB，
   * 且每轮请求都会重发历史图），原图直传会把预算吃穿。
   */
  private suspend fun executeScreenshot(): DeviceActionResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return DeviceActionResult(false, "截图需要 Android 11+")
    }
    val captured = captureScreen() ?: return DeviceActionResult(false, "截图失败（窗口可能禁止截屏）")
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
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    // 手拼 JSON：三个字段都是受控值（固定字面量 / 整数 / base64 字母表），
    // 不值得为它给服务层引入序列化依赖。
    val payload =
      "{\"mediaType\":\"$mediaType\",\"width\":$width,\"height\":$height,\"base64\":\"$base64\"}"
    return DeviceActionResult(
      ok = true,
      summary = "截图 ${width}x$height ${mediaType.substringAfter('/')} ${bytes.size / 1024}KB",
      payload = payload,
    )
  }

  /** 只在 [executeScreenshot] 的 API 30 分支里调用；注解是给 lint 看的不变量。 */
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

  private fun executePress(key: PressKey): DeviceActionResult =
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

  private fun executeImeEnter(): DeviceActionResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return DeviceActionResult(false, "press(enter) 需要 Android 11+")
    }
    val root = applicationRoot() ?: return DeviceActionResult(false, "应用窗口不可读")
    val target = findEditable(root) ?: return DeviceActionResult(false, "当前界面没有可见输入框")
    val ok = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    return DeviceActionResult(ok, if (ok) "已触发输入框发送/搜索" else "输入框拒绝 IME ENTER")
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
    // 上下滑动有语义动作（可滚动节点），触摸探索下必须走它；左右滑动没有，仍靠手势。
    if (touchExplorationActive) semanticScroll(direction)?.let { return it }
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
    return dispatchPath(path, SWIPE_DURATION_MS, "滑动 ${direction.name}")
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

  // ---------- Q1 读屏 ----------

  private fun probeReadTree() {
    val root =
      runCatching { rootInActiveWindow }.getOrElse {
        publish(failed(DeviceCapability.READ_TREE, "rootInActiveWindow 抛异常：${it.javaClass.simpleName}"))
        return
      }
    if (root == null) {
      publish(failed(DeviceCapability.READ_TREE, "rootInActiveWindow = null（当前窗口未向无障碍暴露节点）"))
      return
    }

    var nodes = 0
    var textNodes = 0
    var clickable = 0
    var editable = 0
    var scrollable = 0
    var focused = 0
    var truncated = false
    val classes = mutableMapOf<String, Int>()

    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      nodes++
      if (nodes > MAX_NODES) {
        truncated = true
        break
      }
      val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
      if (hasText) textNodes++
      if (node.isClickable) clickable++
      if (node.isEditable) editable++
      if (node.isScrollable) scrollable++
      if (node.isFocused) focused++
      node.className?.toString()?.let { classes[it] = (classes[it] ?: 0) + 1 }
      for (i in 0 until node.childCount) node.getChild(i)?.let(stack::addLast)
    }

    val windows = runCatching { getWindows() }.getOrNull().orEmpty()
    val windowPackages = windows.mapNotNull { it.root?.packageName?.toString() }.distinct()
    val details =
      buildList {
        add("root pkg=${root.packageName}")
        add("windows=${windows.size} pkg=${windowPackages.joinToString(",")}")
        add(
          "nodes=$nodes${if (truncated) "+（截断）" else ""} text=$textNodes clickable=$clickable " +
            "editable=$editable scrollable=$scrollable focused=$focused",
        )
        add(
          "top classes=" +
            classes.entries.sortedByDescending { it.value }.take(6).joinToString {
              "${it.key.substringAfterLast('.')}×${it.value}"
            },
        )
      }
    val readable = textNodes > 0
    publish(
      CapabilityResult(
        capability = DeviceCapability.READ_TREE,
        status = if (readable) CapabilityStatus.OK else CapabilityStatus.FAILED,
        summary = if (readable) "可读：文本节点 $textNodes 个" else "根节点非空，但没有任何文本节点",
        details = details,
        at = now(),
      ),
    )
  }

  // ---------- Q2 截图 ----------

  /**
   * Q2 截图探针。**只在 API 30+ 调用** —— 守卫在 [probe] 的 dispatch 处，不在函数内：
   * takeScreenshot 的成功回调里 lint 追不到本函数开头的守卫，写回函数内会重新报 NewApi。
   */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun probeScreenshot() {
    val dispatched =
      runCatching {
        takeScreenshot(
          Display.DEFAULT_DISPLAY,
          mainExecutor,
          object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) = inspectScreenshot(screenshot)

            override fun onFailure(errorCode: Int) {
              publish(
                failed(DeviceCapability.SCREENSHOT, "截图失败：${screenshotErrorName(errorCode)}（code=$errorCode）"),
              )
            }
          },
        )
        true
      }.getOrElse { throwable ->
        publish(
          failed(
            DeviceCapability.SCREENSHOT,
            "takeScreenshot 抛异常：${throwable.javaClass.simpleName}（能力未声明？）",
          ),
        )
        false
      }
    if (!dispatched) return
  }

  /** 只被 API 30+ 的 takeScreenshot 成功回调调用。 */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun inspectScreenshot(screenshot: ScreenshotResult) {
    val bitmap = bitmapFrom(screenshot)
    if (bitmap == null) {
      publish(failed(DeviceCapability.SCREENSHOT, "截图成功但位图解码失败"))
      return
    }

    val step = maxOf(1, bitmap.width / 64)
    var total = 0
    var bright = 0
    var y = 0
    while (y < bitmap.height) {
      var x = 0
      while (x < bitmap.width) {
        val color = bitmap.getPixel(x, y)
        total++
        if (Color.red(color) + Color.green(color) + Color.blue(color) > BRIGHT_THRESHOLD) bright++
        x += step
      }
      y += step
    }
    val ratio = if (total == 0) 0.0 else bright.toDouble() / total
    val width = bitmap.width
    val height = bitmap.height
    bitmap.recycle()

    val looksBlank = ratio < BLANK_RATIO
    publish(
      CapabilityResult(
        capability = DeviceCapability.SCREENSHOT,
        status = if (looksBlank) CapabilityStatus.FAILED else CapabilityStatus.OK,
        summary =
          if (looksBlank) {
            "疑似黑图（非黑像素 ${"%.1f".format(ratio * 100)}%）"
          } else {
            "可截：${width}x$height，非黑像素 ${"%.1f".format(ratio * 100)}%"
          },
        details = listOf("采样步长=$step", "非黑判定=RGB 和 > $BRIGHT_THRESHOLD"),
        at = now(),
      ),
    )
  }

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

  // ---------- Q3 输入 ----------

  private fun probeSetText() {
    val root = activeRoot(DeviceCapability.SET_TEXT) ?: return
    val target = findEditable(root)
    if (target == null) {
      publish(failed(DeviceCapability.SET_TEXT, "当前界面没有可见的可编辑节点（输入框可能是自绘）"))
      return
    }

    if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val arguments =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, PROBE_TEXT)
      }
    val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    // Compose / 自绘输入框的文本是异步刷新的：立刻读回会误判失败，等一拍再验。
    Handler(Looper.getMainLooper()).postDelayed(
      { verifySetText(target, accepted, "ACTION_SET_TEXT", allowPasteFallback = true) },
      TEXT_VERIFY_DELAY_MS,
    )
  }

  private fun verifySetText(
    target: AccessibilityNodeInfo,
    accepted: Boolean,
    method: String,
    allowPasteFallback: Boolean,
  ) {
    target.refresh()
    val readBack = target.text?.toString()
    if (readBack == PROBE_TEXT) {
      publishSetTextResult(target, accepted, method, matched = true, readBackLength = readBack.length)
      clearProbeText(target)
      return
    }
    if (allowPasteFallback) {
      val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("tappet-probe", PROBE_TEXT))
      target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
      Handler(Looper.getMainLooper()).postDelayed(
        { verifySetText(target, accepted, "ACTION_FOCUS+ACTION_PASTE", allowPasteFallback = false) },
        TEXT_VERIFY_DELAY_MS,
      )
      return
    }
    publishSetTextResult(target, accepted, method, matched = false, readBackLength = readBack?.length ?: 0)
  }

  private fun publishSetTextResult(
    target: AccessibilityNodeInfo,
    accepted: Boolean,
    method: String,
    matched: Boolean,
    readBackLength: Int,
  ) {
    publish(
      CapabilityResult(
        capability = DeviceCapability.SET_TEXT,
        status = if (matched) CapabilityStatus.OK else CapabilityStatus.FAILED,
        summary = if (matched) "写入成功（$method）" else "写入未生效（accepted=$accepted，读回长度=$readBackLength）",
        details =
          listOf(
            "目标=${target.className?.toString()?.substringAfterLast('.')} pkg=${target.packageName}",
            "方法=$method，读回匹配=$matched",
            "测试串=「$PROBE_TEXT」；未触发发送 / 搜索" + if (matched) "，验证后已清空" else "",
          ),
        at = now(),
      ),
    )
  }

  /** 验证后把测试串清掉，避免用户后续误发。 */
  private fun clearProbeText(target: AccessibilityNodeInfo) {
    val arguments =
      Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
      }
    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
  }

  // ---------- Q4 手势 ----------

  private fun probeTap() {
    val root = activeRoot(DeviceCapability.TAP) ?: return
    val target = findEditable(root)
    if (target == null) {
      publish(
        failed(DeviceCapability.TAP, "没有可编辑节点；探针只点输入框（安全目标），不点其它控件"),
      )
      return
    }
    val rect = Rect()
    target.getBoundsInScreen(rect)
    if (rect.width() <= 0 || rect.height() <= 0) {
      publish(failed(DeviceCapability.TAP, "目标节点 bounds 为空，无法计算坐标"))
      return
    }
    val x = rect.exactCenterX()
    val y = rect.exactCenterY()
    val wasFocused = target.isFocused
    val path = Path().apply {
      moveTo(x, y)
      lineTo(x + 1f, y)
    }
    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()

    val dispatched =
      runCatching {
        dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
              Handler(Looper.getMainLooper()).postDelayed({ verifyFocusAfterTap(x, y, wasFocused) }, FOCUS_CHECK_DELAY_MS)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
              publish(failed(DeviceCapability.TAP, "手势被系统取消"))
            }
          },
          null,
        )
      }.getOrElse { throwable ->
        publish(failed(DeviceCapability.TAP, "dispatchGesture 抛异常：${throwable.javaClass.simpleName}"))
        false
      }
    if (!dispatched) {
      publish(failed(DeviceCapability.TAP, "dispatchGesture 返回 false（能力未声明？）"))
    } else {
      publish(
        CapabilityResult(
          capability = DeviceCapability.TAP,
          status = CapabilityStatus.RUNNING,
          summary = "手势已下发，等待结果…",
          at = now(),
        ),
      )
    }
  }

  private fun verifyFocusAfterTap(x: Float, y: Float, wasFocused: Boolean) {
    val focused = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
    val rect = Rect()
    focused?.getBoundsInScreen(rect)
    val contains = rect.contains(x.toInt(), y.toInt())
    // 有的输入框被聚焦后会因键盘/展开而位移，坐标包含判定会假阴性；
    // 只要焦点变成了可编辑节点且此前没有焦点，就算点击生效。
    val focusGained = focused != null && focused.isEditable && !wasFocused
    val landed = focused != null && focused.isEditable && (contains || focusGained)
    publish(
      CapabilityResult(
        capability = DeviceCapability.TAP,
        status = if (landed) CapabilityStatus.OK else CapabilityStatus.FAILED,
        summary =
          when {
            contains -> "点击生效，焦点落在目标节点"
            focusGained -> "点击生效（输入框被聚焦后位置变化，坐标包含判定不适用）"
            else -> "手势完成，但焦点未落在目标（该 App 可能不响应焦点）"
          },
        details =
          listOf(
            "点击坐标=(${"%.0f".format(x)}, ${"%.0f".format(y)}) 点击前已聚焦=$wasFocused",
            "点击后焦点=${focused?.className?.toString()?.substringAfterLast('.') ?: "null"} bounds=$rect contains=$contains",
          ),
        at = now(),
      ),
    )
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

  private var lastNodeHandles: List<AccessibilityNodeInfo> = emptyList()

  /** 语义点击：ref 的节点自身或最近的 clickable 祖先执行 ACTION_CLICK / ACTION_LONG_CLICK。 */
  private suspend fun semanticClick(ref: Int, long: Boolean): DeviceActionResult {
    val handle =
      lastNodeHandles.getOrNull(ref)
        ?: return DeviceActionResult(false, "ref=$ref 没有活的节点句柄：重新 read_screen")
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
      lastNodeHandles.lastOrNull { handle ->
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
      lastNodeHandles
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

  // ---------- 工具 ----------

  private fun activeRoot(capability: DeviceCapability): AccessibilityNodeInfo? {
    val root = runCatching { rootInActiveWindow }.getOrNull()
    if (root == null) publish(failed(capability, "rootInActiveWindow = null（当前窗口未暴露节点）"))
    return root
  }

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

  private fun failed(capability: DeviceCapability, summary: String) =
    CapabilityResult(capability, CapabilityStatus.FAILED, summary, at = now())

  private fun publish(result: CapabilityResult) {
    // 只记结构信息：计数、包名、类名、坐标、错误码；绝不记页面正文。
    Log.i(TAG, "${result.capability.name} ${result.status}: ${result.summary}")
    result.details.forEach { Log.i(TAG, "  $it") }
    DeviceBridgeState.publish(result)
  }

  private fun now() = System.currentTimeMillis()

  companion object {
    private const val TAG = "TappetProbe"

    /**
     * 审计标记的 logcat tag（见 [audit]）。`tools/audit/collect.mjs` 按它过滤，
     * 改这里必须同步改那边，否则 run 目录里会静默地一条标记都没有。
     */
    const val AUDIT_TAG = "TappetAudit"

    /** 遍历节点上限，防止超大界面把主线程拖死。 */
    private const val MAX_NODES = 4000

    /** read_screen 快照的节点上限（给 LLM 的行数是另一层截断；聊天列表节点很多）。 */
    private const val MAX_SCREEN_NODES = 1500

    private const val TAP_DURATION_MS = 60L

    private const val SWIPE_DURATION_MS = 300L

    private const val PROBE_TEXT = "tappet-probe"

    /** 采样像素亮度阈值：RGB 之和低于它视为黑。 */
    private const val BRIGHT_THRESHOLD = 30

    /** 非黑采样占比低于它视为黑图。 */
    private const val BLANK_RATIO = 0.01

    private const val FOCUS_CHECK_DELAY_MS = 400L

    /** 语义动作后等界面反应一拍（与 FOCUS_CHECK 同量级）。 */
    private const val ACTION_SETTLE_DELAY_MS = 350L

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

    private const val SCREENSHOT_JPEG_QUALITY = 80

    /** 输入框文本异步刷新，读回验证前等一拍。 */
    private const val TEXT_VERIFY_DELAY_MS = 400L

    @Volatile private var instance: TappetAccessibilityService? = null

    fun get(): TappetAccessibilityService? = instance
  }
}

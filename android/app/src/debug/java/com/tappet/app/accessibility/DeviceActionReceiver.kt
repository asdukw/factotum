package com.tappet.app.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.device.BridgeHealth
import com.tappet.app.device.DeviceAction
import com.tappet.app.device.PressKey
import com.tappet.app.device.SwipeDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 仅 debug 构建存在：用 adb 直接驱动执行层原子动作，便于在 QQ 等目标 App 上做可复现实测。
 *
 * ```
 * adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver \
 *   -a com.tappet.app.debug.ACTION --es action READ_SCREEN
 * adb shell ... --es action TAP_NODE --ei ref 12
 * adb shell ... --es action TYPE_TEXT --es text "hello"
 * adb shell ... --es action LONG_PRESS --ei ref 12 --el duration_ms 700
 * adb shell ... --es action PRESS --es key BACK
 * adb shell ... --es action LAUNCH_APP --es query "QQ"
 * adb shell ... --es action SWIPE --es direction DOWN
 * adb shell ... --es action CAPTURE_FRAME
 * adb shell ... --es action TAP_TARGET --es frame_id f1 --ei x1 .. --ei y1 .. --ei x2 .. --ei y2 ..
 * adb shell ... --es action TEXT_CHANNEL
 * adb shell ... --es action IME_TEXT --es text "hello"   # 绕过控件树，只验文本注入通道
 * ```
 *
 * `TEXT_CHANNEL` 不驱动动作，只把无障碍文本注入通道的状态打到 logcat（系统版本 / 配置是否
 * 生效 / 有没有输入框连接 / 输入框类型）；`IME_TEXT` 直接调通道的写入 —— 两者合起来能在
 * 没有 UI 的情况下把这条通道验完（正常路径在树可读时走控件树，通道有没有写成功看不出来）。
 *
 * `--ez logPayload true` 会把读屏全文打进 logcat —— **仅供调试**，默认关闭（隐私红线）。
 */
class DeviceActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val actionName = intent.getStringExtra(EXTRA_ACTION) ?: return
    if (actionName == "AGENT_MODE") {
      // 调试用：不走 DeviceAction 契约，只切触摸探索开关（见 setTouchExplorationForTask）。
      val service = TappetAccessibilityService.get()
      if (service == null) {
        Log.w(TAG, "AGENT_MODE ignored: service not connected")
        return
      }
      val on = intent.getBooleanExtra(EXTRA_ON, false)
      service.setTouchExplorationForTask(on)
      Handler(Looper.getMainLooper())
        .postDelayed(
          { Log.i(TAG, "action=AGENT_MODE on=$on touchExploration=" + service.touchExplorationActive) },
          600L,
        )
      return
    }
    if (actionName == "TEXT_CHANNEL") {
      // 不驱动任何动作，只报文本注入通道此刻的状态（无 UI 时验证它）。
      // 只打结构：系统版本 / 配置是否生效 / 有没有连接 / 输入框类型 —— 不打正文。
      val pending = goAsync()
      CoroutineScope(Dispatchers.Default).launch {
        try {
          Log.i(TAG, "text channel: ${TextChannel.describe()}")
        } finally {
          pending.finish()
        }
      }
      return
    }
    if (actionName == "IME_TEXT" || actionName == "IME_ENTER") {
      // 绕过控件树，直接把文本交给无障碍文本注入通道 —— 用来**隔离**验证这条通道：
      // 树可读的界面上正常路径走的是控件树，通道有没有成功从外面看不出来。
      // IME_TEXT 只打长度，不打正文（隐私红线）。
      val text = intent.getStringExtra(EXTRA_TEXT)
      if (actionName == "IME_TEXT" && text == null) {
        Log.w(TAG, "IME_TEXT 缺 $EXTRA_TEXT")
        return
      }
      val pending = goAsync()
      CoroutineScope(Dispatchers.Default).launch {
        try {
          if (actionName == "IME_TEXT") {
            Log.i(TAG, "ime text: ok=${TextChannel.commit(text!!)} chars=${text.length}")
          } else {
            Log.i(TAG, "ime enter: ok=${TextChannel.enter()}")
          }
        } finally {
          pending.finish()
        }
      }
      return
    }
    val action = parse(actionName, intent)
    if (action == null) {
      Log.w(TAG, "unknown or incomplete action=$actionName")
      return
    }
    val service = TappetAccessibilityService.get()
    if (service == null) {
      // 与桥走同一条路：无障碍没开时记下原因码 —— 界面靠 BridgeHealth 弹「去开启」提示。
      // 这个接收器存在的意义就是「不经 UI、可复现地驱动执行层」，所以失败路径也要一致。
      val unavailable = AccessibilityStatus.unavailableToolResult()
      BridgeHealth.record(unavailable.code)
      Log.w(TAG, "action=$actionName ok=false code=${unavailable.code}")
      return
    }
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        val result = service.execute(action)
        Log.i(TAG, "action=$actionName ok=${result.ok} ${result.summary}")
        if (intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)) {
          val payload = result.payload
          if (payload != null && payload.length > MAX_LOGGED_PAYLOAD_CHARS) {
            // 截图 payload 是几百 KB 的 base64，打进 logcat 没有意义
            Log.i(TAG, "  payload 省略（${payload.length} 字符）")
          } else {
            payload?.lineSequence()?.forEach { Log.i(TAG, "  $it") }
          }
        }
      } finally {
        pending.finish()
      }
    }
  }

  private fun parse(name: String, intent: Intent): DeviceAction? =
    when (name) {
      "READ_SCREEN" -> DeviceAction.ReadScreen

      "TAP_NODE" -> intent.getIntExtra(EXTRA_REF, -1).takeIf { it >= 0 }?.let(DeviceAction::TapNode)

      "TAP_POINT" ->
        DeviceAction.TapPoint(intent.getIntExtra(EXTRA_X, -1), intent.getIntExtra(EXTRA_Y, -1))
          .takeIf { it.x >= 0 && it.y >= 0 }

      "LONG_PRESS" -> {
        val duration = intent.getLongExtra(EXTRA_DURATION_MS, 700L).coerceIn(300L, 2000L)
        val ref = intent.getIntExtra(EXTRA_REF, -1)
        if (ref >= 0) {
          DeviceAction.LongPressNode(ref, duration)
        } else {
          val x = intent.getIntExtra(EXTRA_X, -1)
          val y = intent.getIntExtra(EXTRA_Y, -1)
          if (x >= 0 && y >= 0) DeviceAction.LongPressPoint(x, y, duration) else null
        }
      }

      "TYPE_TEXT" -> intent.getStringExtra(EXTRA_TEXT)?.let(DeviceAction::TypeText)

      "PRESS" ->
        intent.getStringExtra(EXTRA_KEY)?.let { raw ->
          runCatching { DeviceAction.Press(PressKey.valueOf(raw.uppercase())) }.getOrNull()
        }

      "BACK" -> DeviceAction.Press(PressKey.BACK)

      "LAUNCH_APP" -> intent.getStringExtra(EXTRA_QUERY)?.let(DeviceAction::LaunchApp)

      "SCREENSHOT", "CAPTURE_FRAME" -> DeviceAction.Screenshot

      "TAP_TARGET" ->
        frameBox(intent)?.let { DeviceAction.TapFrame(it.frameId, it.x1, it.y1, it.x2, it.y2) }

      "LONG_PRESS_TARGET" ->
        frameBox(intent)?.let {
          val duration = intent.getLongExtra(EXTRA_DURATION_MS, 700L).coerceIn(300L, 2000L)
          DeviceAction.LongPressFrame(it.frameId, it.x1, it.y1, it.x2, it.y2, duration)
        }

      "SWIPE" ->
        intent.getStringExtra(EXTRA_DIRECTION)?.let { raw ->
          runCatching { DeviceAction.Swipe(SwipeDirection.valueOf(raw.uppercase())) }.getOrNull()
        }

      else -> null
    }

  /** 视觉路径的公共入参：帧 id + 图像像素 bbox。缺一个整条动作就不成立。 */
  private class FrameBox(val frameId: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int)

  private fun frameBox(intent: Intent): FrameBox? {
    val frameId = intent.getStringExtra(EXTRA_FRAME_ID)?.takeIf { it.isNotBlank() } ?: return null
    val x1 = intent.getIntExtra(EXTRA_X1, -1)
    val y1 = intent.getIntExtra(EXTRA_Y1, -1)
    val x2 = intent.getIntExtra(EXTRA_X2, -1)
    val y2 = intent.getIntExtra(EXTRA_Y2, -1)
    if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return null
    return FrameBox(frameId, x1, y1, x2, y2)
  }

  companion object {
    private const val TAG = "TappetDevice"

    const val ACTION = "com.tappet.app.debug.ACTION"
    const val EXTRA_ACTION = "action"
    const val EXTRA_REF = "ref"
    const val EXTRA_X = "x"
    const val EXTRA_Y = "y"
    const val EXTRA_TEXT = "text"
    const val EXTRA_QUERY = "query"
    const val EXTRA_DIRECTION = "direction"
    const val EXTRA_KEY = "key"
    const val EXTRA_DURATION_MS = "duration_ms"
    const val EXTRA_LOG_PAYLOAD = "logPayload"
    const val EXTRA_FRAME_ID = "frame_id"
    const val EXTRA_X1 = "x1"
    const val EXTRA_Y1 = "y1"
    const val EXTRA_X2 = "x2"
    const val EXTRA_Y2 = "y2"
    const val EXTRA_ON = "on"

    /** 超过这个长度的 payload 不落日志（截图是 base64 大块）。 */
    private const val MAX_LOGGED_PAYLOAD_CHARS = 4000
  }
}

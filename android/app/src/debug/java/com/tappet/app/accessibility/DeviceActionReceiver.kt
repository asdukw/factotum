package com.tappet.app.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * ```
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
    val action = parse(actionName, intent)
    if (action == null) {
      Log.w(TAG, "unknown or incomplete action=$actionName")
      return
    }
    val service = TappetAccessibilityService.get()
    if (service == null) {
      Log.w(TAG, "action=$actionName ignored: service not connected")
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

      "SCREENSHOT" -> DeviceAction.Screenshot

      "SWIPE" ->
        intent.getStringExtra(EXTRA_DIRECTION)?.let { raw ->
          runCatching { DeviceAction.Swipe(SwipeDirection.valueOf(raw.uppercase())) }.getOrNull()
        }

      else -> null
    }

  companion object {
    private const val TAG = "TappetProbe"

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
    const val EXTRA_ON = "on"

    /** 超过这个长度的 payload 不落日志（截图是 base64 大块）。 */
    private const val MAX_LOGGED_PAYLOAD_CHARS = 4000
  }
}

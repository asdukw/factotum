package com.tappet.app.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tappet.app.device.DeviceCapability

/**
 * 仅 debug 构建存在：让四问可以绕过 UI 用 adb 触发，保证真机探测可复现。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a com.tappet.app.debug.PROBE --es capability READ_TREE
 * ```
 * release 包不包含本类（放在 src/debug）。
 */
class ProbeCommandReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val name = intent.getStringExtra(EXTRA_CAPABILITY) ?: return
    val capability = runCatching { DeviceCapability.valueOf(name) }.getOrNull()
    if (capability == null) {
      Log.w(TAG, "adb probe ignored: unknown capability=$name")
      return
    }
    Log.i(TAG, "adb probe ${capability.name}")
    TappetAccessibilityService.get()?.probe(capability) ?: Log.w(TAG, "adb probe ignored: service not connected")
  }

  companion object {
    private const val TAG = "TappetProbe"

    const val ACTION = "com.tappet.app.debug.PROBE"
    const val EXTRA_CAPABILITY = "capability"
  }
}

package com.tappet.app.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 「无障碍服务此刻能不能用」的唯一判据，以及把它交给用户的那条路。
 *
 * **应用无法自行开启无障碍**（`WRITE_SECURE_SETTINGS` 是系统签名权限）—— 这是有意的：
 * 否则任何 App 都能自己拿到「读屏 + 模拟点击」的权力，权限模型就没了。所以这一层能做的只有
 * 三件事：**检测**、**把用户送到开关那里**、**说清点哪里**。
 *
 * 判据取系统侧的启用列表，而不是「进程内有没有服务实例」：前者能覆盖
 * 「用户在设置里开了、但服务被系统回收或刚崩掉」，那也正是需要用户去看一眼的状态。
 */
object AccessibilityStatus {
  /** 桥在服务不可用时回的错误码。UI 靠它判断，不去匹配中文文案。 */
  const val CODE_NOT_ENABLED = "accessibility_off"

  /**
   * 该不该弹「去开启无障碍」的提示。
   *
   * **纯函数，故意抽出来单测**。判据是两条同时成立：服务当前不可用（[enabled] 为假）**且**
   * 桥确实因为这件事失败过（[lastErrorCode] 等于 [CODE_NOT_ENABLED]）。
   * 后者是关键：纯聊天、只是问问题、模型没碰设备 —— 都不会产生这个码，于是**不会**打扰用户。
   */
  fun shouldPrompt(lastErrorCode: String?, enabled: Boolean): Boolean =
    !enabled && lastErrorCode == CODE_NOT_ENABLED

  // 把桥的动作名翻成「什么操作需要无障碍」。
  // 对话框必须说明具体原因：只说「无障碍没开」，用户不知道这跟自己刚才那句
  // 「往下滑一下」有什么关系。认不出来的动作说「这个操作」，不编。
  fun actionLabel(action: String?): String =
    when (action) {
      "read_ui_tree" -> "读屏"
      "capture_screenshot", "screenshot" -> "截图"
      "tap_node", "tap_point", "tap_target" -> "点击屏幕"
      "long_press", "long_press_target" -> "长按屏幕"
      "type_text" -> "输入文字"
      "press" -> "按系统键"
      "launch_app" -> "启动应用"
      "swipe" -> "滑动屏幕"
      else -> "这个操作"
    }

  fun isEnabled(context: Context): Boolean {
    val manager =
      context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val component = ComponentName.unflattenFromString(TAPPET_ACCESSIBILITY_COMPONENT) ?: return false
    return manager
      .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { info ->
        val service = info.resolveInfo?.serviceInfo ?: return@any false
        service.packageName == component.packageName && service.name == component.className
      }
  }

  /**
   * 打开系统的无障碍设置页。
   *
   * Android 没有「直接把我这个开关打开」的深链 —— 只能送到列表页，由用户自己找到并开启。
   * 个别 ROM 支持带 component 的 extra 直达详情页，但不通用，所以不赌它。
   */
  fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

  /**
   * 工具因「无障碍没开」而失败时的**统一结果**：文案给模型、码给 UI。
   *
   * 桥与 debug 广播入口共用这一个工厂。各写一份的话，将来改文案必然漏一处 ——
   * 而这里漏掉的后果是「模型收到的说明和界面提示对不上」，最难查。
   */
  fun unavailableToolResult(): DeviceActionResult =
    DeviceActionResult(
      ok = false,
      // 给模型看：说清「为什么做不了 + 别重试 + 让用户做什么」。模型改不了这件事，只有用户能。
      summary =
        "无障碍服务未启用，读屏 / 点击 / 截图都用不了。**不要重试这个动作** —— " +
          "应用无法自行开启无障碍（系统不允许），必须由用户手动开启：" +
          "请告诉用户打开 Tappet，按界面顶部提示点「去开启」，跟着指引在系统设置里打开，" +
          "然后重新发起任务。",
      code = CODE_NOT_ENABLED,
    )

  /** 给界面用的操作指引。分清「通用步骤」与「ColorOS 那一步」，后者不点会被系统关掉。 */
  const val GUIDE_STEPS: String =
    "1）点下面的「去开启」，系统会跳到「设置 → 无障碍」；\n" +
      "2）在「已下载的应用 / 已安装的服务」里找到 Tappet，把它打开；\n" +
      "3）ColorOS / 一加会再弹一个「保持开启」的确认框（3 秒倒计时）——必须点它，" +
      "不点的话系统会把服务关掉，看起来就像白开了。"
}

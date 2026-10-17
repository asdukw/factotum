package com.tappet.app.ui.chat

/**
 * 工具的**对外显示名**。
 *
 * 模型看到的是原始工具名（`read_screen`、`tap_node`…），但界面上不该出现带下划线的英文 ——
 * 使用者关心的是「读取屏幕」，不是「read_screen」。这里只做展示层映射，不动工具契约。
 *
 * **认不出的工具回落到原名**：插件仓库可以随时加工具，UI 不该因为没同步而显示空白；
 * 宁可难看，也不要猜错。
 */
private val TOOL_LABELS =
  mapOf(
    "read_screen" to "读取屏幕",
    "screenshot" to "截屏",
    "tap_node" to "点按控件",
    "tap_point" to "点按坐标",
    "long_press" to "长按",
    "type_text" to "输入文字",
    "press" to "按键",
    "launch_app" to "启动应用",
    "swipe" to "滑动",
  )

internal fun toolDisplayName(raw: String): String = TOOL_LABELS[raw] ?: raw

/**
 * 思考时长：[elapsedMs] 毫秒 → 人读的字符串。
 *
 * 保留一位小数（「思考了 3.8 秒」比「4 秒」有信息量），且**最少 0.1 秒** —— 界面上的模板是
 * 「思考了 $x」，给「不到 1 秒」会拼成「思考了 不到 1 秒」。超过一分钟换成「M 分 S 秒」，
 * 免得出现「93.4 秒」这种要心算的数字。
 */
internal fun formatThinkingDuration(elapsedMs: Long): String {
  if (elapsedMs < 0) return ""
  val tenths = (elapsedMs / 100).coerceAtLeast(1)
  if (tenths < 600) return "${tenths / 10}.${tenths % 10} 秒"
  val totalSeconds = elapsedMs / 1_000
  return "${totalSeconds / 60} 分 ${totalSeconds % 60} 秒"
}

package com.zoneu.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 工具显示名与思考时长：都是**展示层**的纯函数，但错法很显眼 ——
 * 界面上出现 `read_ui_tree` 这种下划线英文，或者「思考了 0 秒」。
 */
class ToolLabelsTest {
  @Test
  fun `known tools get chinese labels`() {
    assertEquals("读取屏幕", toolDisplayName("read_ui_tree"))
    assertEquals("截屏", toolDisplayName("screenshot"))
    assertEquals("截取画面", toolDisplayName("capture_screenshot"))
    assertEquals("点按控件", toolDisplayName("tap_node"))
    assertEquals("点按坐标", toolDisplayName("tap_point"))
    assertEquals("点按目标", toolDisplayName("tap_target"))
    assertEquals("长按", toolDisplayName("long_press"))
    assertEquals("长按目标", toolDisplayName("long_press_target"))
    assertEquals("输入文字", toolDisplayName("type_text"))
    assertEquals("按键", toolDisplayName("press"))
    assertEquals("启动应用", toolDisplayName("launch_app"))
    assertEquals("滑动", toolDisplayName("swipe"))
  }

  /** 插件仓库随时可能加工具：认不出来就原样显示，宁可难看也不猜。 */
  @Test
  fun `unknown tools fall back to the raw name`() {
    assertEquals("get_time_info", toolDisplayName("get_time_info"))
  }

  /** 界面模板是「思考了 $x」：给「不到 1 秒」会拼出病句，所以最少也给 0.1 秒。 */
  @Test
  fun `duration never rounds down to zero`() {
    assertEquals("0.1 秒", formatThinkingDuration(0))
    assertEquals("0.1 秒", formatThinkingDuration(40))
    assertEquals("0.4 秒", formatThinkingDuration(400))
  }

  @Test
  fun `duration keeps one decimal`() {
    assertEquals("3.8 秒", formatThinkingDuration(3_800))
    assertEquals("59.9 秒", formatThinkingDuration(59_900))
  }

  @Test
  fun `long durations switch to minutes`() {
    assertEquals("1 分 5 秒", formatThinkingDuration(65_000))
    assertEquals("2 分 0 秒", formatThinkingDuration(120_000))
  }

  @Test
  fun `negative duration renders empty`() {
    assertEquals("", formatThinkingDuration(-1))
  }

  /**
   * 模型能调的工具必须**全都有**显示名 —— 漏一个，界面上就冒出 `add_calendar_event` 这种
   * 下划线英文（真机踩过）。这份名单跟 AGENTS.md §7.3 的工具有意重复：插件在本仓库之外，
   * 加工具时这里不红就说明没人同步 UI。
   */
  @Test
  fun `every model-visible tool has a chinese label`() {
    val tools =
      listOf(
        "read_ui_tree",
        "tap_node",
        "long_press",
        "capture_screenshot",
        "tap_target",
        "long_press_target",
        "type_text",
        "press",
        "launch_app",
        "swipe",
        "set_alarm",
        "add_calendar_event",
        "list_calendar_events",
        "update_calendar_event",
        "delete_calendar_event",
        "run_command",
        "mail_list",
        "mail_search",
        "mail_read",
        "mail_thread",
        "mail_labels",
        "mail_modify",
        "mail_draft",
        "mail_send",
        "mail_save_attachment",
      )
    for (tool in tools) assertNotNull("$tool 没有中文显示名", toolLabelOrNull(tool))
  }
}

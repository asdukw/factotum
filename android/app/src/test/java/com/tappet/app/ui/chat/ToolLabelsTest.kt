package com.tappet.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具显示名与思考时长：都是**展示层**的纯函数，但错法很显眼 ——
 * 界面上出现 `read_screen` 这种下划线英文，或者「思考了 0 秒」。
 */
class ToolLabelsTest {
  @Test
  fun `known tools get chinese labels`() {
    assertEquals("读取屏幕", toolDisplayName("read_screen"))
    assertEquals("截屏", toolDisplayName("screenshot"))
    assertEquals("截取画面", toolDisplayName("capture_frame"))
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
}

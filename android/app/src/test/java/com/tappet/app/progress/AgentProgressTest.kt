package com.tappet.app.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressTest {
  @Test
  fun describesToolCallsWithoutDumpingPayloads() {
    assertEquals("输入文字" to "5 个字符", describeToolCall("type_text", """{"text":"hello"}"""))
    assertEquals("启动应用" to "设置", describeToolCall("launch_app", """{"query":"设置"}"""))
    assertEquals("按下系统键" to "返回", describeToolCall("press", """{"key":"back"}"""))
  }

  @Test
  fun summarizesScreenOutputWithKeyLabels() {
    val screen =
      """
      pkg=com.oplus.safecenter nodes=42
      [1] TextView "电池" clickable @(500,880)
      [2] TextView "存储" clickable @(500,1180)
      [3] TextView "显示" clickable @(500,1480)
      """
        .trimIndent()

    assertEquals("电池 · 存储 · 显示 · 42 个控件 · safecenter", summarizeScreenResult(screen))
  }

  @Test
  fun truncatesLongOutputToOneLine() {
    val summary = summarizeText("# 第一行\n第二行", 40)
    assertEquals("第一行", summary)
    assertTrue(summarizeText("x".repeat(20), 10)!!.endsWith("…"))
  }
}

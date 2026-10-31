package com.zoneu.app.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressTest {
  @Test
  fun describesToolCallsWithoutDumpingPayloads() {
    assertEquals("输入文字" to "5 个字符", describeToolCall("type_text", """{"text":"hello"}"""))
    assertEquals("启动应用" to "设置", describeToolCall("launch_app", """{"query":"设置"}"""))
    assertEquals("按键" to "返回", describeToolCall("press", """{"key":"back"}"""))
  }

  /** 视觉路径的动作也要有人话，否则悬浮层里冒出来的是下划线英文。 */
  @Test
  fun describesVisionPathCalls() {
    assertEquals("截取画面" to null, describeToolCall("capture_screenshot", "{}"))
    assertEquals(
      "点按目标" to "图像 (500,1023)",
      describeToolCall("tap_target", """{"screenshot_id":"f1","x1":400,"y1":1000,"x2":600,"y2":1047}"""),
    )
    assertEquals(
      "长按目标" to "图像 (500,1023) · 700ms",
      describeToolCall("long_press_target", """{"screenshot_id":"f1","x1":400,"y1":1000,"x2":600,"y2":1047,"duration_ms":700}"""),
    )
    // 四个数缺一个就当没有 —— 不猜、不显示半截坐标。
    assertEquals("点按目标" to null, describeToolCall("tap_target", """{"screenshot_id":"f1","x1":400}"""))
  }

  /** 日程 / 闹钟是静默写入，界面上没有编辑器可看 —— 进度条必须把「做了什么」说清楚。 */
  @Test
  fun describesSilentWriteCalls() {
    assertEquals(
      "添加日程" to "理发 · 2026-10-26T15:00",
      describeToolCall(
        "add_calendar_event",
        """{"title":"理发","begin":"2026-10-26T15:00","end":"2026-10-26T16:00"}""",
      ),
    )
    assertEquals("设置闹钟" to "07:30", describeToolCall("set_alarm", """{"hour":7,"minute":30}"""))
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
  fun characterToolsNeverCopyBodyOrQueryIntoProgress() {
    val tools = mapOf(
      "create_character_card" to "创建角色卡",
      "import_character_card_url" to "下载并导入角色卡",
      "list_character_cards" to "查找本地角色卡",
      "read_character_card" to "读取角色卡",
      "update_character_card" to "修改角色卡",
    )
    for ((name, label) in tools) {
      assertEquals(label to null, describeToolCall(name, """{"query":"PRIVATE","fields":{"name":"PRIVATE","personality":"PRIVATE"}}"""))
      assertEquals("详见角色卡工具结果", summarizeToolResult(name, "PRIVATE_BODY"))
    }
    for (name in listOf("preview_character_card_update", "cancel_character_card")) {
      assertEquals("详见角色卡工具结果", summarizeToolResult(name, "PRIVATE_BODY"))
    }
    assertEquals("角色卡读取完成", toolResultTitle("read_character_card", true))
    assertEquals("角色卡修改完成", toolResultTitle("update_character_card", true))
  }

  @Test
  fun worldToolsNeverCopyAnyUntrustedTextIntoProgress() {
    val tools = listOf(
      "list_world_books",
      "read_world_book",
      "create_world_book",
      "update_world_book",
      "copy_world_book",
      "delete_world_book",
      "read_world_book_bindings",
      "update_world_book_bindings",
      "explain_world_activation",
    )
    for (name in tools) {
      val call = describeToolCall(name, """{"query":"PRIVATE","fields":{"name":"PRIVATE"},"text":"PRIVATE"}""")
      assertEquals(null, call.second)
      assertTrue(!call.first.contains("PRIVATE"))
      assertEquals("详见世界书工具结果", summarizeToolResult(name, "PRIVATE_BODY"))
    }
    for (name in listOf("preview_world_book_action", "cancel_world_book_action", "bind_world_request", "resolve_world_request")) {
      assertEquals("详见世界书工具结果", summarizeToolResult(name, "PRIVATE_BODY"))
    }
  }

  @Test
  fun truncatesLongOutputToOneLine() {
    val summary = summarizeText("# 第一行\n第二行", 40)
    assertEquals("第一行", summary)
    assertTrue(summarizeText("x".repeat(20), 10)!!.endsWith("…"))
  }
}

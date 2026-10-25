package com.factotum.app.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.factotum.app.agent.ChatMessage
import com.factotum.app.agent.ChatRole
import com.factotum.app.agent.ChatUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Markdown 渲染的设备侧验收：模型实际会写的结构都要真的画出来 ——
 * 标题、嵌套列表、任务列表、表格、引用、带语言标注的代码围栏、行内样式。
 *
 * 断言用文本节点（解析失败时节点根本不存在），同时把整屏截到
 * `files/markdown-render.png`，供人工看排版。截图只在测试里写，不进仓库。
 */
class MarkdownRenderAndroidTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun rich_markdown_renders_all_blocks() {
    composeTestRule.setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
          MarkdownText(
            markdown = FIXTURE,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
          )
        }
      }
    }

    // 解析在 Dispatchers.Default 上异步进行：等到第一个块出现即可，后面都是同一次解析结果。
    composeTestRule.waitUntil(timeoutMillis = 15_000) {
      composeTestRule.onAllNodesWithText("标题一", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 先把整屏截下来：断言失败时截图仍然可用。
    saveScreenshot("markdown-render.png")

    listOf(
      "标题一",
      "第一项",
      "嵌套项 A",
      "有序二",
      "已完成",
      "工具",
      "点击节点",
      "引用一行",
      "println",
    ).forEach { expected ->
      composeTestRule.onNodeWithText(expected, substring = true).assertExists()
    }
  }

  /** 会话页里的集成路径：LazyColumn + 消息卡片 + 操作行，确认正文仍然渲染成块级结构。 */
  @Test
  fun chat_screen_renders_assistant_markdown() {
    val state =
      ChatUiState(
        ready = true,
        sessionId = "s1",
        messages = listOf(ChatMessage(id = "m1", role = ChatRole.Assistant, content = CHAT_FIXTURE)),
      )
    composeTestRule.setContent {
      MaterialTheme {
        ChatScreen(state = state, onInputChange = {}, onSend = {}, onStop = {})
      }
    }

    composeTestRule.waitUntil(timeoutMillis = 15_000) {
      composeTestRule.onAllNodesWithText("回复标题", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    saveScreenshot("chat-markdown.png")

    listOf("回复标题", "第一行", "val x = 1").forEach { expected ->
      composeTestRule.onNodeWithText(expected, substring = true).assertExists()
    }
  }

  private fun saveScreenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, name)
    val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue("截图未写入 $file", file.length() > 0)
  }

  private companion object {
    val CHAT_FIXTURE =
      """
      ## 回复标题

      说明文字，列表如下：

      1. 第一行
      2. 第二行

      ```kotlin
      val x = 1
      ```
      """.trimIndent()

    val FIXTURE =
      """
      # 标题一

      段落里有 **粗体**、*斜体*、`inline code`、~~删除线~~ 和 [链接](https://example.com)。

      ## 列表

      - 第一项
        - 嵌套项 A
        - 嵌套项 B
      - 第二项

      1. 有序一
      2. 有序二

      - [x] 已完成
      - [ ] 未完成

      ## 表格

      | 工具 | 说明 |
      | --- | --- |
      | tap_node | 点击节点 |
      | swipe | 滑动方向 |

      > 引用一行

      ```kotlin
      fun main() {
        println("hello")
      }
      ```
      """.trimIndent()
  }
}

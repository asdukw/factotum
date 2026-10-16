package com.tappet.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 解析的纯 JVM 单测：块级结构与行内样式。 */
class MarkdownTest {
  @Test
  fun `headings and paragraphs keep their structure`() {
    val blocks =
      Markdown.parse(
        """
        # 标题

        第一段
        换行还在

        ## 二级
        """.trimIndent(),
      )

    assertEquals(
      listOf(
        MarkdownBlock.Heading(1, "标题"),
        MarkdownBlock.Paragraph("第一段\n换行还在"),
        MarkdownBlock.Heading(2, "二级"),
      ),
      blocks,
    )
  }

  @Test
  fun `fenced code block keeps language and body`() {
    val blocks =
      Markdown.parse(
        """
        来看代码

        ```kotlin
        val a = 1
        println(a)
        ```
        """.trimIndent(),
      )

    assertEquals(2, blocks.size)
    assertEquals(MarkdownBlock.Paragraph("来看代码"), blocks[0])
    assertEquals(MarkdownBlock.CodeBlock("kotlin", "val a = 1\nprintln(a)"), blocks[1])
  }

  @Test
  fun `unterminated fence from a streaming reply still renders as code`() {
    val blocks = Markdown.parse("说话中间\n```\nval a = 1")

    assertEquals(MarkdownBlock.CodeBlock(null, "val a = 1"), blocks[1])
  }

  @Test
  fun `consecutive bullets and numbers group into one list`() {
    val blocks =
      Markdown.parse(
        """
        - 第一步
        - 第二步

        1. 甲
        2. 乙
        """.trimIndent(),
      )

    assertEquals(
      listOf(
        MarkdownBlock.BulletList(listOf("第一步", "第二步")),
        MarkdownBlock.NumberedList(listOf("甲", "乙")),
      ),
      blocks,
    )
  }

  @Test
  fun `quote and divider are recognised`() {
    val blocks = Markdown.parse("> 提示\n> 继续\n\n---")

    assertEquals(MarkdownBlock.Quote("提示\n继续"), blocks[0])
    assertEquals(MarkdownBlock.Divider, blocks[1])
  }

  @Test
  fun `hash without a space stays a paragraph`() {
    assertEquals(listOf(MarkdownBlock.Paragraph("#标签不是标题")), Markdown.parse("#标签不是标题"))
  }

  @Test
  fun `inline parses code, bold, italic and links`() {
    val tokens = Markdown.inline("看 `read_screen` 和 **粗体**、*斜体*，见 [文档](https://example.com/a_b)")

    assertEquals(InlineToken.Code("read_screen"), tokens[1])
    assertTrue(tokens.contains(InlineToken.Bold("粗体")))
    assertTrue(tokens.contains(InlineToken.Italic("斜体")))
    assertTrue(tokens.contains(InlineToken.Link("文档", "https://example.com/a_b")))
  }

  @Test
  fun `unclosed inline markers stay literal text`() {
    val tokens = Markdown.inline("半截 **没写完")

    assertEquals(1, tokens.size)
    assertEquals(InlineToken.Text("半截 **没写完"), tokens.single())
  }
}

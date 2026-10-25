package com.factotum.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗读文本的纯 JVM 单测。
 *
 * 这一层最值得测：Markdown 摊平与切句错了，用户听到的是「星号星号」或者半句话 ——
 * 而它完全不需要设备就能验证，没有理由把它留到真机上靠耳朵发现。
 */
class SpeakableTextTest {
  @Test
  fun `markdown 标记被摊平，链接只留文字`() {
    val spoken = SpeakableText.of("这是 **重点**，还有 `代码` 和 [文档](https://example.com/a?b=1)。")

    assertEquals("这是 重点，还有 代码 和 文档。", spoken)
  }

  @Test
  fun `标题、列表、引用都读成正文，分隔线不读`() {
    val spoken =
      SpeakableText.of(
        """
        ## 今日待办

        - 买菜
        - 取快递

        > 记得带伞

        ---
        """.trimIndent(),
      )

    assertEquals("今日待办\n买菜\n取快递\n记得带伞", spoken)
  }

  @Test
  fun `代码块整个丢掉`() {
    val spoken =
      SpeakableText.of(
        """
        先跑这段：

        ```kotlin
        fun main() {
          println("hello")
        }
        ```

        然后看输出。
        """.trimIndent(),
      )

    assertEquals("先跑这段：\n然后看输出。", spoken)
  }

  @Test
  fun `整条都是代码时没有可读内容`() {
    assertEquals("", SpeakableText.of("```\nSELECT 1;\n```"))
  }

  @Test
  fun `emoji 与裸链接不读出来`() {
    val spoken = SpeakableText.of("✅ 已完成，详见 https://example.com/x 这里。")

    assertEquals("已完成，详见 这里。", spoken)
  }

  @Test
  fun `表格的分隔行不读，竖线读成停顿`() {
    val spoken = SpeakableText.of("| 项目 | 状态 |\n| --- | --- |\n| 构建 | 通过 |")

    assertEquals("项目，状态\n构建，通过", spoken)
  }

  @Test
  fun `按句末标点切段，标点跟着前一句`() {
    val chunks = SpeakableText.chunks("第一句。第二句！第三句？", limit = 100)

    assertEquals(listOf("第一句。第二句！第三句？"), chunks)
  }

  @Test
  fun `超过上限时在句末断开，不会超限`() {
    val text = "甲".repeat(30) + "。" + "乙".repeat(30) + "。" + "丙".repeat(30) + "。"

    val chunks = SpeakableText.chunks(text, limit = 40)

    assertEquals(3, chunks.size)
    assertTrue(chunks.all { it.length <= 40 })
    assertEquals(text, chunks.joinToString(""))
  }

  @Test
  fun `单句超长时先按逗号软切，不把词从中间劈开`() {
    val text = "甲".repeat(10) + "，" + "乙".repeat(10) + "，" + "丙".repeat(10) + "。"

    val chunks = SpeakableText.chunks(text, limit = 20)

    assertEquals(listOf("甲".repeat(10) + "，", "乙".repeat(10) + "，", "丙".repeat(10) + "。"), chunks)
  }

  @Test
  fun `完全没有标点的长串也要切，且不丢字符`() {
    val text = "字".repeat(100)

    val chunks = SpeakableText.chunks(text, limit = 30)

    assertTrue(chunks.all { it.length <= 30 })
    assertEquals(text, chunks.joinToString(""))
  }

  @Test
  fun `切段不吃掉换行带来的句子边界`() {
    val chunks = SpeakableText.chunks("第一行\n第二行", limit = 100)

    assertEquals(listOf("第一行", "第二行"), chunks)
  }

  @Test
  fun `limit 非法时退回默认值而不是抛异常`() {
    val text = "短句。"

    assertEquals(listOf("短句。"), SpeakableText.chunks(text, limit = 0))
  }
}

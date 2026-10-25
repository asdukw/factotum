package com.factotum.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模态索引（构建期从 pi-ai 目录生成）的解析与查找。
 *
 * 结论同时被两处使用：设置页不展示标签后，运行时仍要按它声明 `FACTOTUM_LLM_MODELS` 的 input。
 * 因此这里的优先级必须和 `ModelModalities` 的注释一致：provider 条目 > 跨 provider 一致条目
 * > 界面目录 > false。
 */
class ModelModalitiesTest {
  @Test
  fun `parse index and tolerate junk`() {
    val index =
      parseModalityIndex(
        """{"zen":{"grok-4.6":["text","image"],"glm":["text"]},"openai":{"gpt-6-luna":["text","image"]}}""",
      )
    assertEquals(listOf("text", "image"), index["zen"]?.get("grok-4.6"))
    assertEquals(listOf("text"), index["zen"]?.get("glm"))

    assertTrue(parseModalityIndex("not json").isEmpty())
    assertTrue(parseModalityIndex("""{"p":{"m":"not-an-array"}}""").isEmpty())
    assertTrue(parseModalityIndex("{}").isEmpty())
  }

  @Test
  fun `provider entry wins over everything`() {
    val index = parseModalityIndex("""{"zen":{"m":["text"]}}""")

    assertFalse(resolveSupportsImage(index, "zen", "m"))
  }

  @Test
  fun `cross provider lookup only when the result is unanimous`() {
    val unanimous = parseModalityIndex("""{"zen":{},"openai":{"m":["text","image"]}}""")
    assertTrue(resolveSupportsImage(unanimous, "zen", "m"))

    val split = parseModalityIndex("""{"a":{"m":["text","image"]},"b":{"m":["text"]}}""")
    assertFalse("分歧时不猜", resolveSupportsImage(split, "zen", "m"))
  }

  @Test
  fun `unknown model is declared text`() {
    val empty = parseModalityIndex("{}")
    assertFalse(resolveSupportsImage(empty, "p", "m"))
  }

  @Test
  fun `known covers provider and cross provider entries`() {
    val index = parseModalityIndex("""{"zen":{"a":["text"]},"openai":{"b":["text","image"]}}""")

    assertTrue(isModelKnown(index, "zen", "a"))
    assertTrue(isModelKnown(index, "zen", "b"))
    assertFalse(isModelKnown(index, "zen", "c"))
  }
}

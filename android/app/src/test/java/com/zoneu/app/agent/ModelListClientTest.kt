package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端点模型列表的纯函数：URL / 认证头 / 解析。网络本身不在单测里。 */
class ModelListClientTest {
  @Test
  fun `models url follows the api style`() {
    assertEquals(
      "https://api.openai.com/v1/models",
      modelsUrl("https://api.openai.com/v1", "openai-responses"),
    )
    assertEquals(
      "https://api.openai.com/v1/models",
      modelsUrl("https://api.openai.com/v1/", "openai-completions"),
    )
    // Anthropic 的 models 在 /v1 下，而它的 baseUrl 通常不带 /v1。
    assertEquals(
      "https://api.anthropic.com/v1/models",
      modelsUrl("https://api.anthropic.com", "anthropic-messages"),
    )
    assertEquals(
      "https://api.anthropic.com/v1/models",
      modelsUrl("https://api.anthropic.com/v1", "anthropic-messages"),
    )
    assertEquals(
      "https://openrouter.ai/api/v1/models",
      modelsUrl("https://openrouter.ai/api/v1", "openai-completions"),
    )
    assertEquals(
      "https://gateway.example/v1/models",
      modelsUrl("https://gateway.example/v1", "openai-completions"),
    )
    // 已经填到 /models 的不重复拼。
    assertEquals("https://x.example/models", modelsUrl("https://x.example/models", "openai-completions"))
    assertNull(modelsUrl("   ", "openai-completions"))
  }

  @Test
  fun `headers follow the auth style`() {
    assertEquals(
      mapOf("Authorization" to "Bearer sk-1"),
      modelListHeaders("openai-completions", "sk-1", zenSession = false),
    )

    val anthropic = modelListHeaders("anthropic-messages", "sk-ant", zenSession = false)
    assertEquals("sk-ant", anthropic["x-api-key"])
    assertEquals("2023-06-01", anthropic["anthropic-version"])
    assertFalse(anthropic.containsKey("Authorization"))

    assertEquals(
      ZEN_SESSION_FALLBACK,
      modelListHeaders("openai-completions", "k", zenSession = true)["x-opencode-session"],
    )
  }

  @Test
  fun `parse openai and anthropic shapes`() {
    assertEquals(
      listOf("a", "b"),
      parseModelIds("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""),
    )
    assertEquals(
      listOf("claude-1"),
      parseModelIds("""{"data":[{"id":"claude-1","display_name":"Claude"}],"has_more":false}"""),
    )
  }

  @Test
  fun `parse top level array and string entries`() {
    assertEquals(listOf("m1", "m2"), parseModelIds("""[{"id":"m2"},"m1"]"""))
  }

  @Test
  fun `parse tolerates junk`() {
    assertTrue(parseModelIds("not json").isEmpty())
    assertTrue(parseModelIds("").isEmpty())
    assertTrue(parseModelIds("""{"data":[]}""").isEmpty())
    assertTrue(parseModelIds("""{"nope":1}""").isEmpty())
    assertEquals(listOf("x"), parseModelIds("""{"data":[{"id":"  x  "},{"id":"   "}]}"""))
  }
}

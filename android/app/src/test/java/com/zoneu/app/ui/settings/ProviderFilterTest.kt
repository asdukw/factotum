package com.zoneu.app.ui.settings

import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.agent.ProviderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提供商搜索是纯函数，单测直接钉：名称、说明、id、当前模型与目录模型都要能搜到。 */
class ProviderFilterTest {
  private fun settings(
    preset: LlmPreset,
    name: String = preset.label,
    model: String = preset.defaultModel,
  ) = ProviderSettings(
    preset = preset,
    name = name,
    baseUrl = preset.baseUrl,
    model = model,
    api = preset.api,
    hasKey = false,
    enabled = false,
  )

  private val all = LlmPreset.entries.map { settings(it) }

  @Test
  fun `blank query returns everything`() {
    assertEquals(all, filterProviders(all, "   "))
  }

  @Test
  fun `query matches provider names and descriptions`() {
    assertEquals(
      listOf(LlmPreset.OpenRouter),
      filterProviders(all, "openrouter").map { it.preset },
    )
  }

  @Test
  fun `query matches model ids across providers`() {
    val result = filterProviders(all, "gpt-6-sol").map { it.preset }
    assertTrue(LlmPreset.OpenAi in result)
    assertTrue(LlmPreset.OpenRouter in result)
  }

  @Test
  fun `query is case insensitive`() {
    assertEquals(
      filterProviders(all, "DEEPSEEK").map { it.preset },
      filterProviders(all, "deepseek").map { it.preset },
    )
  }

  @Test
  fun `no match yields an empty list`() {
    assertTrue(filterProviders(all, "no-such-provider-xyz").isEmpty())
  }

  @Test
  fun `renamed provider is searchable by its display name`() {
    val renamed = listOf(settings(LlmPreset.Custom, name = "公司网关", model = "gpt-5.5"))
    assertEquals(1, filterProviders(renamed, "公司").size)
  }

  @Test
  fun `providers are sorted by display name`() {
    val names = sortProviders(all).map { it.name }

    assertEquals(
      listOf("Anthropic", "DeepSeek", "OpenAI", "OpenCode Go", "OpenRouter", "自定义"),
      names,
    )
    // 改名后跟着新名字排（而不是钉死在目录顺序上）。
    val renamed =
      all.map { if (it.preset == LlmPreset.Custom) it.copy(name = "AAA 网关") else it }
    assertEquals("AAA 网关", sortProviders(renamed).first().name)
  }
}

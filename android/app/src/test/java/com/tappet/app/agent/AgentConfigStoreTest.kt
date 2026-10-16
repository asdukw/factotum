package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析的优先级，纯 JVM 测试（不起 Android）。
 *
 * 这里钉住的是**回落规则**：设置页存的值只在非空白时才盖掉默认值。写反了不会报错，
 * 只会让用户在设置页清空一个输入框之后端点静默失效 —— 正是本仓库吃过的那种亏。
 */
class AgentConfigStoreTest {
  private val defaults =
    AgentConfig(
      baseUrl = "https://default.example/v1",
      // 结构上就是空串：构建期不再有密钥这条路径（见 AgentConfig 的注释）。
      apiKey = "",
      model = "default-model",
      reasoningEffort = "low",
      proxyHost = "127.0.0.1",
      proxyPort = 7897,
      preset = LlmPreset.Custom,
    )

  @Test
  fun `saved values override defaults`() {
    val resolved =
      resolveAgentConfig(
        defaults = defaults,
        savedBaseUrl = "https://saved.example/v1",
        savedModel = "saved-model",
        savedApiKey = "saved-key",
        savedPreset = LlmPreset.DeepSeek.id,
      )

    assertEquals("https://saved.example/v1", resolved.baseUrl)
    assertEquals("saved-model", resolved.model)
    assertEquals("saved-key", resolved.apiKey)
    assertEquals(LlmPreset.DeepSeek, resolved.preset)
  }

  @Test
  fun `nothing saved falls back to defaults`() {
    val resolved =
      resolveAgentConfig(defaults, savedBaseUrl = null, savedModel = null, savedApiKey = null)

    assertEquals(defaults, resolved)
  }

  @Test
  fun `blank saved values do not clobber defaults`() {
    val resolved =
      resolveAgentConfig(defaults, savedBaseUrl = "", savedModel = "   ", savedApiKey = "\t")

    assertEquals("https://default.example/v1", resolved.baseUrl)
    assertEquals("default-model", resolved.model)
    // 密钥不再有「默认值」可回落：没存过就是没有。
    assertEquals("", resolved.apiKey)
  }

  /**
   * 构建期注入已经去掉，这条测试把它钉成**结构性的事实**而不是约定：
   * 无论 defaults 里塞了什么密钥，解析结果都不会把它带出来。
   */
  @Test
  fun `key never comes from defaults`() {
    val withKey = defaults.copy(apiKey = "should-never-surface")

    val resolved =
      resolveAgentConfig(withKey, savedBaseUrl = null, savedModel = null, savedApiKey = null)

    assertEquals("", resolved.apiKey)
    assertFalse(resolved.isConfigured)
  }

  @Test
  fun `saved values are trimmed`() {
    val resolved =
      resolveAgentConfig(
        defaults,
        savedBaseUrl = "  https://x.example/v1  ",
        savedModel = " m ",
        savedApiKey = " k ",
      )

    assertEquals("https://x.example/v1", resolved.baseUrl)
    assertEquals("m", resolved.model)
    assertEquals("k", resolved.apiKey)
  }

  /** 代理与 reasoningEffort 不在设置页里，改设置页不该动它们。 */
  @Test
  fun `proxy and reasoning effort always come from defaults`() {
    val resolved =
      resolveAgentConfig(
        defaults,
        savedBaseUrl = "https://saved.example/v1",
        savedModel = "m",
        savedApiKey = "k",
      )

    assertEquals("127.0.0.1", resolved.proxyHost)
    assertEquals(7897, resolved.proxyPort)
    assertEquals("low", resolved.reasoningEffort)
  }

  /**
   * 预选的推断：老版本升上来时只存了 base URL、没有预选字段，这时按端点反推，
   * 否则会拿「自定义」的调用风格去调 Anthropic 的原生端点。
   */
  @Test
  fun `preset is inferred from base url when nothing is stored`() {
    val resolved =
      resolveAgentConfig(
        defaults,
        savedBaseUrl = LlmPreset.Anthropic.baseUrl,
        savedModel = LlmPreset.Anthropic.defaultModel,
        savedApiKey = "k",
      )

    assertEquals(LlmPreset.Anthropic, resolved.preset)
    assertEquals("anthropic-messages", resolved.api)
  }

  @Test
  fun `unknown preset id falls back to inference then custom`() {
    val byUrl =
      resolveAgentConfig(
        defaults,
        savedBaseUrl = LlmPreset.OpenCodeGo.baseUrl,
        savedModel = null,
        savedApiKey = null,
        savedPreset = "no-such-preset",
      )
    assertEquals(LlmPreset.OpenCodeGo, byUrl.preset)

    val unknownUrl =
      resolveAgentConfig(
        defaults,
        savedBaseUrl = "https://my-own-gateway.example/v1",
        savedModel = null,
        savedApiKey = null,
        savedPreset = "no-such-preset",
      )
    assertEquals(LlmPreset.Custom, unknownUrl.preset)
  }

  /**
   * 预选表本身的体检。这些值是从运行时 pi-ai 的模型目录抄来的，抄错不会编译失败，
   * 只会在第一次请求时以「网络问题」的样子炸掉 —— 所以在这里挡住。
   */
  @Test
  fun `preset table is internally consistent`() {
    val knownApis = setOf("openai-completions", "openai-responses", "anthropic-messages")

    LlmPreset.entries.forEach { preset ->
      assertTrue("${preset.id} 的调用风格不认识：${preset.api}", preset.api in knownApis)
      if (!preset.custom) {
        assertTrue("${preset.id} 缺 baseUrl", preset.baseUrl.startsWith("https://"))
        assertTrue("${preset.id} 缺默认模型", preset.defaultModel.isNotBlank())
        assertTrue(
          "${preset.id} 的默认模型不在候选里，界面提示会自相矛盾",
          preset.defaultModel in preset.modelHints,
        )
      }
    }
    // 端点不能重复，否则 forBaseUrl 反推会撞车。
    val urls = LlmPreset.entries.filterNot { it.custom }.map { it.baseUrl }
    assertEquals(urls.size, urls.toSet().size)
    // 只有本项目一直在用的那条路径是真机上跑通过的。
    assertEquals(listOf(LlmPreset.OpenCodeGo), LlmPreset.entries.filter { it.verified })
  }

  /**
   * 隐私红线的回归测试：这两个方法是要进日志 / 上界面的，任何一个带上密钥本体都是泄漏。
   */
  @Test
  fun `describe and shortSummary never carry the key body`() {
    // 碎片拼装：整串字面量躺在源码里，tools/check-sensitive.ps1 的默认扫描会命中自己（恒红）。
    // Kotlin 编译期会折叠成一个常量，断言强度不变。
    val secret = "oc_sk_" + "this_must_never_be_printed_0123456789"
    val config = defaults.copy(apiKey = secret)

    assertFalse(config.describe().contains(secret))
    assertFalse(config.shortSummary().contains(secret))
    // 但「配没配」这件事要说得出来，否则界面没法提示。
    assertTrue(config.describe().contains("已配置"))
  }

  @Test
  fun `blank key is reported as unconfigured`() {
    val config = defaults.copy(apiKey = "")

    assertFalse(config.isConfigured)
    assertTrue(config.shortSummary().contains("缺 API key"))
  }
}

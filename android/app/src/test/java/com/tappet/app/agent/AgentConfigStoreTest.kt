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
      apiKey = "build-time-key",
      model = "default-model",
      reasoningEffort = "low",
      proxyHost = "127.0.0.1",
      proxyPort = 7897,
    )

  @Test
  fun `saved values override defaults`() {
    val resolved =
      resolveAgentConfig(
        defaults = defaults,
        savedBaseUrl = "https://saved.example/v1",
        savedModel = "saved-model",
        savedApiKey = "saved-key",
      )

    assertEquals("https://saved.example/v1", resolved.baseUrl)
    assertEquals("saved-model", resolved.model)
    assertEquals("saved-key", resolved.apiKey)
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
    assertEquals("build-time-key", resolved.apiKey)
  }

  @Test
  fun `saved values are trimmed`() {
    val resolved =
      resolveAgentConfig(defaults, savedBaseUrl = "  https://x.example/v1  ", savedModel = " m ", savedApiKey = " k ")

    assertEquals("https://x.example/v1", resolved.baseUrl)
    assertEquals("m", resolved.model)
    assertEquals("k", resolved.apiKey)
  }

  /** 代理与 reasoningEffort 不在设置页里，改设置页不该动它们。 */
  @Test
  fun `proxy and reasoning effort always come from defaults`() {
    val resolved =
      resolveAgentConfig(defaults, savedBaseUrl = "https://saved.example/v1", savedModel = "m", savedApiKey = "k")

    assertEquals("127.0.0.1", resolved.proxyHost)
    assertEquals(7897, resolved.proxyPort)
    assertEquals("low", resolved.reasoningEffort)
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

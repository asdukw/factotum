package com.factotum.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置解析的优先级，纯 JVM 测试（不起 Android）。
 *
 * 这里钉住的是**回落规则**：设置页存的值只在非空白时才盖掉默认值。写反了不会报错，
 * 只会让用户在设置页清空一个输入框之后端点静默失效 —— 正是本仓库吃过的那种亏。
 * 新版按提供商解析（[resolveProviderSettings]）与旧版单端点解析（[resolveAgentConfig]）
 * 都在这份测试里：升级路径不写测试，出问题只会表现为「老用户的 key 没了」。
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
      assertTrue("${preset.id} 缺 description", preset.description.isNotBlank())
      if (!preset.custom) {
        assertTrue("${preset.id} 缺 baseUrl", preset.baseUrl.startsWith("https://"))
        // defaultModel 只用于「已验证」组合与运行时兜底，不是界面模型列表。
        assertTrue("${preset.id} 缺默认 / 已验证模型", preset.defaultModel.isNotBlank())
      }
      if (preset.responseApiToggle) {
        assertTrue(
          "${preset.id} 的 Response API 开关没有对应的两种调用风格",
          preset.api in setOf("openai-completions", "openai-responses"),
        )
      }
    }
    // 端点不能重复，否则 forBaseUrl 反推会撞车。
    val urls = LlmPreset.entries.filterNot { it.custom }.map { it.baseUrl }
    assertEquals(urls.size, urls.toSet().size)
    // id 是存储身份（provider.<id>.* 与 Keystore 别名），不能重复也不能空。
    val ids = LlmPreset.entries.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    assertTrue(ids.none { it.isBlank() })
    // 只有本项目一直在用的那条路径是真机上跑通过的。
    assertEquals(listOf(LlmPreset.OpenCodeGo), LlmPreset.entries.filter { it.verified })
    // 自定义提供商没有默认模型：模型由用户手填或从端点拉取。
    assertTrue(LlmPreset.Custom.defaultModel.isBlank())
  }

  // --- 新版：按提供商解析 ---

  @Test
  fun `provider settings fall back to catalog defaults`() {
    val settings =
      resolveProviderSettings(
        preset = LlmPreset.OpenAi,
        overrides = ProviderOverrides(name = "  ", baseUrl = "", model = null, api = " "),
        legacy = null,
        hasKey = false,
        enabled = false,
      )

    assertEquals(LlmPreset.OpenAi.label, settings.name)
    assertEquals(LlmPreset.OpenAi.baseUrl, settings.baseUrl)
    // 没有模型目录可回落：没选就是空，等用户拉取或手填。
    assertEquals("", settings.model)
    assertEquals(LlmPreset.OpenAi.api, settings.api)
    assertFalse(settings.hasKey)
    assertFalse(settings.enabled)
  }

  @Test
  fun `provider overrides win over catalog`() {
    val settings =
      resolveProviderSettings(
        preset = LlmPreset.OpenAi,
        overrides =
          ProviderOverrides(
            name = "  我的 OpenAI  ",
            baseUrl = "  https://gateway.example/v1  ",
            model = " gpt-5.5 ",
            api = " openai-completions ",
          ),
        legacy = null,
        hasKey = true,
        enabled = true,
      )

    assertEquals("我的 OpenAI", settings.name)
    assertEquals("https://gateway.example/v1", settings.baseUrl)
    assertEquals("gpt-5.5", settings.model)
    assertEquals("openai-completions", settings.api)
    assertTrue(settings.hasKey)
    assertTrue(settings.enabled)
  }

  /** 旧版的单端点配置只属于「升级前正在用的那家」，别的提供商不能继承它的端点或密钥。 */
  @Test
  fun `legacy config only applies to the provider it pointed at`() {
    val legacy =
      LegacyProviderSettings(
        preset = LlmPreset.Anthropic,
        baseUrl = "https://legacy.example/v1",
        model = "legacy-model",
        hasKey = true,
      )

    val matching =
      resolveProviderSettings(
        preset = LlmPreset.Anthropic,
        overrides = ProviderOverrides(),
        legacy = legacy,
        hasKey = false,
        enabled = true,
      )
    assertEquals("https://legacy.example/v1", matching.baseUrl)
    assertEquals("legacy-model", matching.model)
    assertTrue(matching.hasKey)

    val other =
      resolveProviderSettings(
        preset = LlmPreset.OpenAi,
        overrides = ProviderOverrides(),
        legacy = legacy,
        hasKey = false,
        enabled = false,
      )
    assertEquals(LlmPreset.OpenAi.baseUrl, other.baseUrl)
    assertEquals("", other.model)
    assertFalse(other.hasKey)
  }

  @Test
  fun `enabled provider resolution distinguishes none from never configured`() {
    assertEquals(LlmPreset.default, resolveEnabledPreset(null, LlmPreset.default))
    assertNull(resolveEnabledPreset(ENABLED_NONE, LlmPreset.default))
    assertEquals(
      LlmPreset.DeepSeek,
      resolveEnabledPreset(LlmPreset.DeepSeek.id, LlmPreset.default),
    )
    assertEquals(LlmPreset.default, resolveEnabledPreset("no-such-provider", LlmPreset.default))
  }

  @Test
  fun `provider config carries the api override into agent config`() {
    val settings =
      resolveProviderSettings(
        preset = LlmPreset.OpenAi,
        overrides = ProviderOverrides(api = "openai-completions", model = "gpt-6-sol"),
        legacy = null,
        hasKey = true,
        enabled = true,
      )

    val config = settings.toAgentConfig("k")

    assertEquals("openai-completions", config.api)
    assertEquals(LlmPreset.OpenAi, config.preset)
    assertEquals("k", config.apiKey)
    assertEquals(LlmPreset.OpenAi.thinkingFormat, config.preset.thinkingFormat)
  }

  @Test
  fun `model count counts the available models`() {
    val selected =
      resolveProviderSettings(
        LlmPreset.OpenAi,
        ProviderOverrides(model = "gpt-6-sol"),
        legacy = null,
        hasKey = false,
        enabled = false,
      )
    assertEquals(1, selected.modelCount)

    // 「可用模型」列表决定卡片上的数量；端点拉回来的清单只是候选，不算进去。
    val withAvailable =
      selected.copy(selectedModels = listOf("gpt-6-sol", "vendor/x", "vendor/y"))
    assertEquals(3, withAvailable.modelCount)
    assertEquals(
      3,
      withAvailable.copy(remoteModels = listOf("vendor/z")).modelCount,
    )

    val empty =
      resolveProviderSettings(
        LlmPreset.Custom,
        ProviderOverrides(),
        legacy = null,
        hasKey = false,
        enabled = false,
      )
    assertEquals(0, empty.modelCount)
  }

  @Test
  fun `custom provider cannot be enabled without endpoint and model`() {
    val empty =
      resolveProviderSettings(
        LlmPreset.Custom,
        ProviderOverrides(),
        legacy = null,
        hasKey = true,
        enabled = false,
      )
    assertFalse(empty.canEnable)

    val filled =
      resolveProviderSettings(
        LlmPreset.Custom,
        ProviderOverrides(baseUrl = "https://gateway.example/v1", model = "my-model"),
        legacy = null,
        hasKey = true,
        enabled = true,
      )
    assertTrue(filled.canEnable)
  }

  // --- 存储身份 / 运行时注入 ---

  /**
   * 存储键是改名红线的一部分：改一个字符，设备上已存的配置与密钥就读不回来。
   * 这条测试把字面量钉死；真要改，先写迁移。
   */
  @Test
  fun `storage identity is pinned`() {
    assertEquals("provider.opencode-go.", providerPrefsPrefix(LlmPreset.OpenCodeGo))
    assertEquals("factotum_agent_secret-openai", providerSecretPrefsName(LlmPreset.OpenAi))
    assertEquals("factotum-agent-llm-key-v1-anthropic", providerKeyAlias(LlmPreset.Anthropic))
  }

  @Test
  fun `build config defaults apply only to the provider they were inferred for`() {
    val defaults = ProviderDefaults(LlmPreset.OpenAi, "https://dev.example/v1", "dev-model")

    val matching =
      resolveProviderSettings(
        preset = LlmPreset.OpenAi,
        overrides = ProviderOverrides(),
        legacy = null,
        hasKey = false,
        enabled = false,
        defaults = defaults,
      )
    assertEquals("https://dev.example/v1", matching.baseUrl)
    assertEquals("dev-model", matching.model)

    val other =
      resolveProviderSettings(
        preset = LlmPreset.DeepSeek,
        overrides = ProviderOverrides(),
        legacy = null,
        hasKey = false,
        enabled = false,
        defaults = defaults,
      )
    assertEquals(LlmPreset.DeepSeek.baseUrl, other.baseUrl)
    // 没有模型目录：BuildConfig 的默认模型只属于它推断出的那家。
    assertEquals("", other.model)
  }

  @Test
  fun `saved and legacy values beat build config defaults`() {
    val defaults = ProviderDefaults(LlmPreset.OpenAi, "https://dev.example/v1", "dev-model")
    val legacy =
      LegacyProviderSettings(
        preset = LlmPreset.OpenAi,
        baseUrl = "https://legacy.example/v1",
        model = "legacy-model",
        hasKey = false,
      )

    val fromLegacy =
      resolveProviderSettings(LlmPreset.OpenAi, ProviderOverrides(), legacy, false, false, defaults)
    assertEquals("https://legacy.example/v1", fromLegacy.baseUrl)
    assertEquals("legacy-model", fromLegacy.model)

    val fromSaved =
      resolveProviderSettings(
        LlmPreset.OpenAi,
        ProviderOverrides(baseUrl = "https://saved.example/v1", model = "saved-model"),
        legacy,
        false,
        false,
        defaults,
      )
    assertEquals("https://saved.example/v1", fromSaved.baseUrl)
    assertEquals("saved-model", fromSaved.model)
  }

  @Test
  fun `verified only applies to the untouched default combination`() {
    val base =
      resolveProviderSettings(
        LlmPreset.OpenCodeGo,
        ProviderOverrides(model = LlmPreset.OpenCodeGo.defaultModel),
        null,
        hasKey = true,
        enabled = true,
      )
    assertTrue(base.verified)

    assertFalse(base.copy(baseUrl = "https://gateway.example/v1").verified)
    assertFalse(base.copy(model = "glm-5.3").verified)
    assertFalse(base.copy(api = "openai-responses").verified)
    // 只有真机跑通过的那家带 verified 标记。
    assertFalse(
      resolveProviderSettings(LlmPreset.OpenAi, ProviderOverrides(), null, true, true).verified,
    )
  }

  @Test
  fun `activation requires a key`() {
    val noKey =
      resolveProviderSettings(
        LlmPreset.OpenAi,
        ProviderOverrides(model = "gpt-6-sol"),
        null,
        hasKey = false,
        enabled = false,
      )
    assertTrue(noKey.canEnable)
    assertFalse(noKey.canActivate)

    assertTrue(noKey.copy(hasKey = true).canActivate)
  }

  /**
   * 自定义提供商把端点清空后仍可能处于「启用」状态。运行时必须能挂载（空 baseURL 会让
   * dsh profile 直接失败），且不能把这家提供商的 key 发到回落后的另一个端点。
   */
  @Test
  fun `agent config stays mountable and drops the key when the endpoint is missing`() {
    val custom =
      resolveProviderSettings(LlmPreset.Custom, ProviderOverrides(), null, hasKey = true, enabled = true)
    assertFalse(custom.canEnable)

    val config = custom.toAgentConfig("must-never-be-sent")

    assertEquals(LlmPreset.default.baseUrl, config.baseUrl)
    assertEquals(LlmPreset.default.defaultModel, config.model)
    assertEquals(LlmPreset.default, config.preset)
    assertEquals("", config.apiKey)
  }

  @Test
  fun `dynamic model list declares only the selected model with resolved modality`() {
    assertEquals(
      """[{"id":"gpt-6-sol","input":["text","image"]}]""",
      llmModelsJson("gpt-6-sol", supportsImage = true),
    )
    assertEquals(
      """[{"id":"my-model","input":["text"]}]""",
      llmModelsJson("my-model", supportsImage = false),
    )
    // 空 id 回落到默认模型：运行时至少要有一个可挂载的模型。
    assertEquals(
      """[{"id":"deepseek-v4.1-flash","input":["text","image"]}]""",
      llmModelsJson(" ", supportsImage = true),
    )
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

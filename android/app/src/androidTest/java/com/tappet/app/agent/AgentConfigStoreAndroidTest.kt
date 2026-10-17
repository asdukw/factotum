package com.tappet.app.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tappet.app.data.crypto.SecretVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 存储层的真机回归：这些行为在 JVM 上测不了（SharedPreferences + Android Keystore）。
 *
 * 钉住四件事，它们任何一件写错都**不会编译失败**，只会静默丢配置 / 丢密钥 / 串 key：
 * 1. 旧版单端点配置只被它指向的那家继承，其它提供商借不到旧 key；
 * 2. 每家的密钥互相隔离；
 * 3. 「启用」是互斥的，且「全部停用」是一个显式状态（不会回落默认又把旧 key 用起来）；
 * 4. 「恢复默认设置」会把旧版键一起清掉（否则重置后旧端点又回来了）。
 */
@RunWith(AndroidJUnit4::class)
class AgentConfigStoreAndroidTest {
  private lateinit var context: Context

  private val settingsPrefsNames = listOf("tappet_agent_settings")
  private val secretPrefsNames =
    listOf("tappet_agent_secret") + LlmPreset.entries.map { "tappet_agent_secret-${it.id}" }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    clearStorage()
  }

  @After
  fun tearDown() {
    clearStorage()
  }

  private fun clearStorage() {
    (settingsPrefsNames + secretPrefsNames).forEach { name ->
      context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
  }

  private fun writeLegacy(baseUrl: String, model: String, preset: LlmPreset, key: String?) {
    context
      .getSharedPreferences("tappet_agent_settings", Context.MODE_PRIVATE)
      .edit()
      .putString("base_url", baseUrl)
      .putString("model", model)
      .putString("preset", preset.id)
      .commit()
    key?.let {
      SecretVault(prefsName = "tappet_agent_secret", keyAlias = "tappet-agent-llm-key-v1")
        .put(context, it)
    }
  }

  @Test
  fun legacyConfigIsInheritedOnlyByTheProviderItPointedAt() {
    writeLegacy(
      baseUrl = "https://legacy.example/v1",
      model = "legacy-model",
      preset = LlmPreset.Anthropic,
      key = "legacy-key",
    )

    val anthropic = AgentConfigStore.provider(context, LlmPreset.Anthropic)
    assertEquals("https://legacy.example/v1", anthropic.baseUrl)
    assertEquals("legacy-model", anthropic.model)
    assertTrue(anthropic.hasKey)

    val openAi = AgentConfigStore.provider(context, LlmPreset.OpenAi)
    assertFalse("别的提供商不能借旧 key", openAi.hasKey)
    assertEquals(LlmPreset.OpenAi.baseUrl, openAi.baseUrl)
    // 没有模型目录可回落：没选就是空。
    assertEquals("", openAi.model)
  }

  @Test
  fun keysAreIsolatedPerProvider() {
    assertTrue(AgentConfigStore.saveApiKey(context, LlmPreset.OpenAi, "openai-key"))

    assertTrue(AgentConfigStore.provider(context, LlmPreset.OpenAi).hasKey)
    assertFalse(AgentConfigStore.provider(context, LlmPreset.DeepSeek).hasKey)

    assertTrue(AgentConfigStore.clearApiKey(context, LlmPreset.OpenAi))
    assertFalse(AgentConfigStore.provider(context, LlmPreset.OpenAi).hasKey)
  }

  @Test
  fun remoteModelListIsPersistedPerProvider() {
    saveRemoteModels(context, LlmPreset.OpenAi, listOf("vendor/model-a", "vendor/model-b"))
    saveSelectedModels(context, LlmPreset.OpenAi, listOf("vendor/model-a"))

    val settings = AgentConfigStore.provider(context, LlmPreset.OpenAi)
    assertEquals(listOf("vendor/model-a", "vendor/model-b"), settings.remoteModels)
    assertEquals(listOf("vendor/model-a"), settings.selectedModels)
    assertTrue(AgentConfigStore.provider(context, LlmPreset.DeepSeek).remoteModels.isEmpty())

    // 坏数据当空，不拖垮设置页。
    context
      .getSharedPreferences("tappet_agent_settings", Context.MODE_PRIVATE)
      .edit()
      .putString("provider.openai.remote_models", "not-json")
      .commit()
    assertTrue(AgentConfigStore.provider(context, LlmPreset.OpenAi).remoteModels.isEmpty())
  }

  @Test
  fun enabledProviderIsExclusiveAndNoneIsExplicit() {
    assertEquals(defaultPresetFromBuildConfig(), AgentConfigStore.enabledPreset(context))

    assertTrue(AgentConfigStore.setEnabled(context, LlmPreset.OpenAi, true))
    assertEquals(LlmPreset.OpenAi, AgentConfigStore.enabledPreset(context))

    // 停用当前唯一启用的那家 = 显式「没有」；不能回落到默认提供商把旧 key 又用起来。
    assertTrue(AgentConfigStore.setEnabled(context, LlmPreset.OpenAi, false))
    assertNull(AgentConfigStore.enabledPreset(context))

    assertTrue(AgentConfigStore.setEnabled(context, LlmPreset.DeepSeek, true))
    assertEquals(LlmPreset.DeepSeek, AgentConfigStore.enabledPreset(context))
  }

  @Test
  fun resetRemovesTheLegacyFallbackAndTheKey() {
    writeLegacy(
      baseUrl = "https://gateway.corp/v1",
      model = "my-claude",
      preset = LlmPreset.Anthropic,
      key = "legacy-key",
    )

    assertTrue(AgentConfigStore.resetProvider(context, LlmPreset.Anthropic))

    val settings = AgentConfigStore.provider(context, LlmPreset.Anthropic)
    assertEquals(LlmPreset.Anthropic.baseUrl, settings.baseUrl)
    assertEquals("", settings.model)
    assertFalse(settings.hasKey)
    // 重置后没有模型 → 不可用，顺带停用，避免留下一个「启用但发不出请求」的提供商。
    assertFalse(settings.enabled)

    val prefs = context.getSharedPreferences("tappet_agent_settings", Context.MODE_PRIVATE)
    assertNull(prefs.getString("base_url", null))
    assertNull(prefs.getString("model", null))
    assertNull(prefs.getString("preset", null))
  }

  @Test
  fun currentFollowsTheEnabledProvider() {
    writeLegacy(
      baseUrl = LlmPreset.Anthropic.baseUrl,
      model = LlmPreset.Anthropic.defaultModel,
      preset = LlmPreset.Anthropic,
      key = "legacy-key",
    )
    AgentConfigStore.saveApiKey(context, LlmPreset.OpenAi, "openai-key")
    AgentConfigStore.saveProviderFields(
      context = context,
      preset = LlmPreset.OpenAi,
      name = LlmPreset.OpenAi.label,
      baseUrl = LlmPreset.OpenAi.baseUrl,
      model = "gpt-6-sol",
      api = LlmPreset.OpenAi.api,
    )
    AgentConfigStore.setEnabled(context, LlmPreset.OpenAi, true)

    val config = AgentConfigStore.current(context)
    assertEquals(LlmPreset.OpenAi, config.preset)
    assertEquals(LlmPreset.OpenAi.baseUrl, config.baseUrl)
    assertEquals("gpt-6-sol", config.model)
    assertEquals("openai-key", config.apiKey)
  }

  /** 自定义提供商端点被清空后仍可能处于启用状态：运行时必须可挂载，且不能带出密钥。 */
  @Test
  fun currentStaysMountableAndKeylessWhenTheEnabledProviderHasNoEndpoint() {
    AgentConfigStore.saveApiKey(context, LlmPreset.Custom, "custom-key")
    AgentConfigStore.setEnabled(context, LlmPreset.Custom, true)

    val config = AgentConfigStore.current(context)
    assertEquals(LlmPreset.default.baseUrl, config.baseUrl)
    assertEquals(LlmPreset.default.defaultModel, config.model)
    assertEquals("", config.apiKey)
  }
}

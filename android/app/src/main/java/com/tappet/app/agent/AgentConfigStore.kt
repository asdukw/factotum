package com.tappet.app.agent

import android.content.Context
import com.tappet.app.data.crypto.SecretVault

/**
 * Agent 端点配置的读取与落盘（服务商预选 / base URL / model / API key）。
 *
 * **优先级：设置页保存的值 > BuildConfig（仅端点与模型）> 内置默认值。**
 * BuildConfig 那一层只在开发机上非空（`android/local.properties` 注入）。
 *
 * **密钥只有一个来源：设置页。** 构建期那条注入路径已经去掉 —— 见 [AgentConfig] 的注释。
 * 密钥单独走 [SecretVault]（Android Keystore 封装）：**不进 SharedPreferences 明文、
 * 不进日志、不进云备份**。非密钥项（预选 / baseUrl / model）放普通 SharedPreferences 就够。
 */
internal object AgentConfigStore {
  private const val PREFS_NAME = "tappet_agent_settings"
  private const val KEY_BASE_URL = "base_url"
  private const val KEY_MODEL = "model"
  private const val KEY_PRESET = "preset"

  // prefs 名与别名一旦定下就别再改：改了等于换一把 Keystore 密钥，用户已存的 key 读不出来。
  private val vault =
    SecretVault(prefsName = "tappet_agent_secret", keyAlias = "tappet-agent-llm-key-v1")

  /** 当前生效的配置：设置页存的（若有）盖在构建期默认值上。 */
  fun current(context: Context): AgentConfig {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return resolveAgentConfig(
      defaults = AgentConfig.fromBuildConfig(),
      savedBaseUrl = prefs.getString(KEY_BASE_URL, null),
      savedModel = prefs.getString(KEY_MODEL, null),
      savedApiKey = vault.get(context),
      savedPreset = prefs.getString(KEY_PRESET, null),
    )
  }

  /** 设置页保存下来的原始值，供界面回填；没存过就是 null（界面该显示构建期默认值）。 */
  fun savedBaseUrl(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_BASE_URL, null)

  fun savedModel(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODEL, null)

  /** 存过的服务商预选 id；没存过（老版本升上来）返回 null，由 [resolveAgentConfig] 反推。 */
  fun savedPresetId(context: Context): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRESET, null)

  /** 设置页里是否存着密钥（只回答有没有，不返回密钥本体）。 */
  fun hasStoredApiKey(context: Context): Boolean = vault.has(context)

  /**
   * 保存设置页的输入。
   *
   * [apiKey] 传 **null = 这次不动密钥**（用户只改了 base URL），传**空串 = 清除密钥**。
   * 这个区分是刻意的：设置页把密钥输入框留空表示「不改」，而「删掉密钥」得是一个明确动作。
   *
   * 返回 false 表示非密钥项或密钥有一项没写进去。
   */
  fun save(
    context: Context,
    baseUrl: String,
    model: String,
    apiKey: String?,
    preset: LlmPreset,
  ): Boolean {
    val settingsSaved =
      context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        // 空串 = 移除该项，回落到 BuildConfig / 内置默认，而不是存一个空值把默认值顶掉。
        .putString(KEY_BASE_URL, baseUrl.trim().ifEmpty { null })
        .putString(KEY_MODEL, model.trim().ifEmpty { null })
        .putString(KEY_PRESET, preset.id)
        .commit()
    val secretSaved = apiKey?.let { vault.put(context, it.trim()) } ?: true
    return settingsSaved && secretSaved
  }

  /** 只清密钥，不动 base URL / model。 */
  fun clearApiKey(context: Context): Boolean = vault.clear(context)
}

/**
 * 配置解析里**纯函数的那一半**：抽出来是为了能在 JVM 单测里直接钉住优先级，
 * 不必起 Android。
 *
 * 规则：
 * - 设置页存的端点 / 模型只在**非空白**时才覆盖默认值 —— 存了个空串等于没配，
 *   不能把一个可用的默认值顶掉（否则清空输入框就会把端点打进不可用状态）。
 * - **密钥只认设置页存的**，默认值里那一份不再参与 —— 构建期注入已去掉。
 * - 预选（服务商）决定调用风格：存过就按存的；没存过（老版本升上来）按 base URL 反推；
 *   再推不出来算「自定义」。
 * - 代理与 reasoningEffort 不在设置页里，一律沿用默认值。
 */
internal fun resolveAgentConfig(
  defaults: AgentConfig,
  savedBaseUrl: String?,
  savedModel: String?,
  savedApiKey: String?,
  savedPreset: String? = null,
): AgentConfig {
  val baseUrl = savedBaseUrl.nonBlankOrNull() ?: defaults.baseUrl
  return AgentConfig(
    baseUrl = baseUrl,
    apiKey = savedApiKey.nonBlankOrNull().orEmpty(),
    model = savedModel.nonBlankOrNull() ?: defaults.model,
    reasoningEffort = defaults.reasoningEffort,
    proxyHost = defaults.proxyHost,
    proxyPort = defaults.proxyPort,
    preset = LlmPreset.byId(savedPreset) ?: LlmPreset.forBaseUrl(baseUrl) ?: LlmPreset.Custom,
  )
}

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

package com.zoneu.app.agent

import android.content.Context
import com.zoneu.app.data.crypto.SecretVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// --- 存储布局 ---
// 旧版单端点配置（只读，保留给升级用户）与新版按提供商的键都在同一个 prefs 文件里。
private const val PREFS_NAME = "zoneu_agent_settings"
private const val KEY_LEGACY_BASE_URL = "base_url"
private const val KEY_LEGACY_MODEL = "model"
private const val KEY_LEGACY_PRESET = "preset"
private const val KEY_ENABLED = "enabled_provider"

private const val SUFFIX_NAME = "name"
private const val SUFFIX_BASE_URL = "base_url"
private const val SUFFIX_MODEL = "model"
private const val SUFFIX_API = "api"

/** 端点 `GET /models` 拉回来的清单（JSON 数组字符串）。 */
private const val SUFFIX_REMOTE_MODELS = "remote_models"

/** 用户选进「可用模型」的清单（JSON 数组字符串）。 */
private const val SUFFIX_SELECTED_MODELS = "models"

// prefs 名与别名一旦定下就别再改：改了等于换一把 Keystore 密钥，用户已存的 key 读不出来。
private const val LEGACY_SECRET_PREFS = "zoneu_agent_secret"
private const val LEGACY_KEY_ALIAS = "zoneu-agent-llm-key-v1"

private val legacyVault = SecretVault(prefsName = LEGACY_SECRET_PREFS, keyAlias = LEGACY_KEY_ALIAS)

/** 每个提供商一把 Keystore 密钥。别名带着 [LlmPreset.id] —— id 是存储身份，不能改。 */
internal fun providerSecretPrefsName(preset: LlmPreset): String = "$LEGACY_SECRET_PREFS-${preset.id}"

internal fun providerKeyAlias(preset: LlmPreset): String = "$LEGACY_KEY_ALIAS-${preset.id}"

private fun providerVault(preset: LlmPreset): SecretVault =
  SecretVault(prefsName = providerSecretPrefsName(preset), keyAlias = providerKeyAlias(preset))

private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** 旧配置的等效值与旧密钥（读出来给「它当时指向的那个提供商」用）。 */
private data class LegacyState(val settings: LegacyProviderSettings?, val key: String?)

private fun readLegacyState(context: Context): LegacyState {
  val prefs = prefs(context)
  val baseUrl = prefs.getString(KEY_LEGACY_BASE_URL, null)
  val model = prefs.getString(KEY_LEGACY_MODEL, null)
  val presetId = prefs.getString(KEY_LEGACY_PRESET, null)
  val key = legacyVault.get(context)
  if (listOf(baseUrl, model, presetId, key).all { it == null }) {
    return LegacyState(settings = null, key = null)
  }
  val resolved =
    resolveAgentConfig(
      defaults = AgentConfig.fromBuildConfig(),
      savedBaseUrl = baseUrl,
      savedModel = model,
      savedApiKey = key,
      savedPreset = presetId,
    )
  return LegacyState(
    settings =
      LegacyProviderSettings(
        preset = resolved.preset,
        baseUrl = resolved.baseUrl,
        model = resolved.model,
        hasKey = key != null,
      ),
    key = key,
  )
}

private fun enabledFromStored(stored: String?, legacy: LegacyState): LlmPreset? =
  resolveEnabledPreset(
    stored = stored,
    fallback = legacy.settings?.preset ?: defaultPresetFromBuildConfig(),
  )

private fun apiKeyFor(context: Context, preset: LlmPreset, legacy: LegacyState): String {
  val own = providerVault(preset).get(context)
  if (!own.isNullOrBlank()) return own
  // 旧版的单 key 只属于升级前正在用的那家 —— 别的提供商不能借它。
  return if (legacy.settings?.preset == preset) legacy.key.orEmpty() else ""
}

private fun resolve(context: Context, preset: LlmPreset, legacy: LegacyState): ProviderSettings {
  val prefs = prefs(context)
  val prefix = providerPrefsPrefix(preset)
  val overrides =
    ProviderOverrides(
      name = prefs.getString(prefix + SUFFIX_NAME, null),
      baseUrl = prefs.getString(prefix + SUFFIX_BASE_URL, null),
      model = prefs.getString(prefix + SUFFIX_MODEL, null),
      api = prefs.getString(prefix + SUFFIX_API, null),
    )
  val enabled = enabledFromStored(prefs.getString(KEY_ENABLED, null), legacy) == preset
  val hasKey = apiKeyFor(context, preset, legacy).isNotBlank()
  val base =
    resolveProviderSettings(
      preset = preset,
      overrides = overrides,
      legacy = legacy.settings,
      hasKey = hasKey,
      enabled = enabled,
      defaults = providerDefaultsFromBuildConfig(),
    )
  // 升级用户没有「可用模型」列表：把当前模型当作唯一一项，界面与聊天页都能直接用。
  val selected =
    readModelList(context, preset, SUFFIX_SELECTED_MODELS).ifEmpty {
      if (base.model.isNotBlank()) listOf(base.model) else emptyList()
    }
  return base.copy(
    remoteModels = readModelList(context, preset, SUFFIX_REMOTE_MODELS),
    selectedModels = selected,
  )
}

/** 读一个模型 id 列表（JSON 数组）：只认字符串数组，坏数据当空。 */
private fun readModelList(context: Context, preset: LlmPreset, suffix: String): List<String> {
  val raw = prefs(context).getString(providerPrefsPrefix(preset) + suffix, null) ?: return emptyList()
  val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
  return array.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.filter { it.isNotBlank() }
}

private fun writeModelList(context: Context, preset: LlmPreset, suffix: String, ids: List<String>) {
  val json = buildJsonArray { ids.forEach { add(it) } }.toString()
  prefs(context)
    .edit()
    .putString(providerPrefsPrefix(preset) + suffix, json)
    .apply()
}

/** 落盘端点拉回来的清单（整份替换）。 */
internal fun saveRemoteModels(context: Context, preset: LlmPreset, ids: List<String>) =
  writeModelList(context, preset, SUFFIX_REMOTE_MODELS, ids)

/** 落盘「可用模型」清单（整份替换）。 */
internal fun saveSelectedModels(context: Context, preset: LlmPreset, ids: List<String>) =
  writeModelList(context, preset, SUFFIX_SELECTED_MODELS, ids)

/**
 * 设置当前模型，并确保它在「可用模型」里。
 * 传空串 = 清除当前模型（例如用户把它从可用列表里删掉且没有其它候选）。
 */
internal fun setModel(context: Context, preset: LlmPreset, model: String) {
  val settings = AgentConfigStore.provider(context, preset)
  prefs(context)
    .edit()
    .putString(providerPrefsPrefix(preset) + SUFFIX_MODEL, model.trim().ifEmpty { null })
    .apply()
  if (model.isNotBlank() && model !in settings.selectedModels) {
    saveSelectedModels(context, preset, settings.selectedModels + model)
  }
}

/**
 * **只给「发一次请求」的路径用**：取出这家提供商的明文密钥（端点 `/models` 的认证头）。
 * 调用方不得缓存、不得打印、不得写进任何状态。
 */
internal fun requestApiKey(context: Context, preset: LlmPreset): String =
  apiKeyFor(context, preset, readLegacyState(context))

/**
 * Agent 端点配置的读取与落盘（提供商 / base URL / model / 调用风格 / API key）。
 *
 * **优先级：设置页按提供商存的值 > 旧版单端点配置（仅同一个提供商）> BuildConfig（开发机）> 目录默认值。**
 *
 * 存储分两层：
 * - 非密钥项（名称 / base URL / model / 调用风格 / 启用的提供商）在普通 SharedPreferences；
 * - 密钥**每个提供商一份**，各自走 [SecretVault]（Android Keystore 封装）。密钥不进
 *   SharedPreferences 明文、不进日志、不进云备份、不回填到界面上。
 *
 * 旧版的单端点键（`base_url` / `model` / `preset`）与旧密钥别名**不迁移、不删除**：
 * 它们只对升级前正在用的那个提供商生效。这样老用户不用重填 key，而别的提供商也借不到它。
 * 反向也安全：装回旧版本时旧键仍在。
 *
 * **存储身份**：[providerPrefsPrefix] 与 `providerVault` 的别名都带着 [LlmPreset.id]。改 id
 * = 设备上已存的配置与密钥全部读不回来（与包名 / 数据库名 / 口令 prefs 同一类红线）。
 */
internal object AgentConfigStore {
  /** 当前生效的配置：运行时（NodeRuntime）与聊天页读的都是它。 */
  fun current(context: Context): AgentConfig {
    val legacy = readLegacyState(context)
    val enabled = enabledFromStored(prefs(context).getString(KEY_ENABLED, null), legacy)
    // 一个都没启用时仍给出可启动的端点 / 模型，但**不带密钥** —— 聊天页据此显示「未配置」，
    // 而不是悄悄用上一家的 key 继续发请求。
    val preset = enabled ?: legacy.settings?.preset ?: defaultPresetFromBuildConfig()
    val settings = resolve(context, preset, legacy)
    // 端点 / 模型被清空（典型是自定义提供商）时，运行时仍要能挂载：AgentConfig 会回落到
    // 目录默认组合；同时**不交出密钥**，避免把这家提供商的 key 发到另一个端点。
    val key = if (enabled == null || !settings.canEnable) "" else apiKeyFor(context, preset, legacy)
    return settings.toAgentConfig(key)
  }

  /** 提供商列表：目录里每一项 + 用户改过的字段。只读一次旧配置，避免逐个提供商解密。 */
  fun providers(context: Context): List<ProviderSettings> {
    val legacy = readLegacyState(context)
    return LlmPreset.entries.map { resolve(context, it, legacy) }
  }

  fun provider(context: Context, preset: LlmPreset): ProviderSettings =
    resolve(context, preset, readLegacyState(context))

  /** 启用中的提供商；null = 用户把所有都关了。 */
  fun enabledPreset(context: Context): LlmPreset? =
    enabledFromStored(prefs(context).getString(KEY_ENABLED, null), readLegacyState(context))

  fun hasStoredApiKey(context: Context, preset: LlmPreset): Boolean = provider(context, preset).hasKey

  /**
   * 保存非密钥字段。空白值写成「未设置」，于是回落到目录默认 —— 清空输入框不该把端点顶成空串。
   * 用 apply：这些字段在输入过程中会频繁写，值本身不重要到值得同步落盘。
   */
  fun saveProviderFields(
    context: Context,
    preset: LlmPreset,
    name: String,
    baseUrl: String,
    model: String,
    api: String,
  ) {
    val prefix = providerPrefsPrefix(preset)
    prefs(context)
      .edit()
      .putString(prefix + SUFFIX_NAME, name.trim().ifEmpty { null })
      .putString(prefix + SUFFIX_BASE_URL, baseUrl.trim().ifEmpty { null })
      .putString(prefix + SUFFIX_MODEL, model.trim().ifEmpty { null })
      .putString(prefix + SUFFIX_API, api.trim().ifEmpty { null })
      .apply()
  }

  /**
   * 启用 / 停用提供商。**同一时间只有一个**：启用它会停用其它提供商，停用它则变成
   * 「没有提供商」（显式存 [ENABLED_NONE]，不回落默认）。
   *
   * 这么设计不是界面偷懒：内嵌运行时每个进程只连一个端点（profile 里只有一条 provider 路由），
   * 多启用没有对应的运行语义。等「默认模型」页做出来再改。
   */
  fun setEnabled(context: Context, preset: LlmPreset, enabled: Boolean): Boolean {
    val prefs = prefs(context)
    if (enabled) return prefs.edit().putString(KEY_ENABLED, preset.id).commit()
    if (enabledPreset(context) != preset) return true
    return prefs.edit().putString(KEY_ENABLED, ENABLED_NONE).commit()
  }

  /** 写入一个提供商的密钥。空串表示无效输入 —— 删除请走 [clearApiKey]，别把「没填」当「删除」。 */
  fun saveApiKey(context: Context, preset: LlmPreset, key: String): Boolean {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return false
    return providerVault(preset).put(context, trimmed)
  }

  /** 只清这个提供商的密钥；如果它正是升级前那家，旧别名里的 key 也一并清掉。 */
  fun clearApiKey(context: Context, preset: LlmPreset): Boolean {
    val legacy = readLegacyState(context)
    val ownCleared = providerVault(preset).clear(context)
    val legacyCleared =
      if (legacy.settings?.preset == preset) legacyVault.clear(context) else true
    return ownCleared && legacyCleared
  }

  /** 恢复目录默认：清掉用户改过的字段与密钥（密钥提示写在界面的确认框里）。 */
  fun resetProvider(context: Context, preset: LlmPreset): Boolean {
    val legacy = readLegacyState(context)
    val prefix = providerPrefsPrefix(preset)
    prefs(context)
      .edit()
      .remove(prefix + SUFFIX_NAME)
      .remove(prefix + SUFFIX_BASE_URL)
      .remove(prefix + SUFFIX_MODEL)
      .remove(prefix + SUFFIX_API)
      .remove(prefix + SUFFIX_REMOTE_MODELS)
      .remove(prefix + SUFFIX_SELECTED_MODELS)
      .apply()
    val keyCleared = clearApiKey(context, preset)
    // 旧版单端点键是全局的，只在它指向的正是这家时删掉；否则重置后 legacy 仍会把旧端点
    // 顶回来，确认框承诺的「回到目录默认」就成了假话。
    if (legacy.settings?.preset == preset) {
      prefs(context)
        .edit()
        .remove(KEY_LEGACY_BASE_URL)
        .remove(KEY_LEGACY_MODEL)
        .remove(KEY_LEGACY_PRESET)
        .apply()
    }
    // 重置后如果连端点 / 模型都没了（典型是自定义提供商），就别让它继续「启用」着 ——
    // 那会让聊天页只报「缺 API Key」，掩盖真正缺的是端点。
    val settings = provider(context, preset)
    val disabledIfUnusable =
      if (settings.enabled && !settings.canEnable) setEnabled(context, preset, false) else true
    return keyCleared && disabledIfUnusable
  }
}

/**
 * 旧版配置解析里**纯函数的那一半**：抽出来是为了能在 JVM 单测里直接钉住优先级，
 * 不必起 Android。新版的按提供商解析见 [resolveProviderSettings]；这里仍被
 * [AgentConfigStore] 用来把旧键换算成「它当时指向的那个提供商」。
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

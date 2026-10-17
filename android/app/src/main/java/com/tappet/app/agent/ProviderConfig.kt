package com.tappet.app.agent

import com.tappet.app.BuildConfig
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一个提供商「当前生效」的配置：目录默认值 + 用户在设置页改过的字段。
 *
 * 提供商列表、详情页与运行时读的都是这一个类型。密钥**不在这里** —— 它只回答
 * [hasKey]（有没有），明文只经 `SecretVault` 在请求前取出，不进状态、不进日志。
 */
internal data class ProviderSettings(
  val preset: LlmPreset,
  val name: String,
  val baseUrl: String,
  val model: String,
  /** 实际使用的调用风格。OpenAI / 自定义可被「Response API」开关改成 openai-responses。 */
  val api: String,
  val hasKey: Boolean,
  val enabled: Boolean,
  /** 从端点 `GET /models` 拉回来并落盘的 id（可能很多）；只用来挑选，不改变模态声明。 */
  val remoteModels: List<String> = emptyList(),
  /** 用户选进「可用模型」的 id（来自端点列表或手填）；聊天页从这里挑。 */
  val selectedModels: List<String> = emptyList(),
) {
  /** 端点与模型都齐全 —— 运行时至少能挂载（不一定能请求成功）。 */
  val canEnable: Boolean
    get() = baseUrl.isNotBlank() && model.isNotBlank()

  /** 界面允许启用 / 「选中即启用」的前提：端点、模型、密钥三样都齐。 */
  val canActivate: Boolean
    get() = canEnable && hasKey

  /**
   * 「已验证」只对**目录默认组合**成立：[LlmPreset.verified] 记录的是「目录端点 + 默认模型
   * 在真机上跑通过」。用户改了端点 / 模型 / 调用风格之后，这个结论不再适用于当前配置。
   */
  val verified: Boolean
    get() =
      preset.verified &&
        baseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/') &&
        model == preset.defaultModel &&
        api == preset.api

  /**
   * 列表卡片上的「N 个模型」：用户选进「可用模型」的数量（当前模型也计入）。
   */
  val modelCount: Int
    get() = (selectedModels + listOfNotNull(model.nonBlankOrNull())).distinct().size
}

/** 用户改过的字段；null / 空白表示「跟随目录默认」。 */
internal data class ProviderOverrides(
  val name: String? = null,
  val baseUrl: String? = null,
  val model: String? = null,
  val api: String? = null,
)

/**
 * 开发机在 `local.properties` / `TAPPET_AGENT_*` 里给的端点与模型（BuildConfig）。
 * 只对**它推断出的那家提供商**生效，与旧版单端点配置同一条规则。
 */
internal data class ProviderDefaults(
  val preset: LlmPreset,
  val baseUrl: String,
  val model: String,
)

/**
 * 旧版单端点配置（`base_url` / `model` / `preset` + 一把旧密钥）的等效值。
 *
 * 升级到「多提供商」时不搬数据、不改写旧键：旧配置只对**它当时指向的那个提供商**生效，
 * 于是老用户不用重新填 key，其它提供商也借不到这把 key。
 */
internal data class LegacyProviderSettings(
  val preset: LlmPreset,
  val baseUrl: String,
  val model: String,
  val hasKey: Boolean,
)

/**
 * 纯函数解析：目录默认值 < BuildConfig（仅默认提供商）< 旧版单端点配置（仅同一个提供商）
 * < 用户在新界面改过的字段。
 *
 * 抽成纯函数是为了在 JVM 单测里钉住回落规则。这里写错不会报错，只会让「清空输入框」
 * 静默把端点顶成空串，或者让升级用户的 key 消失 —— 正是本仓库吃过的那种亏。
 */
internal fun resolveProviderSettings(
  preset: LlmPreset,
  overrides: ProviderOverrides,
  legacy: LegacyProviderSettings?,
  hasKey: Boolean,
  enabled: Boolean,
  defaults: ProviderDefaults? = null,
): ProviderSettings {
  val legacyMatch = legacy?.takeIf { it.preset == preset }
  val buildMatch = defaults?.takeIf { it.preset == preset }
  return ProviderSettings(
    preset = preset,
    name = overrides.name.nonBlankOrNull() ?: preset.label,
    baseUrl =
      overrides.baseUrl.nonBlankOrNull()
        ?: legacyMatch?.baseUrl?.nonBlankOrNull()
        ?: buildMatch?.baseUrl?.nonBlankOrNull()
        ?: preset.baseUrl,
    model =
      overrides.model.nonBlankOrNull()
        ?: legacyMatch?.model?.nonBlankOrNull()
        ?: buildMatch?.model?.nonBlankOrNull()
        // 没有模型目录可以回落：没选就是空，等用户从端点拉或手填。
        ?: "",
    api = overrides.api.nonBlankOrNull() ?: preset.api,
    hasKey = hasKey || legacyMatch?.hasKey == true,
    enabled = enabled,
  )
}

/** 「没有启用任何提供商」的显式值。null（从没存过）与它必须区分：前者回落到默认提供商。 */
internal const val ENABLED_NONE = "none"

/**
 * 解析启用中的提供商。
 *
 * - 从没存过 → 回落到 [fallback]（升级用户沿用旧配置指向的那家；新装是目录默认）。
 * - 存了 [ENABLED_NONE] → 真的没有（用户把所有都关了），不回落。
 * - 存了不认识的 id → 回落到 [fallback]（改名 / 手改 prefs 的兜底）。
 */
internal fun resolveEnabledPreset(stored: String?, fallback: LlmPreset): LlmPreset? =
  when (stored) {
    null -> fallback
    ENABLED_NONE -> null
    else -> LlmPreset.byId(stored) ?: fallback
  }

/** 新装 / 开发机 local.properties 注入的默认提供商。发布包里就是 [LlmPreset.default]。 */
internal fun defaultPresetFromBuildConfig(): LlmPreset = AgentConfig.fromBuildConfig().preset

/** 开发机 BuildConfig 的端点 / 模型，只在它是默认提供商时当回落层用。 */
internal fun providerDefaultsFromBuildConfig(): ProviderDefaults {
  val config = AgentConfig.fromBuildConfig()
  return ProviderDefaults(preset = config.preset, baseUrl = config.baseUrl, model = config.model)
}

/**
 * 把「当前生效的提供商」翻译成运行时用的 AgentConfig。密钥由调用方给出（可能为空）。
 *
 * **端点 / 模型为空时必须回落到一个可挂载的组合**：dsh profile 里 `baseURL` 的兜底是
 * `?? 默认值`，而空串不会触发 `??` —— 空的 `TAPPET_LLM_BASE_URL` 会让整个运行时 mount
 * 失败（内嵌 Node 与宿主同进程，报错就是 App 崩）。此时密钥也由调用方清空，不会把
 * 一个提供商的 key 发到另一个端点。
 */
internal fun ProviderSettings.toAgentConfig(apiKey: String): AgentConfig {
  val defaults = AgentConfig.fromBuildConfig()
  val mountable = canEnable
  val presetForRuntime = if (mountable) preset else LlmPreset.default
  return AgentConfig(
    baseUrl = if (mountable) baseUrl else LlmPreset.default.baseUrl,
    // 不可挂载时不交出密钥：避免把这家提供商的 key 发到回落后的另一个端点。
    apiKey = if (mountable) apiKey else "",
    model = if (mountable) model else LlmPreset.default.defaultModel,
    reasoningEffort = defaults.reasoningEffort,
    proxyHost = defaults.proxyHost,
    proxyPort = defaults.proxyPort,
    preset = presetForRuntime,
    apiOverride = if (mountable) api else LlmPreset.default.api,
  )
}

/**
 * 运行时 profile 的模型清单：**只声明当前选中的那一个模型**。
 *
 * 路由键 `tappet` 不是 pi-ai 目录里的 provider，适配器不会从安装目录补模型 —— 未声明的 id
 * 会以 UNKNOWN_MODEL 失败（不是「退化成纯文本」）。所以清单必须由宿主在启动时按选中模型生成，
 * 否则设置页的手填模型 / 自定义提供商全都不可用。
 *
 * `input` 由调用方用 [ModelModalities] 判断（pi-ai 目录 → 跨 provider 一致条目 → 界面目录 →
 * false）；判断不出来的 id 一律 `[text]`，读图会被 pi-ai 明确拒绝。
 */
internal fun llmModelsJson(modelId: String, supportsImage: Boolean): String {
  val id = modelId.nonBlankOrNull() ?: LlmPreset.default.defaultModel
  return buildJsonArray {
    add(
      buildJsonObject {
        put("id", id)
        put(
          "input",
          buildJsonArray {
            add("text")
            if (supportsImage) add("image")
          },
        )
      },
    )
  }
    .toString()
}

/**
 * 提供商的存储身份。`provider.<id>.*` 与 Keystore 别名都带着它 —— id 改了，设备上已存的
 * 配置和密钥就读不回来。与包名 / 数据库名 / 口令 prefs 属于同一类「改名红线」。
 */
internal fun providerPrefsPrefix(preset: LlmPreset): String = "provider.${preset.id}."

internal fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** 构建期版本号，设置页「关于」用；抽出来免得界面直接碰 BuildConfig。 */
internal fun appVersionName(): String = BuildConfig.VERSION_NAME

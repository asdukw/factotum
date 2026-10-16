package com.tappet.app.agent

import com.tappet.app.BuildConfig

/**
 * Agent 端点配置。
 *
 * 端点的**开发机默认值**在编译期写死，来源优先级：
 * 环境变量 `TAPPET_AGENT_*` > `android/local.properties`（已 gitignore）> 内置默认值。
 *
 * **API key 不在这条路径上**：构建期注入已经整个去掉（`AGENT_API_KEY` 这个 BuildConfig 字段
 * 不存在了），密钥只有一个来源 —— 设置页，存进 `data/crypto/SecretVault`（Keystore 封装）。
 * 理由是 BuildConfig 是编译期常量、会以明文进 dex：只要密钥**能**被编进包，
 * 「本机构建的包不能发出去」就永远是一条要人记住的规矩，而不是一条结构性的事实。
 *
 * 密钥只用于请求头，绝不写进日志、报告或数据库。
 */
data class AgentConfig(
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String?,
  val proxyHost: String?,
  val proxyPort: Int,
  /** 端点属于哪家服务商：决定调用风格、推理格式与是否需要 zen 的会话头。 */
  val preset: LlmPreset,
) {
  /** pi-ai 的调用风格，随 [preset] 走（见 [LlmPreset] 里为什么不能一律按 OpenAI 兼容处理）。 */
  val api: String
    get() = preset.api

  val isConfigured: Boolean
    get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

  /** 允许只填到 `/v1`，也允许直接填完整的 `/chat/completions` 端点。 */
  val chatCompletionsUrl: String
    get() {
      val trimmed = baseUrl.trim().trimEnd('/')
      return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

  /** 只用于日志展示，不含密钥本体。 */
  fun describe(): String =
    "provider=${preset.label}, api=$api, model=$model, reasoning=${reasoningEffort ?: "默认"}, " +
      "endpoint=$baseUrl, key=" + if (apiKey.isBlank()) "未配置" else "已配置(${apiKey.length}字符)"

  /** 顶栏用的短摘要，同样不含密钥本体。 */
  fun shortSummary(): String =
    "$model · ${reasoningEffort ?: "默认"}" + if (apiKey.isBlank()) " · 缺 API key" else ""

  companion object {
    fun fromBuildConfig(): AgentConfig =
      AgentConfig(
        baseUrl = BuildConfig.AGENT_BASE_URL,
        // 空串是**结构性的**，不是「这次没配」：这里没有、也不该有构建期的密钥。
        apiKey = "",
        model = BuildConfig.AGENT_MODEL,
        reasoningEffort = BuildConfig.AGENT_REASONING_EFFORT.trim().ifBlank { null },
        proxyHost = BuildConfig.AGENT_PROXY_HOST.trim().ifBlank { null },
        proxyPort = BuildConfig.AGENT_PROXY_PORT,
        // 开发机在 local.properties 里把端点写成了哪家，就按哪家的风格调；认不出来算自定义。
        preset = LlmPreset.forBaseUrl(BuildConfig.AGENT_BASE_URL) ?: LlmPreset.Custom,
      )
  }
}

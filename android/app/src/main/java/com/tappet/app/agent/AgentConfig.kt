package com.tappet.app.agent

import com.tappet.app.BuildConfig

/**
 * Agent 端点配置。
 *
 * 测试阶段的值在编译期写死，来源优先级：
 * 环境变量 `TAPPET_AGENT_*` > `android/local.properties`（已 gitignore）> 内置默认值。
 *
 * 后续会由设置页替换这里（用户填 base URL / API key，密钥进加密存储）。
 * 注意：apiKey 只用于请求头，绝不写进日志、报告或数据库。
 */
data class AgentConfig(
  val baseUrl: String,
  val apiKey: String,
  val model: String,
  val reasoningEffort: String?,
  val proxyHost: String?,
  val proxyPort: Int,
) {
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
    "model=$model, reasoning=${reasoningEffort ?: "默认"}, endpoint=$chatCompletionsUrl, key=" +
      if (apiKey.isBlank()) "未配置" else "已配置(${apiKey.length}字符)"

  /** 顶栏用的短摘要，同样不含密钥本体。 */
  fun shortSummary(): String =
    "$model · ${reasoningEffort ?: "默认"}" + if (apiKey.isBlank()) " · 缺 API key" else ""

  companion object {
    fun fromBuildConfig(): AgentConfig =
      AgentConfig(
        baseUrl = BuildConfig.AGENT_BASE_URL,
        apiKey = BuildConfig.AGENT_API_KEY,
        model = BuildConfig.AGENT_MODEL,
        reasoningEffort = BuildConfig.AGENT_REASONING_EFFORT.trim().ifBlank { null },
        proxyHost = BuildConfig.AGENT_PROXY_HOST.trim().ifBlank { null },
        proxyPort = BuildConfig.AGENT_PROXY_PORT,
      )
  }
}
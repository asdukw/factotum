package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 从端点拉模型列表（`GET {base}/models`）。
 *
 * 为什么值得做：pi-ai 目录里的模型是**抄下来的一份**，端点自己新增 / 下线的模型它不知道；
 * 自定义网关更是完全不在目录里。列表只用来**挑一个 id**，选中之后仍然走
 * [llmModelsJson] 的动态声明（目录外的 id 一律 text，读图会被拒）。
 *
 * 只发一个 GET，带这家提供商的密钥；密钥不打印、不缓存（调用方取完就用）。
 * 解析只认最常见的两种形状：`{"data":[{"id":...}]}`（OpenAI 兼容 / Anthropic）与顶层数组。
 */
internal interface ModelListFetcher {
  suspend fun fetch(settings: ProviderSettings, apiKey: String): ModelListResult
}

internal data class ModelListResult(val ids: List<String> = emptyList(), val error: String? = null)

/**
 * `GET {base}/models`。base 只填到 `/v1` 或根都可以：
 * - `https://api.openai.com/v1` → `/v1/models`
 * - `https://api.anthropic.com` → `/v1/models`（Anthropic 的 models 在 /v1 下）
 * - `https://openrouter.ai/api/v1` → `/api/v1/models`
 * 已经是 `/models` 的直接用。
 */
internal fun modelsUrl(baseUrl: String, api: String): String? {
  val trimmed = baseUrl.trim().trimEnd('/')
  if (trimmed.isEmpty()) return null
  if (trimmed.endsWith("/models")) return trimmed
  val anthropic = api == "anthropic-messages"
  return if (anthropic && !trimmed.endsWith("/v1")) "$trimmed/v1/models" else "$trimmed/models"
}

/**
 * 认证头：Anthropic 走 `x-api-key` + 版本头，其余走 Bearer。opencode zen 的
 * `x-opencode-session` 是它的硬要求，/models 也带上（值只用作会话标签，与 profile 的
 * 兜底一致）。
 */
internal fun modelListHeaders(
  api: String,
  apiKey: String,
  zenSession: Boolean,
): Map<String, String> {
  val headers = LinkedHashMap<String, String>()
  if (api == "anthropic-messages") {
    headers["x-api-key"] = apiKey
    headers["anthropic-version"] = "2023-06-01"
  } else {
    headers["Authorization"] = "Bearer $apiKey"
  }
  if (zenSession) headers["x-opencode-session"] = ZEN_SESSION_FALLBACK
  return headers
}

/** 与 profile 里 `ZONEU_LLM_SESSION ?? 'zoneu-mobile'` 的兜底一致。 */
internal const val ZEN_SESSION_FALLBACK = "zoneu-mobile"

/** 端点返回的 id：去重、去空白、按字母序。认不出来就返回空（界面会报「没有返回模型」）。 */
internal fun parseModelIds(body: String): List<String> {
  val root =
    runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
  val entries: JsonArray =
    when (root) {
      is JsonArray -> root
      is JsonObject -> root["data"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
      else -> return emptyList()
    }
  return entries
    .mapNotNull { entry ->
      when (entry) {
        is JsonNull -> null
        is JsonPrimitive -> entry.contentOrNullSafe()
        is JsonObject -> entry["id"]?.let { runCatching { it.jsonPrimitive }.getOrNull() }?.contentOrNullSafe()
        else -> null
      }
    }
    .filter { it.isNotBlank() }
    .distinct()
    .sortedBy { it.lowercase() }
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
  runCatching { content }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/** OkHttp 实现。超时短一点：这是设置页里的交互动作，卡住不如报错。 */
internal class HttpModelListFetcher(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build(),
) : ModelListFetcher {
  override suspend fun fetch(settings: ProviderSettings, apiKey: String): ModelListResult {
    val url = modelsUrl(settings.baseUrl, settings.api) ?: return ModelListResult(error = "还没有填 API Base Url")
    if (apiKey.isBlank()) return ModelListResult(error = "先保存 API Key，再获取模型列表")

    val request =
      Request.Builder()
        .url(url)
        .get()
        .apply {
          modelListHeaders(settings.api, apiKey, settings.preset.zenSessionHeader)
            .forEach { (name, value) -> header(name, value) }
        }
        .build()

    return runCatching {
      client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          // 不回显响应体：网关的错误页里可能带请求信息。
          ModelListResult(error = "端点返回 HTTP ${response.code}")
        } else {
          val ids = parseModelIds(body)
          if (ids.isEmpty()) ModelListResult(error = "端点没有返回可识别的模型列表") else ModelListResult(ids = ids)
        }
      }
    }
      .getOrElse { throwable -> ModelListResult(error = "请求失败：${throwable.message ?: "未知错误"}") }
  }
}

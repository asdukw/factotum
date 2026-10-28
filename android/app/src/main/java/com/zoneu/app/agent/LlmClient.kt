package com.zoneu.app.agent

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 流式响应事件。推理内容单独成一路，避免混进正文。 */
sealed interface AgentStreamEvent {
  /**
   * 正文增量。[startsStep] 为真表示这是某一步的第一个增量 —— 界面据此记下「本步起点」，
   * 好在 [StepFinal] 到达时只替换**这一步**追加的文本（多步回答的前几步不能被吃掉）。
   */
  data class TextDelta(val text: String, val startsStep: Boolean = false) : AgentStreamEvent

  /** 推理增量；[startsStep] 含义同 [TextDelta]。 */
  data class ReasoningDelta(val text: String, val startsStep: Boolean = false) : AgentStreamEvent

  /**
   * 一步的最终成品（dsh 的 `assistant/message`）。
   *
   * 若这一步之前收到过增量，界面用它**替换本步**追加的文本：增量只是为了让人看见过程，
   * 落库与最终展示以这条为准（两边的差可能来自流式分片边界与最终组装）。没收到过增量时
   * 不会发这条 —— 走老的 [TextDelta] 追加路径，于是「运行时没打补丁」时行为与从前一致。
   */
  data class StepFinal(val text: String?, val reasoning: String?) : AgentStreamEvent

  /** 阶段性状态（dsh 事件映射用）；会替换正文显示，直到下一个 [TextDelta]。 */
  data class Status(val text: String) : AgentStreamEvent

  /** 模型请求执行一次工具（dsh 事件映射用）；UI 据此画工具卡。 */
  data class ToolCallStarted(val callId: String?, val name: String, val arguments: String) : AgentStreamEvent

  /** 工具执行完成（dsh 事件映射用）；[ok] 为 false 表示这次调用失败。 */
  data class ToolCallFinished(val callId: String?, val ok: Boolean, val result: String?) : AgentStreamEvent

  data object Completed : AgentStreamEvent
}

/** 端侧可展示的 Agent 错误。 */
class AgentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * OpenAI 兼容的 `/chat/completions` 流式客户端。
 *
 * 协议要点（实测 opencode.ai/zen/go/v1）：
 * - 必须带 `Authorization: Bearer <key>` 与 `x-opencode-session`，缺 session 会返回 MissingSessionID
 * - 响应是 SSE：`data: {...}` 逐块下发，最后一块是 `data: [DONE]`
 * - 推理模型会先发 `delta.reasoning_content`，正文在 `delta.content`
 *
 * 日志只记事件计数与长度，绝不记录正文、请求头或密钥。
 */
class LlmClient(
  private val config: AgentConfig,
  private val client: OkHttpClient = defaultClient(config),
  private val sessionId: String = "zoneu-" + UUID.randomUUID(),
) {
  // 已暂停的手写链路：不再实现 ChatStreamer（UI 走 DshStreamer），保留作参考。
  fun stream(history: List<ChatMessage>): Flow<AgentStreamEvent> =
    callbackFlow {
      if (!config.isConfigured) {
        close(AgentException("Agent 未配置：缺少 API key（聊天页抽屉 → 设置，填自己的 key）"))
        return@callbackFlow
      }

      val call = client.newCall(buildRequest(history))
      Log.d(TAG, "stream start model=${config.model} messages=${history.size}")

      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            Log.d(TAG, "stream failed: ${e.javaClass.simpleName}")
            close(AgentException("连接失败：${e.message ?: e.javaClass.simpleName}", e))
          }

          override fun onResponse(call: Call, response: Response) {
            Log.d(TAG, "response code=${response.code}")
            readStream(call, response)
          }
        },
      )

      awaitClose { call.cancel() }
    }
      .buffer(Channel.UNLIMITED)

  /**
   * 注意：这里刻意用 ProducerScope 作接收者（而不是 Response），
   * 否则无参 `close()` 会解析成 `Response.close()`，流永远不会结束。
   */
  private fun ProducerScope<AgentStreamEvent>.readStream(call: Call, response: Response) {
    if (!response.isSuccessful) {
      val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
      close(AgentException("HTTP ${response.code}：${errorMessage(raw)}"))
      return
    }

    val source = response.body?.source()
    if (source == null) {
      close(AgentException("响应没有内容"))
      return
    }

    var textChunks = 0
    var reasoningChunks = 0
    try {
      response.use {
        while (!call.isCanceled()) {
          val line = source.readUtf8Line() ?: break
          val payload = ssePayload(line) ?: continue
          if (payload == SSE_DONE) {
            Log.d(TAG, "got [DONE]")
            break
          }
          when (val event = parseChunk(payload)) {
            is AgentStreamEvent.TextDelta -> {
              textChunks++
              trySend(event)
            }

            is AgentStreamEvent.ReasoningDelta -> {
              reasoningChunks++
              trySend(event)
            }

            else -> Unit
          }
        }
      }
      Log.d(TAG, "stream done text_chunks=$textChunks reasoning_chunks=$reasoningChunks")
      close()
    } catch (e: IOException) {
      if (call.isCanceled()) close() else close(AgentException("读取流失败：${e.message}", e))
    }
  }

  private fun buildRequest(history: List<ChatMessage>): Request {
    val body =
      buildJsonObject {
        put("model", config.model)
        put("stream", true)
        config.reasoningEffort?.let { put("reasoning_effort", it) }
        put(
          "messages",
          buildJsonArray {
            addJsonObject {
              put("role", "system")
              put("content", SYSTEM_PROMPT)
            }
            history
              .filter { it.error == null && it.content.isNotBlank() }
              .forEach { message ->
                addJsonObject {
                  put("role", if (message.role == ChatRole.User) "user" else "assistant")
                  put("content", message.content)
                }
              }
          },
        )
      }
        .toString()

    return Request.Builder()
      .url(config.chatCompletionsUrl)
      .header("Authorization", "Bearer ${config.apiKey}")
      .header("x-opencode-session", sessionId)
      .header("Accept", "text/event-stream")
      .post(body.toRequestBody(JSON_MEDIA_TYPE))
      .build()
  }

  /** 只取 `data:` 行；忽略注释、事件名与心跳空行。 */
  private fun ssePayload(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith(SSE_DATA_PREFIX)) return null
    return trimmed.removePrefix(SSE_DATA_PREFIX).trim().ifEmpty { null }
  }

  private fun parseChunk(payload: String): AgentStreamEvent? {
    val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
    val delta =
      root["choices"]
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.firstOrNull()
        ?.let { runCatching { it.jsonObject["delta"]?.jsonObject }.getOrNull() }
        ?: return null

    delta.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { return AgentStreamEvent.ReasoningDelta(it) }
    delta.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let { return AgentStreamEvent.TextDelta(it) }
    return null
  }

  private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
  }

  /** 从错误响应里抠一句人能看的话，且永不回显请求头。 */
  private fun errorMessage(raw: String): String {
    val parsed =
      runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        root.stringOrNull("message")
          ?: root["error"]?.let { runCatching { it.jsonObject.stringOrNull("message") }.getOrNull() }
      }
        .getOrNull()
    return (parsed ?: raw).trim().replace(Regex("\\s+"), " ").take(300).ifEmpty { "无响应内容" }
  }

  companion object {
    private const val TAG = "ZoneuAgent"

    private const val SSE_DATA_PREFIX = "data:"

    private const val SSE_DONE = "[DONE]"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true }

    private val SYSTEM_PROMPT =
      """
      你是 Zoneu，一个常驻在用户手机上的私人助理。
      你了解用户的日常记录（通知、聊天、出行、睡眠等），目标是长期陪伴并给出贴合用户本人的建议。
      回答保持自然、简洁、口语化；不确定的事情直说，不要编造用户的数据。
      """
        .trimIndent()

    fun defaultClient(config: AgentConfig): OkHttpClient {
      val builder =
        OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          // 单次读超时：流式响应正常时块间隔远小于它，只在连接僵死时兜底
          .readTimeout(120, TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)

      config.proxyHost?.let { host ->
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, config.proxyPort)))
      }
      return builder.build()
    }
  }
}

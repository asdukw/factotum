package com.tappet.app.node

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * dsh SDK 通知的领域事件（只保留 UI / 调试需要的部分）。
 * 解析是纯函数（[DshProtocol.parse]），便于 JVM 单测。
 */
sealed interface DshEvent {
  val sessionId: String

  /** 复制事件并替换 [sessionId]；NodeRuntime 用它把 dsh 内部会话 id 映射回调用方 id。 */
  fun withSessionId(sessionId: String): DshEvent

  /**
   * 一个 step 组装完成的助手消息。
   *
   * dsh 自己只发这个粒度；token 级增量由 Tappet 的运行时补丁转发（见 [StreamDelta] 与
   * `tools/dsh-phone/patch-runtime.mjs` 补丁 4）。[reasoning] 是同一 step 的推理原文
   * （content 里 type=reasoning 的 block），无推理时为 null。
   */
  data class AssistantMessage(override val sessionId: String, val text: String, val reasoning: String? = null) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }

  /**
   * 模型请求执行一次工具。[arguments] 是模型产出的原始 JSON 字符串；
   * [callId] 与后续的 [ToolResult] 对上（极少数帧缺失，此时为 null）。
   */
  data class ToolCall(
    override val sessionId: String,
    val callId: String?,
    val name: String,
    val arguments: String,
  ) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }

  /** 工具执行完成；[text] 是给模型看的结果正文，截断前只用于工具卡展示。 */
  data class ToolResult(
    override val sessionId: String,
    val callId: String?,
    val isError: Boolean,
    val text: String?,
  ) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }

  /**
   * turn 结束；[error] 非空表示这一轮以错误结束（provider 原文），[code] 是它的失败代码
   * （如 `QUOTA`）。模型侧失败走这里，不会以异常的形式从 RPC 回来。
   */
  data class TurnEnded(
    override val sessionId: String,
    val error: String?,
    val code: String? = null,
  ) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }

  /**
   * token 级增量（Tappet 运行时补丁转发，`session.stream`）。[text] 与 [reasoning] 只会有一个非空。
   *
   * 只转发正文与推理两类 chunk；`tool-call-delta` 之类对 UI 没有意义，直接丢。
   */
  data class StreamDelta(
    override val sessionId: String,
    val text: String? = null,
    val reasoning: String? = null,
  ) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }

  /** 整个会话的 running / idle 流转。 */
  data class Status(override val sessionId: String, val running: Boolean) : DshEvent {
    override fun withSessionId(sessionId: String) = copy(sessionId = sessionId)
  }
}

object DshProtocol {
  /** 解析一条 JSON-RPC 通知帧；不是 session.event / session.status 就返回 null。 */
  fun parse(frame: JsonObject): DshEvent? {
    val method = frame["method"]?.jsonPrimitive?.contentOrNull ?: return null
    val params = frame["params"]?.jsonObject ?: return null
    val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (method) {
      "session.status" ->
        when (params["status"]?.jsonPrimitive?.contentOrNull) {
          "running" -> DshEvent.Status(sessionId, running = true)
          "idle" -> DshEvent.Status(sessionId, running = false)
          else -> null
        }

      "session.event" -> parseSessionEvent(sessionId, params["event"]?.jsonObject ?: return null)

      // Tappet 运行时补丁转发（tools/dsh-phone/patch-runtime.mjs 补丁 4）：token 级增量
      "session.stream" -> parseStreamFrame(sessionId, params["chunk"]?.jsonObject ?: return null)

      else -> null
    }
  }

  /**
   * 把 turn/end 的失败翻成用户能看懂的一句话。未知代码原样带出 provider 的消息 ——
   * 界面停在「正在启动 dsh 运行时…」比一句难看的报错坏得多。
   */
  fun turnErrorText(code: String?, message: String?): String {
    val raw = message?.trim().takeUnless { it.isNullOrEmpty() } ?: "模型调用失败（未返回原因）"
    return when (code?.uppercase()) {
      "QUOTA" -> "模型额度已用尽：$raw"
      else -> raw
    }
  }

  /** token 级增量帧。只认正文与推理；其它 chunk（工具参数增量、block 边界）对 UI 没有意义。 */
  private fun parseStreamFrame(sessionId: String, chunk: JsonObject): DshEvent? {
    val text = chunk["text"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (chunk["type"]?.jsonPrimitive?.contentOrNull) {
      "text-delta" -> DshEvent.StreamDelta(sessionId, text = text)
      "reasoning-delta" -> DshEvent.StreamDelta(sessionId, reasoning = text)
      else -> null
    }
  }

  private fun parseSessionEvent(sessionId: String, event: JsonObject): DshEvent? {
    val data = event["data"]?.jsonObject
    return when (event["type"]?.jsonPrimitive?.contentOrNull) {
      "assistant/message" -> {
        val content = data?.get("message")?.jsonObject?.get("content")?.jsonArray ?: return null
        val text =
          content.joinToString("") { block ->
            val item = block.jsonObject
            if (item["type"]?.jsonPrimitive?.contentOrNull == "text") {
              item["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            } else {
              ""
            }
          }
        val reasoning =
          content
            .mapNotNull { block ->
              val item = block.jsonObject
              if (item["type"]?.jsonPrimitive?.contentOrNull == "reasoning") {
                item["text"]?.jsonPrimitive?.contentOrNull
              } else {
                null
              }
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        DshEvent.AssistantMessage(sessionId, text, reasoning.ifBlank { null })
      }

      "tool/call" ->
        DshEvent.ToolCall(
          sessionId = sessionId,
          callId = data?.string("callId"),
          name = data?.string("name") ?: "tool",
          arguments = data?.string("arguments").orEmpty(),
        )

      "tool/result" -> {
        val message = data?.get("message")?.jsonObject
        DshEvent.ToolResult(
          sessionId = sessionId,
          callId = message?.string("toolCallId"),
          isError = message?.get("isError")?.jsonPrimitive?.booleanOrNull == true,
          text = message?.contentText(),
        )
      }

      "turn/end" -> {
        val reason = data?.get("reason")?.jsonObject
        if (reason?.get("kind")?.jsonPrimitive?.contentOrNull != "error") {
          DshEvent.TurnEnded(sessionId, null)
        } else {
          // reason.error = {message, code}；结构变了就退回整个 reason —— 宁可难看，不能吞掉。
          val failure = (reason["error"] ?: reason["failure"]) as? JsonObject
          val message = failure?.get("message")?.jsonPrimitive?.contentOrNull
          val code = failure?.get("code")?.jsonPrimitive?.contentOrNull
          DshEvent.TurnEnded(sessionId, message ?: reason.toString(), code)
        }
      }

      else -> null
    }
  }

  /** 字符串字段；非字符串（对象 / 数组）退化成原始 JSON 文本，不丢信息。 */
  private fun JsonObject.string(key: String): String? {
    val value = get(key) ?: return null
    return if (value is JsonPrimitive) value.contentOrNull ?: value.toString() else value.toString()
  }

  /** 工具结果正文：把所有 text block 拼起来（图片等其它 block 忽略）。 */
  private fun JsonObject.contentText(): String? {
    val blocks = get("content")?.jsonArray ?: return null
    val text =
      blocks
        .mapNotNull { block ->
          val item = block.jsonObject
          if (item["type"]?.jsonPrimitive?.contentOrNull == "text") {
            item["text"]?.jsonPrimitive?.contentOrNull
          } else {
            null
          }
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return text.ifBlank { null }
  }
}

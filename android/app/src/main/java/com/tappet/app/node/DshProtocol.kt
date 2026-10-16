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
   * 一个 step 组装完成的助手消息（dsh 不发 token 级增量）。
   * [reasoning] 是同一 step 的推理原文（content 里 type=reasoning 的 block），无推理时为 null。
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

  /** turn 结束；[error] 非空表示这一轮以错误结束。 */
  data class TurnEnded(override val sessionId: String, val error: String?) : DshEvent {
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
        val error = if (reason?.get("kind")?.jsonPrimitive?.contentOrNull == "error") reason.toString() else null
        DshEvent.TurnEnded(sessionId, error)
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

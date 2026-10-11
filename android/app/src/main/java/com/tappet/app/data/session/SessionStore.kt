package com.tappet.app.data.session

import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.db.ToolCallStatus
import kotlinx.coroutines.flow.Flow

/** 会话的对外视图（不含 summary 等内部字段）。 */
data class ChatSession(
  val id: String,
  val title: String?,
  val kind: SessionKind,
  val createdAt: Long,
  val updatedAt: Long,
)

/** 一条已落库的消息。 */
data class StoredMessage(
  val id: String,
  val sessionId: String,
  val seq: Long,
  val role: MessageRole,
  val content: String,
  val reasoning: String?,
  val status: MessageStatus,
  val error: String?,
  val createdAt: Long,
  val finishedAt: Long?,
)

/** 一次已落库的工具调用（挂在发起它的那条助手消息上）。 */
data class StoredToolCall(
  val id: String,
  val sessionId: String,
  val messageId: String,
  val name: String,
  val arguments: String,
  val result: String?,
  val status: ToolCallStatus,
  val startedAt: Long,
  val finishedAt: Long?,
)

/**
 * 会话存储的窄接口。
 *
 * 抽成接口是为了让 ViewModel 的单测能跑在纯 JVM 上 —— 真实实现走 Room + SQLCipher，
 * 而 SQLCipher 的原生库在 PC 的 JVM 里不存在。
 */
interface SessionStore {
  /** 最近一个聊天会话；没有就新建一个。 */
  suspend fun currentSession(): ChatSession

  suspend fun createSession(title: String?): ChatSession

  /** 全部会话，最近更新在前；用于原生会话列表。 */
  fun observeSessions(): Flow<List<ChatSession>>

  /** 删除会话，级联带走它的消息与工具调用。 */
  suspend fun deleteSession(id: String)

  suspend fun messages(sessionId: String): List<StoredMessage>

  suspend fun appendMessage(
    sessionId: String,
    role: MessageRole,
    content: String,
    status: MessageStatus,
    reasoning: String? = null,
  ): StoredMessage

  suspend fun updateMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    error: String?,
    finishedAt: Long?,
  )

  /** 第一条用户消息写下去时顺手起个标题；已经有标题就不动。 */
  suspend fun setTitleIfEmpty(sessionId: String, title: String)

  /** 记录一次工具调用的开始；[id] 用 dsh 的 callId，缺失时由调用方生成。 */
  suspend fun appendToolCall(
    id: String,
    sessionId: String,
    messageId: String,
    name: String,
    arguments: String,
  ): StoredToolCall

  /** 工具调用收尾（成功、失败或中断）。 */
  suspend fun finishToolCall(id: String, result: String?, status: ToolCallStatus)

  /** 某个会话的全部工具调用；调用方按 messageId 归到各自的消息上。 */
  suspend fun toolCalls(sessionId: String): List<StoredToolCall>

  /**
   * 进程启动时把残留的 [MessageStatus.Streaming] 改判为 [MessageStatus.Interrupted]。
   * 返回改动条数。
   */
  suspend fun markStreamingAsInterrupted(): Int
}

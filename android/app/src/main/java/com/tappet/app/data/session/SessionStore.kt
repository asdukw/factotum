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
  /** 酒馆会话绑定的角色卡；日常会话为 null。 */
  val characterId: String? = null,
  /**
   * 这段会话上次用的模型 id；没选过（或一直跟着全局走）为 null。
   *
   * 运行时同一时刻只有一个模型，所以这不是「每个会话并行用不同模型」，而是「回到这段
   * 会话时把当时那个模型装回去」。存下来才有意义 —— 否则酒馆用便宜模型、日常用贵模型
   * 这件事每次切会话都要手点一遍。
   */
  val model: String? = null,
) {
  /** 是否属于酒馆分区。界面按这个分流，而不是按 kind 逐个判断。 */
  val isRoleplay: Boolean get() = kind == SessionKind.Roleplay
}

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

  /**
   * 最近一段会话（日常或酒馆，不含后台任务），用于冷启动回到「上次在说话的地方」。
   *
   * 与 [currentSession] 的区别：那个只看日常会话。分区之后，如果用户上次停在酒馆里，
   * 重新打开 App 应该回到那段扮演，而不是被扔进一段日常对话。
   */
  suspend fun latestSession(): ChatSession? = null

  /**
   * 按 id 取一段会话；不存在返回 null。
   *
   * 需要它是因为「刚建好的会话」可能还没被 [observeSessions] 推上来 —— 交接发生在建库
   * 之后、列表刷新之前，只从列表里找会找不到，表现成「点了没反应」。
   */
  suspend fun session(id: String): ChatSession? = null

  /**
   * 建一个指定分区/角色的会话。
   *
   * 给了默认实现（退化成日常会话），是为了让只关心日常会话的测试替身不用跟着改；
   * 真实现必须覆盖它，否则酒馆会话会被记成日常会话、分到错的分区里。
   */
  suspend fun createSession(
    title: String?,
    kind: SessionKind,
    characterId: String?,
  ): ChatSession = createSession(title)

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

  /** 记下这段会话在用的模型（切模型时写，进会话时据此装回去）。 */
  suspend fun setSessionModel(sessionId: String, model: String?) {}

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

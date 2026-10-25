package com.tappet.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * tappet.db 的全部读写入口。
 *
 * 用抽象类而不是接口，是为了能在 `@Transaction` 方法里写实现（插入消息时
 * 顺带取序号、更新会话时间戳，必须在同一个事务里）。
 */
@Dao
abstract class SessionStoreDao {
  // ---- session ----

  @Query("SELECT * FROM session WHERE kind = :kind ORDER BY updated_at DESC LIMIT 1")
  abstract suspend fun latestSession(kind: SessionKind): SessionEntity?

  /** 最近一段「人会回到」的会话：日常或酒馆，不含后台任务记录。 */
  @Query("SELECT * FROM session WHERE kind != 'Background' ORDER BY updated_at DESC LIMIT 1")
  abstract suspend fun latestAnySession(): SessionEntity?

  @Query("SELECT * FROM session WHERE id = :id")
  abstract suspend fun session(id: String): SessionEntity?

  @Query("SELECT * FROM session ORDER BY updated_at DESC")
  abstract fun observeSessions(): Flow<List<SessionEntity>>

  @Insert abstract suspend fun insertSession(session: SessionEntity)

  @Query("UPDATE session SET updated_at = :at WHERE id = :id")
  abstract suspend fun touchSession(id: String, at: Long)

  /** 只在标题还空着时写入，避免后来的消息覆盖用户改过的标题。 */
  @Query("UPDATE session SET title = :title WHERE id = :id AND title IS NULL")
  abstract suspend fun titleIfEmpty(id: String, title: String)

  @Query("UPDATE session SET excluded = :excluded WHERE id = :id")
  abstract suspend fun setExcluded(id: String, excluded: Boolean)

  /** 删会话会级联带走它的消息与工具调用（依赖 PRAGMA foreign_keys = ON）。 */
  @Query("DELETE FROM session WHERE id = :id")
  abstract suspend fun deleteSession(id: String): Int

  // ---- message ----

  @Query("SELECT * FROM message WHERE session_id = :sessionId ORDER BY seq")
  abstract suspend fun messages(sessionId: String): List<MessageEntity>

  @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE session_id = :sessionId")
  abstract suspend fun nextSeq(sessionId: String): Long

  @Insert abstract suspend fun insertMessage(message: MessageEntity)

  @Query(
    """
    UPDATE message
    SET content = :content, reasoning = :reasoning, status = :status,
        error = :error, finished_at = :finishedAt
    WHERE id = :id
    """,
  )
  abstract suspend fun updateMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    error: String?,
    finishedAt: Long?,
  ): Int

  @Query("UPDATE message SET status = :to, finished_at = :at WHERE status = :from")
  abstract suspend fun reclassify(from: MessageStatus, to: MessageStatus, at: Long): Int

  @Query(
    """
    SELECT COUNT(*) FROM message
    WHERE session_id = :sessionId AND role = :role AND status = :status
    """,
  )
  abstract suspend fun countByStatus(sessionId: String, role: MessageRole, status: MessageStatus): Int

  /**
   * 追加一条消息：序号与会话时间戳在同一个事务里更新。
   *
   * 序号不靠时间戳，因为同一毫秒可能插入多条；`(session_id, seq)` 上有唯一索引兜底。
   */
  @Transaction
  open suspend fun appendMessage(
    id: String,
    sessionId: String,
    role: MessageRole,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    createdAt: Long,
  ): MessageEntity {
    val entity =
      MessageEntity(
        id = id,
        sessionId = sessionId,
        seq = nextSeq(sessionId),
        role = role,
        content = content,
        reasoning = reasoning,
        status = status,
        error = null,
        createdAt = createdAt,
        finishedAt = null,
      )
    insertMessage(entity)
    touchSession(sessionId, createdAt)
    return entity
  }

  // ---- tool_call ----

  @Insert abstract suspend fun insertToolCall(call: ToolCallEntity)

  @Query("SELECT * FROM tool_call WHERE session_id = :sessionId ORDER BY started_at")
  abstract suspend fun toolCalls(sessionId: String): List<ToolCallEntity>

  @Query(
    """
    UPDATE tool_call
    SET result = :result, status = :status, finished_at = :finishedAt
    WHERE id = :id
    """,
  )
  abstract suspend fun finishToolCall(
    id: String,
    result: String?,
    status: ToolCallStatus,
    finishedAt: Long,
  ): Int
}

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
 *
 * 它也刻意保持「一个库的全部读写入口」：拆成两张表两张 DAO 之后，凡是跨会话与消息的操作
 * （删会话级联、统计、重排）都得在两处各写一遍，调用方还得同时持有两个 DAO。
 * 方法数顶到 detekt 阈值，是这份职责真实的宽度 —— 所以这里压掉那条规则，而不是从外面
 * 再拆一张 DAO 出来。
 */
@Suppress("TooManyFunctions")
@Dao
abstract class SessionStoreDao {
  // ---- session ----

  @Query("SELECT * FROM session WHERE kind = :kind ORDER BY updated_at DESC LIMIT 1")
  abstract suspend fun latestSession(kind: SessionKind): SessionEntity?

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

  /** 群聊在场的所有卡（逗号分隔）。单角色会话不写这一列。 */
  @Query("UPDATE session SET character_ids = :ids WHERE id = :id")
  abstract suspend fun setSessionCharacters(id: String, ids: String?)

  @Query("UPDATE session SET model = :model WHERE id = :id")
  abstract suspend fun setModel(id: String, model: String?)

  @Query("UPDATE session SET excluded = :excluded WHERE id = :id")
  abstract suspend fun setExcluded(id: String, excluded: Boolean)

  /** 删会话会级联带走它的消息与工具调用（依赖 PRAGMA foreign_keys = ON）。 */
  @Query("DELETE FROM session WHERE id = :id")
  abstract suspend fun deleteSession(id: String): Int

  // ---- message ----

  // 只要选中的那一份：同一句提问的多份回答在库里并存，屏幕上永远只走一条线
  @Query("SELECT * FROM message WHERE session_id = :sessionId AND variant_selected = 1 ORDER BY seq")
  abstract suspend fun messages(sessionId: String): List<MessageEntity>

  /** 删一条消息；「重写」「删掉这一轮」用它（工具调用跟着 message_id 级联）。 */
  @Query("DELETE FROM message WHERE id = :id")
  abstract suspend fun deleteMessage(id: String): Int

  /** 同一个 anchor 下的所有回答（含未选中的），按 variant_index 排。 */
  @Query("SELECT * FROM message WHERE anchor_id = :anchorId ORDER BY variant_index")
  abstract suspend fun variantsOf(anchorId: String): List<MessageEntity>

  /** 让同一组里只留下 [keepId] 那一份可见。 */
  @Query("UPDATE message SET variant_selected = (id = :keepId) WHERE anchor_id = :anchorId")
  abstract suspend fun selectVariant(anchorId: String, keepId: String)

  /** 整组候选一起删（「删这轮」时用：只删选中的那份，未选中的会永远躺在库里）。 */
  @Query("DELETE FROM message WHERE anchor_id = :anchorId")
  abstract suspend fun deleteVariants(anchorId: String)

  /** 把一份已有回复登记成某个 anchor 的第 [index] 份候选（重写时第一步就是它）。 */
  @Query("UPDATE message SET anchor_id = :anchorId, variant_index = :index WHERE id = :id")
  abstract suspend fun tagVariant(id: String, anchorId: String, index: Int)

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

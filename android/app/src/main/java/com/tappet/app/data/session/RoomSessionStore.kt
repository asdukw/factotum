package com.tappet.app.data.session

import com.tappet.app.data.db.MessageEntity
import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.SessionEntity
import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.db.TappetDatabase
import com.tappet.app.data.db.ToolCallEntity
import com.tappet.app.data.db.ToolCallStatus
import com.tappet.app.data.db.Uuids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [SessionStore] 的 Room + SQLCipher 实现。 */
class RoomSessionStore(
  database: TappetDatabase,
  private val clock: () -> Long = System::currentTimeMillis,
) : SessionStore {
  private val dao = database.store()
  private val bootstrap = Mutex()

  override suspend fun currentSession(): ChatSession =
    bootstrap.withLock {
      dao.latestSession(SessionKind.Chat)?.toDomain() ?: newSessionLocked(title = null)
    }

  override suspend fun latestSession(): ChatSession? = dao.latestAnySession()?.toDomain()

  override suspend fun session(id: String): ChatSession? = dao.session(id)?.toDomain()

  override suspend fun createSession(title: String?): ChatSession =
    createSession(title, SessionKind.Chat, characterId = null)

  override suspend fun createSession(
    title: String?,
    kind: SessionKind,
    characterId: String?,
  ): ChatSession = bootstrap.withLock { newSessionLocked(title, kind, characterId) }

  private suspend fun newSessionLocked(
    title: String?,
    kind: SessionKind = SessionKind.Chat,
    characterId: String? = null,
  ): ChatSession {
    val now = clock()
    val entity =
      SessionEntity(
        id = Uuids.v7(now),
        title = title,
        kind = kind,
        createdAt = now,
        updatedAt = now,
        characterId = characterId,
      )
    dao.insertSession(entity)
    return entity.toDomain()
  }

  override fun observeSessions(): Flow<List<ChatSession>> =
    dao.observeSessions().map { rows -> rows.map(SessionEntity::toDomain) }

  override suspend fun deleteSession(id: String) {
    dao.deleteSession(id)
  }

  override suspend fun messages(sessionId: String): List<StoredMessage> =
    dao.messages(sessionId).map { it.toDomain() }

  override suspend fun appendMessage(
    sessionId: String,
    role: MessageRole,
    content: String,
    status: MessageStatus,
    reasoning: String?,
  ): StoredMessage {
    val now = clock()
    return dao
      .appendMessage(
        id = Uuids.v7(now),
        sessionId = sessionId,
        role = role,
        content = content,
        reasoning = reasoning,
        status = status,
        createdAt = now,
      )
      .toDomain()
  }

  override suspend fun updateMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    error: String?,
    finishedAt: Long?,
  ) {
    dao.updateMessage(
      id = id,
      content = content,
      reasoning = reasoning,
      status = status,
      error = error,
      finishedAt = finishedAt,
    )
  }

  override suspend fun setTitleIfEmpty(sessionId: String, title: String) {
    dao.titleIfEmpty(sessionId, title)
  }

  override suspend fun appendToolCall(
    id: String,
    sessionId: String,
    messageId: String,
    name: String,
    arguments: String,
  ): StoredToolCall {
    val entity =
      ToolCallEntity(
        id = id,
        messageId = messageId,
        sessionId = sessionId,
        name = name,
        arguments = arguments,
        result = null,
        status = ToolCallStatus.Running,
        startedAt = clock(),
        finishedAt = null,
      )
    dao.insertToolCall(entity)
    return entity.toDomain()
  }

  override suspend fun finishToolCall(id: String, result: String?, status: ToolCallStatus) {
    dao.finishToolCall(id = id, result = result, status = status, finishedAt = clock())
  }

  override suspend fun toolCalls(sessionId: String): List<StoredToolCall> =
    dao.toolCalls(sessionId).map { it.toDomain() }

  override suspend fun markStreamingAsInterrupted(): Int =
    // 半截回复保留；会话时间戳不因这次改判往前跳，所以不走 touchSession。
    dao.reclassify(MessageStatus.Streaming, MessageStatus.Interrupted, clock())
}

private fun SessionEntity.toDomain() =
  ChatSession(
    id = id,
    title = title,
    kind = kind,
    createdAt = createdAt,
    updatedAt = updatedAt,
    characterId = characterId,
  )

private fun MessageEntity.toDomain() =
  StoredMessage(
    id = id,
    sessionId = sessionId,
    seq = seq,
    role = role,
    content = content,
    reasoning = reasoning,
    status = status,
    error = error,
    createdAt = createdAt,
    finishedAt = finishedAt,
  )

private fun ToolCallEntity.toDomain() =
  StoredToolCall(
    id = id,
    sessionId = sessionId,
    messageId = messageId,
    name = name,
    arguments = arguments,
    result = result,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
  )

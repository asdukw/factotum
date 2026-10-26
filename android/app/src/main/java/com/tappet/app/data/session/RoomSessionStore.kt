package com.tappet.app.data.session

import com.tappet.app.data.db.MessageEntity
import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.SessionEntity
import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.db.SessionStoreDao
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
) : SessionStore,
  VariantStore by RoomVariants(database.store()),
  SessionAdmin by RoomAdmin(database.store()) {
  private val dao = database.store()
  private val bootstrap = Mutex()

  override suspend fun currentSession(): ChatSession =
    bootstrap.withLock {
      dao.latestSession(SessionKind.Chat)?.toDomain() ?: newSessionLocked(title = null)
    }

  /**
   * 「上次在说话的地方」＝ 日常与酒馆两段里更新的那段。
   *
   * 不用一条 `kind != 'Background'` 的查询：DAO 的方法数已经顶到 detekt 阈值，
   * 而这两个 kind 恰好就是「人会回来的」全部，组合起来比再加一个抽象方法更省。
   */
  override suspend fun latestSession(): ChatSession? =
    listOfNotNull(
      dao.latestSession(SessionKind.Chat),
      dao.latestSession(SessionKind.Roleplay),
    )
      .maxByOrNull { it.updatedAt }
      ?.toDomain()

  override suspend fun session(id: String): ChatSession? = dao.session(id)?.toDomain()

  override suspend fun createSession(title: String?): ChatSession =
    createSession(title, SessionKind.Chat, characterId = null)

  override suspend fun createSession(
    title: String?,
    kind: SessionKind,
    characterId: String?,
    parentId: String?,
  ): ChatSession = bootstrap.withLock { newSessionLocked(title, kind, characterId, parentId) }

  private suspend fun newSessionLocked(
    title: String?,
    kind: SessionKind = SessionKind.Chat,
    characterId: String? = null,
    parentId: String? = null,
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
        parentId = parentId,
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

  override suspend fun deleteMessage(id: String) {
    dao.deleteMessage(id)
  }

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

  override suspend fun setSessionModel(sessionId: String, model: String?) {
    dao.setModel(sessionId, model)
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

  override suspend fun setSessionCharacters(sessionId: String, ids: List<String>) =
    dao.setSessionCharacters(sessionId, ids.filter { it.isNotBlank() }.joinToString(","))

  override suspend fun markStreamingAsInterrupted(): Int =
    // 半截回复保留；会话时间戳不因这次改判往前跳，所以不走 touchSession。
    dao.reclassify(MessageStatus.Streaming, MessageStatus.Interrupted, clock())
}

/**
 * 会话行管理写操作的实现：群聊名单与前情提要。
 *
 * 与 [RoomVariants] 同理委托出去 —— 主类已经贴着 detekt 的 20 个函数上限，
 * 每加一只写操作都要从别处挪一只出来，不如按职责分开。
 */
internal class RoomAdmin(private val dao: SessionStoreDao) : SessionAdmin {
  override suspend fun setSummary(sessionId: String, text: String, uptoMessageId: String) =
    dao.setSummary(sessionId, text, uptoMessageId)
}

/** 候选回答的读写实现；被 [RoomSessionStore] 委托，好让那个类不超函数上限。 */
internal class RoomVariants(private val dao: SessionStoreDao) : VariantStore {
  override suspend fun variants(anchorId: String): List<StoredMessage> =
    dao.variantsOf(anchorId).map { it.toDomain() }

  override suspend fun selectVariant(anchorId: String, keepId: String) = dao.selectVariant(anchorId, keepId)

  override suspend fun tagVariant(id: String, anchorId: String, index: Int) = dao.tagVariant(id, anchorId, index)

  override suspend fun deleteGroup(anchorId: String) = dao.deleteVariants(anchorId)
}

private fun SessionEntity.toDomain() =
  ChatSession(
    id = id,
    title = title,
    kind = kind,
    createdAt = createdAt,
    updatedAt = updatedAt,
    characterId = characterId,
    characterIds = characterIds.orEmpty().split(',').filter { it.isNotBlank() },
    parentId = parentId,
    summary = summary?.takeIf { it.isNotBlank() },
    summaryUpto = summaryUpto,
    model = model,
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
    anchorId = anchorId,
    variantIndex = variantIndex,
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

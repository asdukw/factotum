package com.tappet.app.agent

import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.StoredMessage

/**
 * 从任意一条消息开一条支线。
 *
 * 做法是**复制**而不是「截断后继续」：把到此为止的历史抄进一个新会话，原会话原封不动。
 * 这是没有消息树的前提下唯一诚实的实现 —— 截断会让另一条走法永久消失，而真实的扮演里
 * 「刚才那句我不认，换个反应」是最常见的动作。
 *
 * 代价写在界面上：支线是**另一段会话**（侧栏里会多一条），不是同一段里的分叉视图。
 * 真正的树要每条消息带父指针，那是另一个量级的改动；在那之前，宁可让两边的历史各自完整。
 */
class Branching(
  private val snapshot: () -> ChatUiState,
  private val store: SessionStore,
  private val start: (suspend () -> Unit) -> Unit,
  private val open: suspend (ChatSession) -> Unit,
  private val reportFailure: (Throwable) -> Unit,
) {
  /** 从 [messageId] 那条消息之后开新支线（含这条本身）。 */
  val fork: (String) -> Unit = { messageId ->
    start {
      val state = snapshot()
      val sessionId = state.sessionId ?: return@start
      val session = store.session(sessionId) ?: return@start
      val rows = store.messages(sessionId)
      val copied = cut(rows, messageId)
      if (copied.isEmpty()) return@start
      runCatching {
        val forked =
          store.createSession(
            title = titleOf(rows),
            kind = session.kind,
            characterId = session.characterId,
            // 支线的支线仍挂到原来那段主线：侧栏只要「主线 + 它的支线」两层。
            parentId = session.parentId ?: session.id,
          )
        if (session.cast.size > 1) store.setSessionCharacters(forked.id, session.cast)
        copied.forEach { row ->
          store.appendMessage(forked.id, row.role, row.content, statusOf(row), row.reasoning)
        }
        store.session(forked.id) ?: forked
      }
        .onSuccess { forked -> open(forked) }
        .onFailure(reportFailure)
    }
  }

  companion object {
    /**
     * 要抄进支线的那一段：从第一条到 [messageId]（含）。
     *
     * 「正在写」的那条抄成「被打断」：支线不该一进去就看见一个永远不会再长的气泡。
     * 找不到这条消息就返回空表（调用方据此什么都不做 —— 比抄半段历史安全）。
     */
    fun cut(rows: List<StoredMessage>, messageId: String): List<StoredMessage> {
      val index = rows.indexOfFirst { it.id == messageId }
      if (index < 0) return emptyList()
      return rows.take(index + 1)
    }

    fun statusOf(row: StoredMessage): MessageStatus =
      if (row.status == MessageStatus.Streaming) MessageStatus.Interrupted else row.status

    /**
     * 支线的标题：取第一句用户话的前 [CAP] 个字，后缀「· 支线」。
     *
     * 不用角色名做前缀：侧栏已经按角色分组了，标题只需要说清「这是哪一段」。
     * 没有用户消息（只有开场白）时退回「开场白之后」。
     */
    fun titleOf(rows: List<StoredMessage>, cap: Int = CAP): String {
      val firstUser = rows.firstOrNull { it.role == MessageRole.User }?.content
      val head = firstUser?.trim()?.replace(Regex("\\s+"), " ")?.take(cap)
      return if (head.isNullOrBlank()) "开场白之后 · 支线" else "$head · 支线"
    }

    const val CAP = 12
  }
}

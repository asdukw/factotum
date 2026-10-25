package com.tappet.app.agent

import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 把会话按角色卡分组：**每张卡都出现**，没有会话的卡也在 —— 那正是开第一段对话的入口。
 *
 * 卡被删掉的会话单独归到「已删除的角色」：历史不跟着卡一起消失，用户还能把它读出来、删掉。
 */
fun groupRoleplaySessions(sessions: List<ChatSession>, cards: List<CharacterCard>): List<RoleplayGroup> {
  val byCard =
    sessions
      .filter { it.kind == SessionKind.Roleplay && it.characterId != null }
      .groupBy { it.characterId!! }
  val groups =
    cards.map { card ->
      RoleplayGroup(
        characterId = card.id,
        name = card.name,
        tagline = card.tagline,
        sessions = byCard[card.id].orEmpty().sortedByDescending { it.updatedAt },
      )
    }
  val orphaned =
    byCard
      .filterKeys { id -> cards.none { it.id == id } }
      .map { (id, list) ->
        RoleplayGroup(
          characterId = id,
          name = "已删除的角色",
          tagline = "卡片已不在库里，历史仍可查看",
          sessions = list.sortedByDescending { it.updatedAt },
        )
      }
  return groups + orphaned
}

/**
 * 酒馆分区的三个动作：新开一段、回到某角色的最近一段、退回日常。
 *
 * 与 [RoleSwitcher] 分开：那个负责「谁在说话」（切系统提示），这个负责「去哪个会话」。
 * 抽成独立类还有一个现实原因 —— ChatViewModel 的方法数已经压到 detekt 阈值边上，
 * 再往里塞就要么拆个类、要么加抑制注释；拆类能让边界更清楚。
 */
class RoleplaySessions(
  private val scope: CoroutineScope,
  private val store: SessionStore,
  private val groups: () -> List<RoleplayGroup>,
  private val open: (ChatSession) -> Unit,
  private val reportFailure: (Throwable) -> Unit,
) {
  /**
   * 给某个角色开一段新对话。同一张卡可以有好几段，互不干扰。
   *
   * 标题故意留空：让第一句用户消息去当标题（与日常会话同一套规则）。用卡名当标题的话，
   * 同一张卡的三段对话在侧栏里全叫一个名字，根本分不出哪段是哪段。
   */
  fun new(characterId: String) {
    scope.launch {
      runCatching { store.createSession(title = null, kind = SessionKind.Roleplay, characterId = characterId) }
        .onSuccess(open)
        .onFailure(reportFailure)
    }
  }

  /** 点角色名：有历史就回最近一段，没有就开新的。 */
  fun openGroup(characterId: String) {
    val latest = groups().firstOrNull { it.characterId == characterId }?.latest
    if (latest == null) new(characterId) else open(latest)
  }

  /** 退出扮演：回最近一段日常会话（没有就新建），人格在那里生效。 */
  fun leave() {
    scope.launch {
      runCatching { store.currentSession() }.onSuccess(open).onFailure(reportFailure)
    }
  }
}

package com.tappet.app.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * 酒馆会话的一轮请求：把「这张卡 + 这段记忆 + 已落库的对话」折成 messages，交给 [TavernChannel]。
 *
 * 单独一个类，理由和 [RoleplaySessions] / [SessionModels] 一样：ChatViewModel 的方法数已经贴在
 * detekt 的阈值上，而这些逻辑（挑卡、读记忆、过滤半截消息）跟聊天状态机本身没有关系。
 *
 * [channel] 为 null 时 [enabled] 为假，调用方应退回 dsh 通道 —— 比如当前提供商是 Anthropic 协议。
 */
class TavernTurns(
  private val cards: () -> List<CharacterCard>,
  /** 每次现取：用户在设置里换了提供商（比如换成 Anthropic 原生协议），这一条就该换通道。 */
  private val channel: () -> TavernChannel? = { null },
  private val memory: (String) -> String = { "" },
  /**
   * 每次现取，且**按卡取**：预设可以绑在某张卡上（[TavernPresetStore.forCard]），
   * 换卡就换写法，不用每张卡手动切一次。参数是角色 id，null 表示当前不是角色扮演。
   */
  private val preset: (String?) -> TavernPreset = { TavernPreset.DEFAULT },
  /**
   * 世界书的跨轮记忆（sticky / cooldown）。参数是会话 id —— 激活记录按会话隔离，
   * 同一个角色在两个会话里各自算各自的轮次。
   */
  private val tracking: (String) -> WorldTracking = { WorldTracking.NONE },
  /** 会话轮次自增并返回；测试与不关心 sticky 的路径用默认的 0。 */
  private val nextTurn: (String) -> Int = { 0 },
) {
  val enabled: Boolean get() = channel() != null

  /**
   * 拼这一轮。返回 null 表示这项能力此刻不可用（没通道 / 卡被删了），调用方退回 dsh。
   *
   * [turns] 直接给界面上的整段历史：正在流式的那条草稿会被丢掉 —— 它还没有内容，
   * 带上等于给模型递一条空消息。重写时调用方已经把上一轮回复移除，所以这里拿到的
   * 历史天然就是「不含被重写的那句」，不需要额外的回滚概念。
   */
  fun stream(sessionId: String, characterId: String?, turns: List<ChatMessage>): Flow<AgentStreamEvent>? {
    val channel = channel() ?: return null
    val card = cards().firstOrNull { it.id == characterId } ?: return null
    val history =
      turns
        .asSequence()
        .filter { !it.streaming && it.error == null && it.content.isNotBlank() }
        .map { TavernMessage(if (it.role == ChatRole.User) TavernMessage.USER else TavernMessage.ASSISTANT, it.content) }
        .toList()
    if (history.isEmpty()) return null
    val turn = nextTurn(sessionId)
    val messages =
      TavernPrompt.build(
        card = card,
        memory = memory(card.id),
        turns = history,
        preset = preset(card.id),
        state =
          TavernPrompt.TurnState(turn = turn, tracking = tracking(sessionId), onHits = { hits -> logWorld(sessionId, turn, hits) }),
      )
    return channel.stream(TavernRequest(sessionId, messages))
  }

  /**
   * 记一行「这一轮带了哪几条设定」。
   *
   * sticky / cooldown 是**跨轮**行为，界面上看不见 —— 用户只会觉得「这条设定怎么还在」或者
   * 「怎么突然没了」。判定对错只能靠这行日志：同一句话连发两轮，第二轮里带的条目应该一样或更少。
   */
  private fun logWorld(sessionId: String, turn: Int, hits: List<WorldEntry>) {
    val names = hits.joinToString("|") { it.keys.firstOrNull() ?: "常驻" }
    Log.d(TAG, "tavern world session=$sessionId turn=$turn hits=${hits.size} [$names]")
  }

  private companion object {
    const val TAG = "TappetAgent"
  }
}

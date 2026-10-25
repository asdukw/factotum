package com.tappet.app.agent

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
  /** 每次现取：用户在预设页里换了那套，下一条消息就该用新的。 */
  private val preset: () -> TavernPreset = { TavernPreset.DEFAULT },
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
    val messages = TavernPrompt.build(card, memory(card.id), history, preset())
    return channel.stream(TavernRequest(sessionId, messages))
  }
}

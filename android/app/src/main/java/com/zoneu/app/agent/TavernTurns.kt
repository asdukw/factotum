package com.zoneu.app.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
  private val legacyTiming: LegacyWorldTiming = LegacyWorldTiming(),
  /**
   * 会话变量的存续。读进来交给 [TavernPrompt] 渲染，这一轮里被 `{{setvar}}` 写过才回存 ——
   * 每轮都写一次会让「变量没变」也产生一次磁盘写入，而变量表里通常是十几条常量。
   */
  private val vars: VarsStore = VarsStore.NONE,
  private val world: WorldDirect? = null,
) {
  val enabled: Boolean get() = channel() != null

  /**
   * 一次性补全：自动提要这类「只读历史、不改历史」的调用走同一条通道与同一套鉴权。
   *
   * 不另开一个 HTTP 客户端：提供商配置、超时、错误映射都已经在这条通道上，复制一份出来
   * 只会让两边慢慢走偏。
   */
  suspend fun complete(sessionId: String, system: String, user: String): String {
    val ch = channel() ?: return ""
    val out = StringBuilder()
    ch.stream(
      TavernRequest(
        sessionId,
        listOf(TavernMessage(TavernMessage.SYSTEM, system), TavernMessage(TavernMessage.USER, user)),
      ),
    )
      .collect { event -> if (event is AgentStreamEvent.TextDelta) out.append(event.text) }
    return out.toString().trim()
  }

  /**
   * 这个会话现在的变量快照（界面上的「状态」小块就是它）。
   *
   * 只读、不改：变量由卡里的 `{{setvar}}` 在一轮轮里写，用户看见的必须是**实际生效的那一份**，
   * 而不是界面自己算的。读不到（没有变量存储）就给空表。
   */
  suspend fun varsOf(sessionId: String): Map<String, String> = vars.load(sessionId)

  /**
   * 手改一个变量（界面上的「这一场的状态」）。
   *
   * 只覆盖这一个键，其余原样回存：`save` 是整表覆盖，读-改-写才能不把别的变量抹掉。
   */
  suspend fun setVar(sessionId: String, name: String, value: String) {
    vars.save(sessionId, vars.load(sessionId) + (name to value))
  }

  /**
   * 拼这一轮。返回 null 表示这项能力此刻不可用（没通道 / 卡被删了），调用方退回 dsh。
   *
   * [turns] 直接给界面上的整段历史。**空的**草稿在这一层被滤掉（内容为空 = 还没开始写），
   * 但有内容的「正在写」那条要留下 —— 续写就是往同一条里追加，模型必须先看见自己写过什么。
   * 重写时调用方已经把上一轮回复移除，所以这里拿到的历史天然就是「不含被重写的那句」。
   *
   * [tail] 是这一轮的补充指令（续写 / 替我写），只进请求、不落库，见 [TavernDirectives]。
   *
   * [cast] 是群聊在场的所有卡 id（含主卡）。多于一张时按 [GroupCast] 合成一张临时卡，
   * 并把群聊规则接在 [tail] 后面 —— 编排层不知道为什么有几个人，它只需要一张卡。
   */
  fun stream(
    sessionId: String,
    characterId: String?,
    turns: List<ChatMessage>,
    tail: String? = null,
    cast: List<String> = emptyList(),
    /**
     * 这个会话的前情提要（自动总结写的）。由调用方从会话行里读好传进来，而不是在这一层去查：
     * [stream] 不是 suspend（它同步返回 Flow），在这里 await 一个 IO 读会把签名整个改成协程。
     *
     * 它与卡记忆一起进 `{{memory}}` 段：两者都是「模型该记得、但历史里已经不在」的事，
     * 分成两段只会让预设作者多写一个槽位。
     */
    recap: String? = null,
    requestId: String = java.util.UUID.randomUUID().toString(),
  ): Flow<AgentStreamEvent>? {
    val channel = channel() ?: return null
    world?.let { runtime ->
      val history = turns.filter { it.error == null && it.content.isNotBlank() }.map {
        TavernMessage(if (it.role == ChatRole.User) TavernMessage.USER else TavernMessage.ASSISTANT, it.content)
      }
      return flow {
        val request = WorldDirect.Request(requestId, sessionId, history, tail, recap, auxiliary = tail == TavernDirectives.IMPERSONATE)
        val plan = runtime.prepare(request)
        emitAll(channel.stream(TavernRequest(sessionId, plan.messages.map { TavernMessage(it.role, it.content) })))
      }
    }
    val ids = (listOfNotNull(characterId) + cast).distinct()
    val castCards = ids.mapNotNull { id -> cards().firstOrNull { it.id == id } }.take(GroupCast.MAX)
    if (castCards.isEmpty()) return null
    val grouped = castCards.size > 1
    val card = if (grouped) GroupCast.merge(castCards) else castCards.first()
    val history =
      turns
        .asSequence()
        .filter { it.error == null && it.content.isNotBlank() }
        .map { TavernMessage(if (it.role == ChatRole.User) TavernMessage.USER else TavernMessage.ASSISTANT, it.content) }
        .toList()
    if (history.isEmpty()) return null
    val turn = legacyTiming.nextTurn(sessionId)
    if (grouped) Log.d(TAG, "tavern group session=$sessionId cast=${castCards.size}")
    val table = TavernVars(vars.load(sessionId))
    val messages =
      TavernPrompt.build(
        card = card,
        memory = listOfNotNull(memory(card.id).takeIf { it.isNotBlank() }, recap).joinToString("\n\n"),
        turns = history,
        preset = preset(card.id),
        state =
          TavernPrompt.TurnState(
            turn = turn,
            tracking = legacyTiming.tracking(sessionId),
            onHits = { hits -> logWorld(sessionId, turn, hits) },
            vars = table,
            // 群聊规则永远在最后：续写/替我写是「这一轮怎么接」，规则是「谁来说」，后者更硬
            tail = if (grouped) listOfNotNull(GroupCast.rule(castCards), tail).joinToString("\n\n") else tail,
          ),
      )
    // 只记长度与「有没有残留花括号」：宏没生效时正文照样好看，唯一能看出来的机器信号
    // 就是还有 `{{` 活着（那说明它没被任何一层认领）。不记正文。
    Log.d(
      TAG,
      "tavern prompt messages=${messages.size} chars=${messages.sumOf { it.content.length }} " +
        "macrosLeft=${messages.any { it.content.contains("{{") }} vars=${table.snapshot().size}",
    )
    if (table.dirty) {
      vars.save(sessionId, table.snapshot())
      Log.d(TAG, "tavern vars session=$sessionId count=${table.snapshot().size}")
    }
    return channel.stream(TavernRequest(sessionId, messages))
  }

  suspend fun settle(requestId: String, success: Boolean, evidence: String) {
    world?.settle(requestId, success, evidence)
  }

  /**
   * 记一行「这一轮带了哪几条设定」。
   *
   * sticky / cooldown 是**跨轮**行为，界面上看不见 —— 用户只会觉得「这条设定怎么还在」或者
   * 「怎么突然没了」。判定对错只能靠这行日志：同一句话连发两轮，第二轮里带的条目应该一样或更少。
   */
  private fun logWorld(sessionId: String, turn: Int, hits: List<WorldEntry>) {
    Log.d(TAG, "tavern world session=$sessionId turn=$turn hits=${hits.size}")
  }

  private companion object {
    const val TAG = "zoneuAgent"
  }
}

/** Compatibility for callers without the durable world runtime; production uses WorldDirect. */
data class LegacyWorldTiming(
  val tracking: (String) -> WorldTracking = { WorldTracking.NONE },
  val nextTurn: (String) -> Int = { 0 },
)

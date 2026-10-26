package com.tappet.app.agent

import kotlin.random.Random

/**
 * 发出去的一条消息。
 *
 * 只用三种角色（[SYSTEM] / [USER] / [ASSISTANT]），与 OpenAI 兼容接口一致 —— 这是酒馆通道与
 * dsh 通道最根本的区别：dsh 只收「一段话」，谁在前谁在后由它的模板决定；这里整条消息数组由我们排。
 */
data class TavernMessage(val role: String, val content: String) {
  companion object {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
  }
}

/**
 * 酒馆的 prompt 编排。
 *
 * 与 dsh 通道（[CharacterStore.renderPrompt] + [LoreInject]）的分工：
 * - dsh 通道：系统提示在运行时 mount 那刻定死，世界设定只能「常驻」或「粘在用户消息前」两个位置；
 * - 这里：整条数组自己拼，于是能做到 ST 的那三件事 —— 世界书**按深度插进聊天中间**、
 *   后置指令**放在历史之后**、重写**真的从头再要一遍**（上下文里不再有「我上一句说过了」）。
 *
 * 纯函数、不碰 Context：编排规则是这套东西里最容易改错、也最该被单测钉住的部分。
 */
object TavernPrompt {
  /** 一次最多带几条命中条目（预算的实现在 [WorldScan]，这里只做常量转发）。 */
  internal const val MAX_ENTRIES = WorldScan.MAX_ENTRIES

  /** 命中条目合计字符上限（粗算 token：中文约一字一 token）。 */
  internal const val MAX_CHARS = WorldScan.MAX_CHARS

  /** 关键词只回看最近几条用户消息 —— 「她呢」这种指代只有连着上文才命中。 */
  internal const val LOOK_BACK = 3

  /**
   * 组装一整轮要发出去的 messages。
   *
   * [turns] 是**已经落库的对话**，最后一条必须是这一轮用户说的话（重写时助手那条已被调用方剔除）。
   * 顺序：系统模板 → 历史（命中条目按深度插进中间）→ 后置指令。
   *
   * 两段骨架来自 [preset]：换一套预设就是换一种写法，编排（插入位置、预算、回看）不受影响。
   */
  fun build(
    card: CharacterCard,
    memory: String,
    turns: List<TavernMessage>,
    preset: TavernPreset = TavernPreset.DEFAULT,
    /** 概率激活用的随机源；单测传固定种子，线上用默认。 */
    random: Random = Random.Default,
    /** 跨轮状态（轮次 + sticky/cooldown 记忆 + 命中回调）；不传就是「每轮都是新的一轮」。 */
    state: TurnState = TurnState(),
  ): List<TavernMessage> {
    val history = turns.filter { it.content.isNotBlank() }
    val hits = hits(card, keywordSource(history), random, state.tracking, state.turn)
    state.onHits(hits)
    // 第一轮 = 还没有任何助手回复。调用时这一轮的用户消息已经落库并进了 [turns]，
    // 所以不能拿 history.isEmpty() 判断 —— 那会让开场白永远出不来。
    val firstTurn = history.none { it.role == TavernMessage.ASSISTANT }
    val values = values(card, memory, firstTurn, hasLore = hits.isNotEmpty())

    val out = ArrayList<TavernMessage>(history.size + hits.size + 2)
    out += TavernMessage(TavernMessage.SYSTEM, renderPreset(preset.system, values))
    out += history
    // 从最深的一处往近处插：先插远的，后面按原索引算的近处位置不会被挤偏。
    hits.sortedByDescending { it.depth }.forEach { entry ->
      val at = (out.size - entry.depth.coerceIn(1, MAX_DEPTH)).coerceIn(1, out.size)
      out.add(at, TavernMessage(TavernMessage.SYSTEM, loreBlock(entry, card)))
    }
    out += TavernMessage(TavernMessage.SYSTEM, renderPreset(preset.postHistory, values))
    return out
  }

  /** 深度超过这个数就等于「放到最开头」，没有意义。 */
  internal const val MAX_DEPTH = 12

  /**
   * 模板变量表。
   *
   * 卡里的正文先过 [CharacterStore.renderText]（`{{char}}` / `{{user}}` 换成名字、剩下的花括号转全角），
   * 再交给模板 —— 否则卡正文里的花括号会被 [TavernTemplate] 当成模板变量。
   */
  internal fun values(
    card: CharacterCard,
    memory: String,
    firstTurn: Boolean,
    hasLore: Boolean,
  ): Map<String, String> {
    // 常驻先按预算裁一遍（超限会截断并注明），再统一做宏替换
    val constant =
      WorldScan.constantBlock(card.world.map { it.copy(content = CharacterStore.renderText(it.content, card)) })
    return buildMap {
      put("char", card.name)
      put("user", USER_NAME)
      put("description", CharacterStore.renderText(card.description, card))
      put("personality", CharacterStore.renderText(card.personality, card))
      put("scenario", CharacterStore.renderText(card.scenario, card))
      put("first_mes", if (firstTurn) CharacterStore.renderText(card.firstMes, card) else "")
      put("world", constant)
      put("memory", CharacterStore.renderText(memory, card))
      put("first", if (firstTurn) FLAG_ON else "")
      put("lore", if (hasLore) FLAG_ON else "")
    }
  }

  /** 条件块（`{{#名字}}…{{/名字}}`）用：非空字符串即为真。 */
  private const val FLAG_ON = "1"

  /** 酒馆里没有「用户名」这个概念，卡正文里的 `{{user}}` 一律换成它。 */
  internal const val USER_NAME = "用户"

  /** 渲染 + 收掉多余空行（条件块被整段消掉时前面会留下连续换行）。 */
  internal fun renderPreset(template: String, values: Map<String, String>): String =
    TavernTemplate.render(template, values).replace(Regex("\n{3,}"), "\n\n").trim()

  /** 命中条目的成段文本：标明是资料、不是谁说的话。 */
  internal fun loreBlock(entry: WorldEntry, card: CharacterCard): String =
    "${LoreInject.MARK_OPEN}\n${CharacterStore.renderText(entry.content, card)}\n${LoreInject.MARK_CLOSE}"

  /** 关键词来源：这一轮 + 最近几条用户消息。 */
  internal fun keywordSource(history: List<TavernMessage>): String =
    history.filter { it.role == TavernMessage.USER }.map { it.content }.takeLast(LOOK_BACK).joinToString("\n")

  /**
   * 一轮的跨轮状态。
   *
   * 打成一个包是因为 `build` 的参数已经到 6 个了（detekt 卡在 8），而这三样永远一起传；
   * [onHits] 是给调用方记账/打日志用的，单测里默认什么都不做。
   */
  data class TurnState(
    val turn: Int = 0,
    val tracking: WorldTracking = WorldTracking.NONE,
    val onHits: (List<WorldEntry>) -> Unit = {},
  )

  /** 按关键词挑出要带的条目（编排细节全在 [WorldScan]）。 */
  internal fun hits(
    card: CharacterCard,
    text: String,
    random: Random = Random.Default,
    tracking: WorldTracking = WorldTracking.NONE,
    turn: Int = 0,
  ): List<WorldEntry> = WorldScan.select(card.world, text, random, tracking = tracking, turn = turn)
}

package com.tappet.app.agent

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
  /** 一次最多带几条命中条目。再多会把设定压在剧情之上，模型开始背书。 */
  internal const val MAX_ENTRIES = 6

  /** 命中条目合计字符上限（粗算 token：中文约一字一 token）。 */
  internal const val MAX_CHARS = 1_600

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
  ): List<TavernMessage> {
    val history = turns.filter { it.content.isNotBlank() }
    val hits = hits(card, keywordSource(history)).filter { !it.constant }
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
    val constant =
      card.world
        .filter { it.constant && it.content.isNotBlank() }
        .sortedBy { it.order }
        .joinToString("\n") { "· ${CharacterStore.renderText(it.content, card)}" }
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
   * 按关键词挑出要带的条目。
   *
   * 与 ST 的差距写在明处：这里只有「关键词包含」+ 条数/字数上限，没有递归扫描、逻辑与或、
   * 概率激活、sticky/cooldown —— 那些要等世界书编辑器做出来再说，先把「能按需插进中间」跑通。
   */
  internal fun hits(card: CharacterCard, text: String): List<WorldEntry> {
    if (text.isBlank()) return emptyList()
    val matched =
      card.world
        .sortedBy { it.order }
        .filter { !it.constant && it.content.isNotBlank() }
        .filter { entry -> entry.matches(text) }

    // 预算用完就收；单条超预算只跳过它（后面的也许更短），不整体截断。
    var budget = MAX_CHARS
    val out = ArrayList<WorldEntry>(MAX_ENTRIES)
    var index = 0
    while (index < matched.size && out.size < MAX_ENTRIES) {
      val entry = matched[index]
      if (entry.content.length <= budget) {
        budget -= entry.content.length
        out += entry
      }
      index++
    }
    return out
  }
}

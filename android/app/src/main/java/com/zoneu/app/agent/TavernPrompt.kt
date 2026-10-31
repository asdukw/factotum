package com.zoneu.app.agent

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

  /** 关键词只回看最近几条消息（ST `scan_depth`，默认 2，数的是消息不是轮）。 */
  internal const val SCAN_DEPTH = 2

  /** 旧调用点仍可用：等价于默认扫描深度。 */
  internal const val LOOK_BACK = SCAN_DEPTH

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
    /**
     * 这一轮的包裹：轮次、sticky/cooldown 记忆、命中回调，以及会话变量与末尾指令。
     *
     * 都收在这里是为了让 `build` 的参数停在 6 个 —— 这些字段永远一起传，散成参数只会让
     * 每个调用点都要重复一遍「不关心时该传什么」。
     */
    state: TurnState = TurnState(),
  ): List<TavernMessage> {
    state.injections?.let { return planned(card, memory, turns, preset, state, it) }
    val vars = state.vars
    val tail = state.tail
    // 先按预算裁历史：裁完再算关键词，否则命中的条目可能来自一段已经被丢掉的对话
    val history = ContextBudget.trim(turns.filter { it.content.isNotBlank() }, preset.historyChars).turns
    val hits = hits(card, keywordSource(history, preset.scanDepth), random, state.tracking, state.turn, preset.recursionDepth)
    state.onHits(hits)
    // 第一轮 = 还没有任何助手回复。调用时这一轮的用户消息已经落库并进了 [turns]，
    // 所以不能拿 history.isEmpty() 判断 —— 那会让开场白永远出不来。
    val firstTurn = history.none { it.role == TavernMessage.ASSISTANT }
    val values = values(card, memory, firstTurn, hasLore = hits.isNotEmpty(), vars = vars) + blocks(hits, card, vars)
    // 卡作者的系统提示只在用户显式打开时替换应用模板；{{original}} 代表原来的模板。
    val effective = effectivePreset(card, preset)

    val out = ArrayList<TavernMessage>(history.size + hits.size + 2)
    out += TavernMessage(TavernMessage.SYSTEM, systemMessage(effective, values, vars))
    if (card.useExampleDialogue) out += exampleMessages(card, vars)
    out += history
    // 只有「按深度」的条目进聊天中间：人设之前/之后的两种已经并进上面那条系统消息了
    placeInChat(out, hits, card, vars)
    out += TavernMessage(TavernMessage.SYSTEM, renderPreset(effective.postHistory, values, vars))
    // 空的 tail 不能发：一条内容为空的 system 会让部分提供商直接 400
    if (!tail.isNullOrBlank()) out += TavernMessage(TavernMessage.SYSTEM, tail.trim())
    return out
  }

  /** Fixed host plan: no scanning, random selection, or activation writes here. */
  private fun planned(
    card: CharacterCard,
    memory: String,
    turns: List<TavernMessage>,
    preset: TavernPreset,
    state: TurnState,
    injections: List<WorldInjection>,
  ): List<TavernMessage> {
    val history = ContextBudget.trim(turns.filter { it.content.isNotBlank() }, preset.historyChars).turns
    val effective = effectivePreset(card, preset)
    // 常驻条目有独立预算，走 {{world}} 槽位，不和关键词条目抢位置与名额。
    val constants = injections.filter { it.position == "constant" }
    val values = values(
      card.copy(world = emptyList()),
      memory,
      history.none { it.role == TavernMessage.ASSISTANT },
      injections.any { it.position != "constant" },
      state.vars,
    ) +
      mapOf(
        "world" to constants.joinToString("\n") { "· ${it.content}" },
        "world_before" to "",
        "world_after" to "",
      )
    val before = injections.filter { it.position == "before" }.joinToString("\n\n") { it.content }
    val after = injections.filter { it.position == "after" }.joinToString("\n\n") { it.content }

    val system = plannedSystem(effective, values, state.vars, before, after)
    val out = arrayListOf(TavernMessage(TavernMessage.SYSTEM, system))
    if (card.useExampleDialogue) out += exampleMessages(card, state.vars)
    out += history
    injections.filter { it.position == "depth" }
      .groupBy { (out.size - it.depth.coerceIn(0, MAX_DEPTH)).coerceIn(1, out.size) }
      .toSortedMap(reverseOrder()).forEach { (at, entries) ->
        out.addAll(at, entries.map { TavernMessage(it.role, it.content) })
      }
    val post = renderPreset(effective.postHistory, values, state.vars)
    if (post.isNotBlank()) out += TavernMessage(TavernMessage.SYSTEM, post)
    state.tail?.takeIf { it.isNotBlank() }?.let { out += TavernMessage(TavernMessage.SYSTEM, it.trim()) }
    return out
  }

  /**
   * 系统消息：模板渲染之后，把「人设之前 / 人设之后」两块接到该在的位置。
   *
   * 模板里有 `{{world_before}}` / `{{world_after}}` 就由模板决定落点（默认预设就是）；
   * 用户自己的老预设没有这两个变量时，退化成「系统消息开头 / 结尾」—— 与其静默丢掉这些设定，
   * 不如位置差一点并在界面上说明。
   */
  private fun systemMessage(preset: TavernPreset, values: Map<String, String>, vars: TavernVars): String {
    val rendered = renderPreset(preset.system, values, vars)
    val before = values["world_before"].orEmpty()
    val after = values["world_after"].orEmpty()
    val head = if (before.isNotBlank() && !preset.system.contains("{{world_before}}")) "$before\n\n$rendered" else rendered
    return if (after.isNotBlank() && !preset.system.contains("{{world_after}}")) "$head\n\n$after" else head
  }

  /** 槽位缺失时退化成附在系统提示末尾；重复槽位仍然拒绝（位置有歧义）。 */
  private fun plannedSystem(
    preset: TavernPreset,
    values: Map<String, String>,
    vars: TavernVars,
    before: String,
    after: String,
  ): String {
    fun slotState(slot: String): Int =
      when {
        preset.system.indexOf(slot) < 0 -> 0
        preset.system.indexOf(slot) == preset.system.lastIndexOf(slot) -> 1
        else -> 2
      }

    fun requireSlot(content: String, slot: String) {
      if (content.isNotBlank()) worldCheck(slotState(slot) != 2, "WORLD_CHARACTER_ANCHOR_INCOMPATIBLE")
    }
    requireSlot(before, "{{world_before}}")
    requireSlot(after, "{{world_after}}")
    val fallback =
      buildString {
        if (before.isNotBlank() && slotState("{{world_before}}") == 0) append("\n\n【世界设定·前置】\n").append(before)
        if (after.isNotBlank() && slotState("{{world_after}}") == 0) append("\n\n【世界设定·补充】\n").append(after)
      }
    return renderPreset(
      preset.system + fallback,
      values + mapOf("world_before" to before, "world_after" to after),
      vars,
    )
  }

  /** 把「按深度」的条目插进 [out] 的聊天段。从最深的一处往近处插，先插远的不会被后面挤偏。 */
  private fun placeInChat(out: MutableList<TavernMessage>, hits: List<WorldEntry>, card: CharacterCard, vars: TavernVars) {
    hits
      .filter { it.position == WorldEntry.POSITION_DEPTH }
      .sortedByDescending { it.depth }
      .forEach { entry ->
        val at = (out.size - entry.depth.coerceIn(0, MAX_DEPTH)).coerceIn(1, out.size)
        out.add(at, TavernMessage(roleOf(entry), loreBlock(entry, card, vars)))
      }
  }

  /** 条目以谁的口吻出现：ST 的 `role` 数字，认不出的按 system。 */
  private fun roleOf(entry: WorldEntry): String =
    when (entry.role) {
      WorldEntry.ROLE_USER -> TavernMessage.USER
      WorldEntry.ROLE_ASSISTANT -> TavernMessage.ASSISTANT
      else -> TavernMessage.SYSTEM
    }

  /**
   * 「人设之前 / 之后」两块内容，作为模板变量 `{{world_before}}` / `{{world_after}}`。
   *
   * 这两块与深度条目共用同一轮的条数预算（[WorldScan.MAX_ENTRIES]）—— 一张卡里这两种通常
   * 各只有一两条；再单独开一份预算，会让「挑几条」变成两套数字，调起来更难对。
   */
  private fun blocks(hits: List<WorldEntry>, card: CharacterCard, vars: TavernVars): Map<String, String> {
    fun block(position: Int): String {
      var budget = MAX_BLOCK_CHARS
      val lines = ArrayList<String>()
      for (entry in hits.filter { it.position == position }.sortedBy { it.order }) {
        val text = loreBlock(entry, card, vars)
        if (text.length > budget) break
        budget -= text.length
        lines += text
      }
      return lines.joinToString("\n\n")
    }
    return mapOf("world_before" to block(WorldEntry.POSITION_BEFORE), "world_after" to block(WorldEntry.POSITION_AFTER))
  }

  /** 「人设之前 / 之后」合计的字符预算，量级与常驻块相当。 */
  internal const val MAX_BLOCK_CHARS = 1_200

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
    vars: TavernVars = TavernVars(),
  ): Map<String, String> {
    // 常驻先按预算裁一遍（超限会截断并注明），再统一做宏替换
    val constant =
      WorldScan.constantBlock(card.world.map { it.copy(content = cardText(it.content, card, vars)) })
    return buildMap {
      put("char", card.displayName)
      put("user", USER_NAME)
      put("description", cardText(card.description, card, vars))
      put("personality", cardText(card.personality, card, vars))
      put("scenario", cardText(card.scenario, card, vars))
      put("first_mes", if (firstTurn) cardText(card.firstMes, card, vars) else "")
      put("world", constant)
      put("memory", cardText(memory, card, vars))
      put("first", if (firstTurn) FLAG_ON else "")
      put("lore", if (hasLore) FLAG_ON else "")
    }
  }

  /** 条件块（`{{#名字}}…{{/名字}}`）用：非空字符串即为真。 */
  private const val FLAG_ON = "1"

  /** 酒馆里没有「用户名」这个概念，卡正文里的 `{{user}}` 一律换成它。 */
  internal const val USER_NAME = "用户"

  /**
   * 卡里的正文：先过宏、再过名字替换。
   *
   * 顺序不能反：`CharacterStore.renderText` 会把不认识的 `{{...}}` 转成全角，先替换的话
   * 卡里的 `{{random}}`/`{{setvar}}` 就成了死字符，永远等不到宏这一遍。
   */
  internal fun cardText(text: String, card: CharacterCard, vars: TavernVars): String =
    CharacterStore.renderText(TavernMacros.render(text, vars), card)

  /** 用户打开「使用卡内提示」时，把卡作者的两段模板接进预设；`{{original}}` 展开为原模板。 */
  internal fun effectivePreset(card: CharacterCard, preset: TavernPreset): TavernPreset {
    if (!card.useCardPrompt) return preset
    val system = card.archivedText("system_prompt").replace("{{original}}", preset.system).ifBlank { preset.system }
    val post =
      card.archivedText("post_history_instructions")
        .replace("{{original}}", preset.postHistory)
        .ifBlank { preset.postHistory }
    return preset.copy(system = system, postHistory = post)
  }

  /**
   * `mes_example` → 交替的 user/assistant 消息（ST 的格式）。
   *
   * 认不出 `{{user}}:` / `{{char}}:` 前缀时退化成一条 system 块，至少不丢内容。
   * 只有用户显式打开 `useExampleDialogue` 才会走到这里。
   */
  internal fun exampleMessages(card: CharacterCard, vars: TavernVars): List<TavernMessage> {
    val raw = card.archivedText("mes_example").trim()
    if (raw.isBlank()) return emptyList()
    val messages = mutableListOf<TavernMessage>()
    raw.split(Regex("(?i)<START>")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { block ->
      block.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        when {
          line.startsWith("{{user}}:") -> messages += TavernMessage(TavernMessage.USER, line.removePrefix("{{user}}:").trim())
          line.startsWith("{{char}}:") -> messages += TavernMessage(TavernMessage.ASSISTANT, line.removePrefix("{{char}}:").trim())
        }
      }
    }
    if (messages.isEmpty()) return listOf(TavernMessage(TavernMessage.SYSTEM, "【示例对话】\n${cardText(raw, card, vars)}"))
    return messages.map { it.copy(content = cardText(it.content, card, vars)) }
  }

  /**
   * 渲染 + 收掉多余空行（条件块被整段消掉时前面会留下连续换行）。
   *
   * 宏在模板之前：`{{setvar}}` 的结果要能被同一段模板后面的 `{{#名字}}` 条件块看见。
   */
  internal fun renderPreset(template: String, values: Map<String, String>, vars: TavernVars = TavernVars()): String =
    TavernTemplate.render(TavernMacros.render(template, vars), values).replace(Regex("\n{3,}"), "\n\n").trim()

  /** 命中条目的成段文本：标明是资料、不是谁说的话。 */
  internal fun loreBlock(entry: WorldEntry, card: CharacterCard, vars: TavernVars = TavernVars()): String =
    "${LoreInject.MARK_OPEN}\n${cardText(entry.content, card, vars)}\n${LoreInject.MARK_CLOSE}"

  /** 关键词来源：最近 [depth] 条消息（用户和角色都说过的都算），角色消息带说话人前缀。 */
  internal fun keywordSource(history: List<TavernMessage>, depth: Int = SCAN_DEPTH, characterName: String = ""): String =
    history.takeLast(depth.coerceIn(0, MAX_DEPTH)).joinToString("\n") { message ->
      // 不给用户消息加通用前缀：像「用户」这种词一旦成了前缀，任何卡的对应关键词都会每轮命中。
      if (message.role == TavernMessage.ASSISTANT && characterName.isNotBlank()) {
        "$characterName: ${message.content}"
      } else {
        message.content
      }
    }

  /**
   * 一轮的跨轮状态。
   *
   * 打成一个包是因为 `build` 的参数已经到 6 个了（detekt 卡在 8），而这三样永远一起传；
   * [onHits] 是给调用方记账/打日志用的，单测里默认什么都不做。
   */
  data class TurnState(
    val injections: List<WorldInjection>? = null,
    val turn: Int = 0,
    val tracking: WorldTracking = WorldTracking.NONE,
    val onHits: (List<WorldEntry>) -> Unit = {},
    /** 会话变量（`{{setvar}}` 写在这里）。不传就是「这次渲染没有变量」，也不会有写操作。 */
    val vars: TavernVars = TavernVars(),
    /**
     * 追加在最后的一条指令（续写 / 替我写）。
     *
     * 放在后置指令之后的**最后一条 system**：既改写了这一轮的任务，又不动历史 ——
     * 续写不能靠插一条假的用户消息来驱动，那句话会留在库里，下一轮模型会以为用户真说过。
     */
    val tail: String? = null,
  )

  /** 按关键词挑出要带的条目（编排细节全在 [WorldScan]）。 */
  internal fun hits(
    card: CharacterCard,
    text: String,
    random: Random = Random.Default,
    tracking: WorldTracking = WorldTracking.NONE,
    turn: Int = 0,
    depth: Int = 1,
  ): List<WorldEntry> =
    WorldScan.select(card.world, text, random, tracking = tracking, turn = turn, limits = WorldScan.Limits(depth = depth))
}

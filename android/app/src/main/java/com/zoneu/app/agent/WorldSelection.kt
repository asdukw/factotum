package com.zoneu.app.agent

import kotlin.random.Random

/**
 * Shared direct/dsh selector: no storage, clock, or implicit tracking writes.
 *
 * 语义对齐 SillyTavern 的 World Info：常量、关键词/副词/排除、概率、粘性/冷却、递归、
 * 互斥组、延迟与递归门控、生成类型、角色过滤、预算。所有随机都从 [Input.seed] 派生，
 * 同一轮重放得到同一份计划。
 */
object WorldSelection {
  const val GENERATION_NORMAL = "normal"
  const val GENERATION_CONTINUE = "continue"
  const val GENERATION_IMPERSONATE = "impersonate"
  const val GENERATION_REGENERATE = "regenerate"
  const val GENERATION_SWIPE = "swipe"
  const val GENERATION_QUIET = "quiet"

  /** dsh 投影的硬上限是 128 条；留出前情提要等宿主注入的余量。 */
  const val MAX_INJECTIONS = 120

  data class Input(
    val entries: List<WorldResolvedEntry>,
    val text: String,
    val state: WorldRuntimeState = WorldRuntimeState(),
    val seed: Int = 0,
    val limits: WorldScan.Limits = WorldScan.Limits(),
    val explain: Boolean = false,
    /** 生成类型，用于条目的 `triggers` 过滤。 */
    val generation: String = GENERATION_NORMAL,
    /** 当前在场角色名/标签，用于 ST 的 `characterFilter`。 */
    val characterNames: List<String> = emptyList(),
    val characterTags: Set<String> = emptySet(),
    /**
     * 完整（已按预算裁过的）历史。非空时按条目的 scanDepth 逐条重算关键词来源；
     * 为空时退回 [text]，供单测和显式传入摘要的调用方使用。
     */
    val history: List<TavernMessage> = emptyList(),
    val scanDepth: Int = TavernPrompt.SCAN_DEPTH,
    /** 说话人前缀用；空则用「角色」。 */
    val characterName: String = "",
    /** 只有新的用户—助手交换才把轮次 +1；重写/续写复用当前轮。 */
    val turnAdvance: Boolean = true,
  )

  data class Result(val injections: List<WorldInjection>, val decisions: List<WorldDecision>, val activated: List<String>)

  /** Renderer commits macro variable changes only if its result fits remaining budget. */
  fun select(input: Input, render: (WorldResolvedEntry, Int) -> String = { entry, _ -> entry.rule.content }): Result =
    Selection(input, render).run()

  private class Selection(private val input: Input, private val render: (WorldResolvedEntry, Int) -> String) {
    private val selected = linkedMapOf<String, Pair<WorldResolvedEntry, String>>()
    private val reasons = linkedMapOf<String, String>()
    private val activated = linkedSetOf<String>()

    /** 组内落选者：递归轮次也不能把它们重新捡回来。 */
    private val groupBlocked = mutableSetOf<String>()
    private var remaining = input.limits.maxChars.coerceAtLeast(0)
    private var constantRemaining = input.limits.maxConstantChars.coerceAtLeast(0)
    private var regularCount = 0
    private val bookSpent = mutableMapOf<String, Int>()
    private val pool = input.entries.distinctBy { it.key }.sortedBy { it.rule.order }
    private val turn = input.state.turn + if (input.turnAdvance) 1 else 0

    fun run(): Result {
      offerBatch(pool, extra = "", recursive = false, pass = 0)
      expand()
      return result()
    }

    private fun result(): Result =
      Result(
        injections = selected.values.map { (entry, text) -> injection(entry, text) },
        decisions =
          pool.map { entry ->
            WorldDecision(
              entryId = entry.entryId,
              bookId = entry.bookId,
              revision = entry.revision,
              sources = entry.sources,
              reason = reasons[entry.key] ?: "keyword_miss",
              selected = entry.key in selected,
              chars = selected[entry.key]?.second?.length ?: 0,
              position = entry.rule.position,
              role = entry.rule.role,
              depth = entry.rule.depth,
            )
          },
        activated = if (input.explain) emptyList() else activated.toList(),
      )

    /** 一批候选：先算资格，再做组内互斥，最后按 order 装预算。 */
    private fun offerBatch(entries: List<WorldResolvedEntry>, extra: String, recursive: Boolean, pass: Int): List<WorldResolvedEntry> {
      val sources = HashMap<String, String>()
      val eligible =
        entries.mapNotNull { entry ->
          if (entry.key in selected || entry.key in groupBlocked) return@mapNotNull null
          val source = sourceFor(entry, extra)
          sources[entry.key] = source
          val sticky = entry.rule.sticking(turn, input.state.activated[entry.key])
          val reason = eligibility(entry, source, recursive, pass, sticky)
          if (reason.isEmpty()) {
            entry
          } else {
            record(entry, reason, recursive)
            null
          }
        }
      val added = ArrayList<WorldResolvedEntry>()
      groupArbitrate(eligible, sources).sortedBy { it.rule.order }.forEach { entry ->
        if (offer(entry, recursive)) added += entry
      }
      return added
    }

    private fun sourceFor(entry: WorldResolvedEntry, extra: String): String {
      val scanned =
        if (input.history.isEmpty()) {
          input.text
        } else {
          TavernPrompt.keywordSource(input.history, entry.rule.scanDepth ?: input.scanDepth, input.characterName)
        }
      return if (extra.isBlank()) scanned else "$scanned\n$extra"
    }

    private fun eligibility(entry: WorldResolvedEntry, source: String, recursive: Boolean, pass: Int, sticky: Boolean): String {
      basicReason(entry)?.let { return it }
      // 粘住的条目在窗口内无条件保留：不再查关键词、副词、排除、概率与冷却。
      if (sticky) return ""
      return gatedReason(entry, source, recursive, pass)
    }

    /** 与轮次无关的硬性条件。 */
    private fun basicReason(entry: WorldResolvedEntry): String? {
      val rule = entry.rule
      return when {
        !entry.enabled || !rule.enabled -> "disabled"
        rule.content.isBlank() -> "empty"
        entry.unsupported.isNotEmpty() || !supported(rule) -> "unsupported"
        !rule.triggerAllows(input.generation) -> "trigger"
        !characterAllows(rule) -> "character_filter"
        else -> null
      }
    }

    /** 与轮次/递归相关的激活条件。 */
    private fun gatedReason(entry: WorldResolvedEntry, source: String, recursive: Boolean, pass: Int): String {
      timingReason(entry.rule, recursive, pass).let { if (it.isNotEmpty()) return it }
      contentReason(entry, source).let { if (it.isNotEmpty()) return it }
      return probabilityReason(entry)
    }

    private fun timingReason(rule: WorldEntry, recursive: Boolean, pass: Int): String =
      when {
        rule.delay > 0 && turn <= rule.delay -> "delay"
        recursive && rule.excludeRecursion -> "recursion_excluded"
        rule.delayUntilRecursion > 0 && (!recursive || pass < rule.delayUntilRecursion) -> "delay_recursion"
        else -> ""
      }

    private fun contentReason(entry: WorldResolvedEntry, source: String): String {
      val rule = entry.rule
      return when {
        !rule.constant && !rule.matches(source) -> "keyword_miss"
        rule.excluded(source) -> "excluded"
        !rule.secondarySatisfied(source) -> "secondary"
        rule.cooling(turn, input.state.activated[entry.key]) -> "cooldown"
        else -> ""
      }
    }

    private fun probabilityReason(entry: WorldResolvedEntry): String {
      val rule = entry.rule
      return when {
        input.explain && rule.effectiveProbability <= 0 -> "probability"
        input.explain && rule.effectiveProbability < 100 -> "probability_pending"
        !input.explain && !rolls(rule, input.seed, entry.key) -> "probability"
        else -> ""
      }
    }

    private fun offer(entry: WorldResolvedEntry, recursive: Boolean): Boolean {
      val rule = entry.rule
      val sticky = rule.sticking(turn, input.state.activated[entry.key])
      // 条数/总注入上限在渲染前就挡住：宏里的 {{setvar}} 不能在注定被拒的条目上提交。
      if (!rule.ignoreBudget && !rule.constant && regularCount >= input.limits.maxEntries) {
        reasons[entry.key] = "budget"
        return false
      }
      if (selected.size >= MAX_INJECTIONS) {
        reasons[entry.key] = "budget"
        return false
      }
      val content = render(entry, effectiveBudget(entry))
      val rejection =
        when {
          content.isBlank() -> "empty"
          !withinBudget(entry, content) -> "budget"
          else -> ""
        }
      if (rejection.isNotEmpty()) {
        reasons[entry.key] = rejection
        return false
      }
      commit(entry, content, sticky, recursive)
      return true
    }

    /** 渲染前可算出的有效预算：全局剩余与本书剩余取小。 */
    private fun effectiveBudget(entry: WorldResolvedEntry): Int {
      val rule = entry.rule
      if (rule.ignoreBudget) return Int.MAX_VALUE
      val base = if (rule.constant) constantRemaining else remaining
      val book =
        entry.bookBudget?.let { cap -> (cap - (bookSpent[entry.bookId] ?: 0)).coerceAtLeast(0) }
          ?: base
      return minOf(base, book)
    }

    private fun commit(entry: WorldResolvedEntry, content: String, sticky: Boolean, recursive: Boolean) {
      val rule = entry.rule
      if (!rule.ignoreBudget) {
        if (rule.constant) constantRemaining -= content.length else remaining -= content.length
        entry.bookBudget?.let { bookSpent[entry.bookId] = (bookSpent[entry.bookId] ?: 0) + content.length }
      }
      if (!rule.constant && !rule.ignoreBudget) regularCount++
      selected[entry.key] = entry to content
      reasons[entry.key] = selectedReason(rule, sticky, recursive)
      if (!sticky && !rule.constant) activated += entry.key
    }

    /** 书级预算先于全局条数/字数；ignoreBudget 全部跳过。 */
    private fun withinBudget(entry: WorldResolvedEntry, content: String): Boolean {
      val rule = entry.rule
      if (rule.ignoreBudget) return true
      val bookCap = entry.bookBudget
      val budget = if (rule.constant) constantRemaining else remaining
      return when {
        selected.size >= MAX_INJECTIONS -> false
        bookCap != null && (bookSpent[entry.bookId] ?: 0) + content.length > bookCap -> false
        !rule.constant && regularCount >= input.limits.maxEntries -> false
        content.length > budget -> false
        else -> true
      }
    }

    /** 同组条目一轮只留一个：优先 order，其次加权抽签；useGroupScoring 时先按命中数筛。 */
    private fun groupArbitrate(eligible: List<WorldResolvedEntry>, sources: Map<String, String>): List<WorldResolvedEntry> {
      if (eligible.none { it.rule.groupNames().isNotEmpty() }) return eligible
      val groups = linkedMapOf<String, MutableList<WorldResolvedEntry>>()
      eligible.forEach { entry -> entry.rule.groupNames().forEach { groups.getOrPut(it) { mutableListOf() } += entry } }
      val dropped = mutableSetOf<String>()
      groups.forEach { (name, members) ->
        // 这一组已经有成员被选中（可能在上一轮递归里）：其余成员本轮起永久落选。
        val chosen = pool.firstOrNull { it.key in selected && name in it.rule.groupNames() }
        if (chosen != null) {
          members.filter { it.key != chosen.key }.forEach {
            dropped += it.key
            groupBlocked += it.key
            reasons[it.key] = "group"
          }
          return@forEach
        }
        val alive = members.filter { it.key !in dropped }
        if (alive.size < 2) return@forEach
        val winner = pickWinner(alive, sources[alive.first().key].orEmpty(), name)
        alive.filter { it.key != winner.key }.forEach {
          dropped += it.key
          groupBlocked += it.key
          reasons[it.key] = "group"
        }
      }
      return eligible.filter { it.key !in dropped }
    }

    private fun pickWinner(members: List<WorldResolvedEntry>, source: String, group: String): WorldResolvedEntry {
      val scored =
        if (members.any { it.rule.useGroupScoring }) {
          val top = members.maxOf { it.rule.keyScore(source) }
          members.filter { it.rule.keyScore(source) == top }
        } else {
          members
        }
      val overrides = scored.filter { it.rule.groupOverride }
      if (overrides.isNotEmpty()) return overrides.maxByOrNull { it.rule.order } ?: overrides.first()
      // 权重来自外部卡，先夹到合理范围并累加为 Long，避免 Int 溢出让 nextInt 抛异常。
      val weights = scored.map { it.rule.groupWeight.coerceIn(1, 10_000).toLong() }
      var pick = Random(input.seed xor group.hashCode()).nextLong(weights.sum())
      for (index in scored.indices) {
        pick -= weights[index]
        if (pick < 0) return scored[index]
      }
      return scored.last()
    }

    private fun characterAllows(rule: WorldEntry): Boolean {
      if (rule.characterFilterNames.isEmpty() && rule.characterFilterTags.isEmpty()) return true
      val byName = rule.characterFilterNames.any { name -> input.characterNames.any { it.equals(name, ignoreCase = true) } }
      val byTag = rule.characterFilterTags.any { it in input.characterTags }
      val hit = byName || byTag
      return if (rule.characterFilterExclude) !hit else hit
    }

    private fun expand() {
      var frontier = selected.values.map { it.first }.filterNot { it.rule.preventRecursion }
      var pass = 0
      val max = input.limits.depth.coerceIn(0, TavernPrompt.MAX_DEPTH)
      while (pass < max && frontier.isNotEmpty()) {
        pass++
        val extra = frontier.joinToString("\n") { selected.getValue(it.key).second }
        val added = offerBatch(pool, extra, recursive = true, pass = pass)
        // 某一层没有新增也要继续推到上限：delay_until_recursion 的条目可能正等着后面的层。
        frontier = added.filterNot { it.rule.preventRecursion }.ifEmpty { frontier.filterNot { it.rule.preventRecursion } }
      }
    }

    private fun record(entry: WorldResolvedEntry, reason: String, recursive: Boolean) {
      if (!recursive || reasons[entry.key] == null || reasons[entry.key] == "keyword_miss") reasons[entry.key] = reason
    }
  }

  private fun selectedReason(rule: WorldEntry, sticky: Boolean, recursive: Boolean): String = when {
    sticky -> "sticky"
    rule.constant -> "constant"
    rule.delayUntilRecursion > 0 -> "recursive_gated"
    recursive -> "recursive"
    else -> "keyword"
  }

  private fun supported(rule: WorldEntry): Boolean =
    rule.position in listOf(WorldEntry.POSITION_BEFORE, WorldEntry.POSITION_AFTER, WorldEntry.POSITION_DEPTH) &&
      rule.role in WorldEntry.ROLE_SYSTEM..WorldEntry.ROLE_ASSISTANT &&
      (rule.position == WorldEntry.POSITION_DEPTH || rule.role == WorldEntry.ROLE_SYSTEM)

  private fun rolls(rule: WorldEntry, seed: Int, key: String): Boolean {
    val probability = rule.effectiveProbability.coerceIn(0, 100)
    val random = Random(WorldResolver.digest("$seed:$key").take(8).toLong(16))
    return probability >= 100 || (probability > 0 && random.nextInt(100) < probability)
  }

  private fun injection(entry: WorldResolvedEntry, content: String): WorldInjection = WorldInjection(
    entryId = entry.entryId,
    key = entry.key,
    position = when (entry.rule.position) {
      WorldEntry.POSITION_BEFORE -> "before"
      WorldEntry.POSITION_AFTER -> "after"
      else -> if (entry.rule.constant) "constant" else "depth"
    },
    depth = entry.rule.depth.coerceIn(0, TavernPrompt.MAX_DEPTH),
    role = when (entry.rule.role) {
      WorldEntry.ROLE_USER -> TavernMessage.USER
      WorldEntry.ROLE_ASSISTANT -> TavernMessage.ASSISTANT
      else -> TavernMessage.SYSTEM
    },
    content = content,
  )
}

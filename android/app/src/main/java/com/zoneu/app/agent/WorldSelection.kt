package com.zoneu.app.agent

import kotlin.random.Random

/** Shared direct/dsh selector: no storage, clock, or implicit tracking writes. */
object WorldSelection {
  data class Input(
    val entries: List<WorldResolvedEntry>,
    val text: String,
    val state: WorldRuntimeState = WorldRuntimeState(),
    val seed: Int = 0,
    val limits: WorldScan.Limits = WorldScan.Limits(),
    val explain: Boolean = false,
  )

  data class Result(val injections: List<WorldInjection>, val decisions: List<WorldDecision>, val activated: List<String>)

  /** Renderer commits macro variable changes only if its result fits remaining budget. */
  fun select(input: Input, render: (WorldResolvedEntry, Int) -> String = { entry, _ -> entry.rule.content }): Result =
    Selection(input, render).run()

  private class Selection(private val input: Input, private val render: (WorldResolvedEntry, Int) -> String) {
    private val selected = linkedMapOf<String, Pair<WorldResolvedEntry, String>>()
    private val reasons = linkedMapOf<String, String>()
    private val activated = linkedSetOf<String>()
    private var remaining = input.limits.maxChars.coerceAtLeast(0)
    private val pool = input.entries.distinctBy { it.key }.sortedBy { it.rule.order }
    private val turn = input.state.turn + 1

    fun run(): Result {
      pool.forEach { offer(it, input.text, false) }
      expand()
      return Result(
        injections = selected.values.map { (entry, text) -> injection(entry, text) },
        decisions = pool.map { entry ->
          WorldDecision(
            entryId = entry.entryId,
            bookId = entry.bookId,
            sources = entry.sources,
            reason = reasons[entry.key] ?: "keyword_miss",
            selected = entry.key in selected,
            chars = selected[entry.key]?.second?.length ?: 0,
            position = entry.rule.position,
            role = entry.rule.role,
          )
        },
        activated = if (input.explain) emptyList() else activated.toList(),
      )
    }

    private fun offer(entry: WorldResolvedEntry, source: String, recursive: Boolean): Boolean {
      if (entry.key in selected) return false
      val sticky = entry.rule.sticking(turn, input.state.activated[entry.key])
      val reason = eligibility(entry, source, recursive, sticky)
      if (reason.isNotEmpty()) {
        if (!recursive || reasons[entry.key] == "keyword_miss") reasons[entry.key] = reason
        return false
      }
      val content = if (input.explain) entry.rule.content else render(entry, remaining)
      if (content.isBlank() || content.length > remaining) {
        reasons[entry.key] = "budget"
        return false
      }
      selected[entry.key] = entry to content
      remaining -= content.length
      reasons[entry.key] = selectedReason(entry.rule, sticky, recursive)
      if (!sticky && !entry.rule.constant) activated += entry.key
      return true
    }

    private fun eligibility(entry: WorldResolvedEntry, source: String, recursive: Boolean, sticky: Boolean): String {
      val rule = entry.rule
      return when {
        !entry.enabled -> "disabled"
        rule.content.isBlank() -> "empty"
        entry.unsupported.isNotEmpty() || !supported(rule) -> "unsupported"
        rule.excluded(source) -> "excluded"
        !rule.secondarySatisfied(source) -> "secondary"
        rule.cooling(turn, input.state.activated[entry.key]) && !sticky -> "cooldown"
        else -> triggerReason(entry, source, recursive, sticky)
      }
    }

    private fun triggerReason(entry: WorldResolvedEntry, source: String, recursive: Boolean, sticky: Boolean): String {
      val rule = entry.rule
      return when {
        recursive && rule.excludeRecursion -> "recursion_excluded"
        !rule.constant && !sticky && !rule.matches(source) -> "keyword_miss"
        input.explain && rule.effectiveProbability in 1..99 -> "probability_pending"
        !input.explain && !rolls(rule, input.seed, entry.key) -> "probability"
        rule.effectiveProbability <= 0 -> "probability"
        selected.size >= input.limits.maxEntries -> "budget"
        else -> ""
      }
    }

    private fun expand() {
      var frontier = selected.values.map { it.first }.filterNot { it.rule.preventRecursion }
      repeat(input.limits.depth.coerceIn(0, TavernPrompt.MAX_DEPTH)) {
        if (frontier.isEmpty()) return@repeat
        val source = input.text + "\n" + frontier.joinToString("\n") { selected.getValue(it.key).second }
        val added = pool.filter { entry -> entry.key !in selected && offer(entry, source, true) }
        frontier = added.filterNot { it.rule.preventRecursion }
      }
    }
  }

  private fun selectedReason(rule: WorldEntry, sticky: Boolean, recursive: Boolean): String = when {
    sticky -> "sticky"
    recursive -> "recursive"
    rule.constant -> "constant"
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
      else -> "depth"
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

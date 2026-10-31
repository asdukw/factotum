package com.zoneu.app.agent

/**
 * 世界书条目编辑时的纯数据处理。
 *
 * 编辑器里用户看到的是「逗号分隔的一行关键词」，存进去的是 `List<String>`；两边来回转的规则
 * （怎么拆、能填几条、上下限）如果散在 Compose 代码里，就只能靠手点验证。抽出来单测。
 *
 * 与 [WorldEntry.matches] 的关系：那里拆的是**条目自己的 key 串**（真卡把同义词打包进一条），
 * 这里拆的是**编辑框里用户刚敲的一行**。两边用同一套分隔符，否则「我明明写了两个关键词」会变成
 * 一个永远匹配不上的长串 —— 这个坑已经在真卡上踩过一次。
 */
object WorldEntryDraft {
  /** 与 [WorldEntry] 内部拆词同一组分隔符。 */
  private val SEPARATORS = Regex("[,，、·/|｜；;\\n]+")

  /** 关键词上限：再多也塞不进一轮的预算，而且界面会变成一堵墙。 */
  const val MAX_KEYS = 24

  /** 粘住 / 冷却的轮数上限：超过 20 轮基本等于「永远在」或「永远不再」，不如直接改常驻。 */
  const val MAX_STICKY = 20

  /** 0 = 插在历史末尾（ST 的 depth 0），运行时按同一约定处理。 */
  const val MIN_DEPTH = 0
  const val MAX_DEPTH = 12

  /** 正文上限：单条超过这个长度基本等于把整本书塞进聊天。 */
  const val MAX_CONTENT = 4_000

  /** 「a, b，c、d」→ 四个词。空串丢掉、去重、保持输入顺序。 */
  fun parseKeys(text: String): List<String> =
    text.split(SEPARATORS)
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .take(MAX_KEYS)
      .toList()

  fun formatKeys(keys: List<String>): String = keys.joinToString("、")

  fun clampDepth(depth: Int): Int = depth.coerceIn(MIN_DEPTH, MAX_DEPTH)

  fun clampProbability(probability: Int): Int = probability.coerceIn(0, 100)

  /** 正文超长时截断而不是拒绝：用户点保存的意图很明确，只是写多了。 */
  fun clampContent(content: String): String = content.trim().take(MAX_CONTENT)

  /** 编辑框里的原始文本。字段全用文本/布尔/整数，切成条目由 [build] 负责。 */
  data class Draft(
    val original: WorldEntry? = null,
    val primary: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val secondary: String = "",
    val exclude: String = "",
    val depth: Int = 4,
    val probability: Int = 100,
    val logic: SecondaryLogic = SecondaryLogic.ANY,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val position: Int = WorldEntry.POSITION_DEPTH,
    val role: Int = WorldEntry.ROLE_SYSTEM,
    val useProbability: Boolean = true,
    val matchWholeWords: Boolean = false,
    val caseSensitive: Boolean = false,
    // null = 调用方没提供，沿用 original；避免把导入卡里的值重置成默认。
    val order: Int? = null,
    val enabled: Boolean? = null,
    val preventRecursion: Boolean? = null,
    val excludeRecursion: Boolean? = null,
    val group: String? = null,
    val delay: Int? = null,
    val delayUntilRecursion: Int? = null,
    val ignoreBudget: Boolean? = null,
  )

  /**
   * 用编辑框里的原始文本拼一条条目。
   *
   * 常驻条目不需要关键词（它每轮都在），但保留用户填的东西 —— 从常驻切回按需时不用重填。
   */
  fun build(draft: Draft): WorldEntry {
    val old = draft.original

    fun <T> changed(value: T, before: T?, normalized: () -> T): T = if (old != null && value == before) value else normalized()
    return (old ?: WorldEntry()).copy(
      keys = if (old != null && draft.primary == formatKeys(old.keys)) old.keys else parseKeys(draft.primary),
      content = changed(draft.content, old?.content) { clampContent(draft.content) },
      constant = draft.constant,
      depth = changed(draft.depth, old?.depth) { clampDepth(draft.depth) },
      secondary = if (old != null && draft.secondary == formatKeys(old.secondary)) old.secondary else parseKeys(draft.secondary),
      secondaryLogic = draft.logic,
      exclude = if (old != null && draft.exclude == formatKeys(old.exclude)) old.exclude else parseKeys(draft.exclude),
      probability = changed(draft.probability, old?.probability) { clampProbability(draft.probability) },
      sticky = changed(draft.sticky, old?.sticky) { draft.sticky.coerceIn(0, MAX_STICKY) },
      cooldown = changed(draft.cooldown, old?.cooldown) { draft.cooldown.coerceIn(0, MAX_STICKY) },
      position = changed(draft.position, old?.position) { clampPosition(draft.position) },
      role = changed(draft.role, old?.role) { clampRole(draft.role) },
      useProbability = draft.useProbability,
      matchWholeWords = draft.matchWholeWords,
      caseSensitive = draft.caseSensitive,
    ).withTiming(draft, old).withRuntime(draft, old)
  }

  /** 三态旋钮：调用方没给就沿用原条目，避免把导入值重置成默认。 */
  private fun WorldEntry.withTiming(draft: Draft, old: WorldEntry?): WorldEntry = copy(
    order = draft.order ?: old?.order ?: 0,
    enabled = draft.enabled ?: old?.enabled ?: true,
    preventRecursion = draft.preventRecursion ?: old?.preventRecursion ?: false,
    excludeRecursion = draft.excludeRecursion ?: old?.excludeRecursion ?: false,
  )

  private fun WorldEntry.withRuntime(draft: Draft, old: WorldEntry?): WorldEntry = copy(
    group = draft.group ?: old?.group.orEmpty(),
    delay = draft.delay ?: old?.delay ?: 0,
    delayUntilRecursion = draft.delayUntilRecursion ?: old?.delayUntilRecursion ?: 0,
    ignoreBudget = draft.ignoreBudget ?: old?.ignoreBudget ?: false,
  )

  /** 只认三种落点；卡里写别的数字（老版本 ST 有 2/3）一律回到「按深度」。 */
  fun clampPosition(position: Int): Int =
    when (position) {
      WorldEntry.POSITION_BEFORE,
      WorldEntry.POSITION_AFTER,
      WorldEntry.POSITION_DEPTH,
      -> position

      else -> WorldEntry.POSITION_DEPTH
    }

  fun clampRole(role: Int): Int =
    when (role) {
      WorldEntry.ROLE_USER,
      WorldEntry.ROLE_ASSISTANT,
      WorldEntry.ROLE_SYSTEM,
      -> role

      else -> WorldEntry.ROLE_SYSTEM
    }

  /** 条目是否值得存：常驻只要有正文，按需还要有关键词。 */
  fun isSavable(entry: WorldEntry): Boolean =
    entry.content.isNotBlank() && (entry.constant || entry.keys.isNotEmpty())
}

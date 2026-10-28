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

  const val MIN_DEPTH = 1
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
  )

  /**
   * 用编辑框里的原始文本拼一条条目。
   *
   * 常驻条目不需要关键词（它每轮都在），但保留用户填的东西 —— 从常驻切回按需时不用重填。
   */
  fun build(draft: Draft): WorldEntry =
    WorldEntry(
      keys = parseKeys(draft.primary),
      content = clampContent(draft.content),
      constant = draft.constant,
      depth = clampDepth(draft.depth),
      secondary = parseKeys(draft.secondary),
      secondaryLogic = draft.logic,
      exclude = parseKeys(draft.exclude),
      probability = clampProbability(draft.probability),
      sticky = draft.sticky.coerceIn(0, MAX_STICKY),
      cooldown = draft.cooldown.coerceIn(0, MAX_STICKY),
      position = clampPosition(draft.position),
      role = clampRole(draft.role),
      useProbability = draft.useProbability,
      matchWholeWords = draft.matchWholeWords,
      caseSensitive = draft.caseSensitive,
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

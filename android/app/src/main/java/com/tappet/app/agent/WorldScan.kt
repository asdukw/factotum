package com.tappet.app.agent

import kotlin.random.Random

/**
 * 世界书检索：从一张卡的全部条目里挑出这一轮该带哪几条。
 *
 * 与 ST（SillyTavern）World Info 的对应关系，以及刻意没做的东西：
 * - 主关键词命中 ✔（[WorldEntry.matches]，同义词打包会拆开比）
 * - 次要关键词 / 排除词 ✔（ANY / ALL，见 [WorldEntry.secondarySatisfied]）
 * - 概率激活 ✔（[WorldEntry.probability]，用注入的 [Random]，单测可复现）
 * - 递归扫描 ✔，但**只再扫一层**：命中条目正文里出现了别的条目的关键词，就把那条一起带上。
 *   ST 可以设 2–5 层；层数一高就会出现「一条设定牵出半本书」的滚雪球，真卡里几乎都是靠
 *   作者手写关键词控制范围的，一层足够覆盖「势力 → 该势力的人」这类常见结构。
 * - sticky / cooldown / 定时激活 ✘：这两个都需要跨轮记忆（「上一轮激活过就留 N 轮」），
 *   而现在每轮都是纯函数现算，没有会话级状态。要做就得先有一份按会话的激活表。
 *
 * 纯函数、不碰 Context：什么条件下带哪几条，是这套东西里最容易被改坏的部分。
 */
object WorldScan {
  /** 一轮最多带几条。再多会把设定压在剧情之上，模型开始背书。 */
  const val MAX_ENTRIES = 6

  /** 命中条目合计字符上限（中文约一字一 token，1600 已经相当于两屏设定）。 */
  const val MAX_CHARS = 1_600

  /** 常驻条目合计上限：真卡常驻加起来可能有几千字，全塞进系统提示会把开场白和人设冲掉。 */
  const val MAX_CONSTANT_CHARS = 2_000

  /**
   * 挑出这一轮要插进聊天中间的条目（不含常驻 —— 常驻由 [constantBlock] 单独处理）。
   *
   * [text] 是关键词来源（这一轮 + 最近几条用户消息，见 [TavernPrompt.keywordSource]）。
   * 顺序按 `order`，同类按卡的原始顺序，保证同一句话两次请求带进去的设定一样 ——
   * 顺序每次抖动会让回复风格跟着抖，很难看出是模型的问题还是编排的问题。
   */
  fun select(
    entries: List<WorldEntry>,
    text: String,
    random: Random = Random.Default,
    maxEntries: Int = MAX_ENTRIES,
    maxChars: Int = MAX_CHARS,
    tracking: WorldTracking = WorldTracking.NONE,
    turn: Int = 0,
  ): List<WorldEntry> {
    if (text.isBlank()) return emptyList()
    val pool = entries.filter { !it.constant && it.content.isNotBlank() }.sortedBy { it.order }

    val shelf = Shelf(maxEntries, maxChars)
    // 第一轮：主关键词 + 次要逻辑 + 排除词 + 概率；冷却期内的不参与（免得同一段设定被反复吸进来）
    val byKeyword =
      pool.filter { entry ->
        !entry.cooling(turn, tracking.lastActivated(WorldTracking.key(entry))) && accepts(entry, text, random)
      }
    byKeyword.forEach { shelf.offer(it) }
    // 粘性：上一轮按关键词激活过、还在粘性期内，这一轮即使没提关键词也留着
    pool
      .filter { entry ->
        byKeyword.none { it === entry } &&
          entry.sticking(turn, tracking.lastActivated(WorldTracking.key(entry)))
      }
      .forEach { shelf.offer(it) }
    // 第三轮（只一层）：命中条目的正文里提到了谁，就把谁一起带上
    val seeds = shelf.items.toList()
    pool
      .filter { candidate ->
        // 已经捡到的不再捡第二次（否则同一条会出现两遍）
        shelf.items.none { it === candidate } && seeds.any { seed -> isMentionedIn(candidate, seed, text) }
      }
      .forEach { shelf.offer(it) }
    // 只给「按关键词命中」的记账：粘住的条目不能刷新自己的计时，否则粘性会无限续期
    byKeyword.forEach { tracking.record(WorldTracking.key(it), turn) }
    return shelf.items
  }

  /**
   * 常驻条目：按 order 拼成一段，超过 [MAX_CONSTANT_CHARS] 就截断。
   *
   * 截断而不是「全塞」：常驻是每轮都付的固定成本，一张卡的常驻写到 5000 字时，
   * 用户感觉到的不是「设定更全」，而是「模型开始复读设定、回话变短」。
   * 截断处给一句说明，模型自己知道设定被节选过，不会把「没提到」当成「不存在」。
   */
  fun constantBlock(entries: List<WorldEntry>, maxChars: Int = MAX_CONSTANT_CHARS): String {
    val constant = entries.filter { it.constant && it.content.isNotBlank() }.sortedBy { it.order }
    if (constant.isEmpty()) return ""
    var budget = maxChars
    val lines = ArrayList<String>()
    for (entry in constant) {
      val line = "· ${entry.content}"
      if (line.length > budget) break
      budget -= line.length
      lines += line
    }
    if (lines.size < constant.size) lines += "（其余常驻设定因篇幅省略）"
    return lines.joinToString("\n")
  }

  /** 一条条目在这一轮是否该出现在聊天中间。 */
  private fun accepts(entry: WorldEntry, text: String, random: Random): Boolean =
    entry.matches(text) && !entry.excluded(text) && entry.secondarySatisfied(text) && rolls(entry, random)

  /** 递归扫描的判据：正文提到它，且用户这一轮没把它否掉。 */
  private fun isMentionedIn(candidate: WorldEntry, seed: WorldEntry, text: String): Boolean =
    candidate.matches(seed.content) && !candidate.excluded(text)

  /** 概率判定。100 必中、0 必不中，其余按比例。 */
  private fun rolls(entry: WorldEntry, random: Random): Boolean {
    val probability = entry.probability.coerceIn(0, 100)
    return probability >= 100 || (probability > 0 && random.nextInt(100) < probability)
  }

  /** 收集器：条数与字符预算都收在这里，调用处就不用来回 break / continue。 */
  private class Shelf(private val maxEntries: Int, private var budget: Int) {
    val items = ArrayList<WorldEntry>()

    fun offer(entry: WorldEntry) {
      if (items.size >= maxEntries || entry.content.length > budget) return
      budget -= entry.content.length
      items += entry
    }
  }
}

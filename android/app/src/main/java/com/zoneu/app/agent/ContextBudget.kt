package com.zoneu.app.agent

import kotlin.math.ceil

/**
 * token 估算与上下文裁剪。
 *
 * 为什么不用真 tokenizer：每个提供商的切分都不同（DeepSeek / Qwen / GPT 的 BPE 不是一套表），
 * 为二十来个模型各带一份词表，APK 会膨胀几十 MB，而我们要的只是「大概还剩多少」。
 * 规则是**宁可高估**：估高了只是少放几句旧对话，估低了会直接 400。
 *
 * 中文按一字一 token、拉丁按四字母一 token —— 前者是这几家大模型实测的常见比例，
 * 后者与 OpenAI 的经验值一致。
 */
object Tokens {
  /**
   * 估一段文本的 token 数。
   *
   * CJK 与全角标点（U+2E80 起）算 1；其余字母数字按 4 个一 token；空白不计。
   * 加 1 是给 BOS/role 这类协议开销留个位。
   */
  fun estimate(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var ascii = 0
    for (char in text) {
      when {
        char.code >= 0x2E80 -> cjk++
        char.isLetterOrDigit() -> ascii++
      }
    }
    return cjk + ceil(ascii / 4.0).toInt() + 1
  }

  /** 界面展示：<1000 直接给数字，以上给一位小数（`1.2k`）—— 精确到个位在这里没有意义。 */
  fun format(tokens: Int): String =
    if (tokens < 1000) {
      tokens.toString()
    } else {
      val tenths = (tokens + 50) / 100
      "${tenths / 10}.${tenths % 10}k"
    }
}

/**
 * 上下文预算。
 *
 * 只裁**历史**，不裁系统提示：人设与世界设定是这张卡的身份证，丢了它就不是这张卡了；
 * 而对话是越旧越可丢的。裁掉的部分留一行说明，否则模型会以为自己从一开始就在场，
 * 用户也会疑惑「它怎么忘了刚才」。
 *
 * [HISTORY] 取 4000 是保守值：加上系统提示与世界书（约 2–3k），一轮总输入落在 8k 以内，
 * 本地小模型与免费额度都能跑。上下文更大的模型不会因为这一项变差 —— 少给几句旧对话
 * 换来的是永远不 400。
 */
object ContextBudget {
  const val HISTORY = 4_000

  /** 裁剪结果。[dropped] 让调用方能说明「省了多少」，也方便日志核对。 */
  data class Trimmed(val turns: List<TavernMessage>, val dropped: Int)

  /**
   * 从**最旧的**开始丢，直到塞进 [budget]。
   *
   * 三条规矩：
   * 1. 至少留最后一条 —— 它是这一轮用户刚说的话，丢了等于没问；
   * 2. 留下的第一句若是助手，往前多丢一条：历史以助手开头时，模型会把它当成开场白，
   *    然后接着「自己」往下说，而不是回应这一轮；
   * 3. 丢过东西就在开头补一条说明（system，不当成谁说的话）。
   */
  fun trim(turns: List<TavernMessage>, budget: Int = HISTORY): Trimmed {
    if (turns.isEmpty()) return Trimmed(turns, 0)
    var used = 0
    var start = turns.size
    for (index in turns.indices.reversed()) {
      val cost = Tokens.estimate(turns[index].content)
      if (used + cost > budget && start < turns.size) break
      used += cost
      start = index
    }
    if (start < turns.size && turns[start].role == TavernMessage.ASSISTANT) start++
    if (start == 0) return Trimmed(turns, 0)
    val note = TavernMessage(TavernMessage.SYSTEM, "〔更早的 $start 条对话已省略，只保留最近的往来〕")
    return Trimmed(listOf(note) + turns.subList(start, turns.size), start)
  }
}

package com.tappet.app.agent

/**
 * 世界书的**按轮检索**：把「这一句命中了哪几条设定」算出来，随消息一起送进去。
 *
 * 为什么不在系统提示里一次塞完：卡的设定动辄十几条，全塞进系统提示既费 token 又会让模型抓着
 * 无关设定不放；而系统提示在 mount 那一刻就定了，一轮里改不了 —— 所以只能在**发出去的这一轮**
 * 里带资料。这就是 SillyTavern 里 `lore_search`（按需检索）在无插件环境下的等价做法。
 *
 * 代价如实写在标记里：这段资料出现在用户消息的位置上，所以必须显式告诉模型「这不是用户说的话」，
 * 否则它会以为用户在念设定，甚至照着复述。
 */
object LoreInject {
  const val MARK_OPEN = "〔设定资料〕"
  const val MARK_CLOSE = "〔/设定资料〕"

  /** 一次最多带几条：再多就变成把世界书全塞进来了。 */
  private const val MAX_ENTRIES = 4

  /** 总字符预算：长条目会挤掉对话本身。 */
  private const val MAX_CHARS = 1_200

  /** 回看多少条之前说过的话：关键词常常在上一句里被提到，这一句只接着说「她呢」。 */
  private const val LOOK_BACK = 3

  /** 命中条件：非常驻、有内容、且任一关键词出现在这段文本里。 */
  internal fun matched(card: CharacterCard, haystack: String): List<WorldEntry> {
    if (haystack.isBlank()) return emptyList()
    return card.world
      .filter { entry ->
        entry.content.isNotBlank() &&
          !entry.constant &&
          entry.keys.any { key -> key.isNotBlank() && haystack.contains(key, ignoreCase = true) }
      }
      .sortedBy { it.order }
      .take(MAX_ENTRIES)
  }

  /**
   * 给这一轮的用户消息加一段设定资料；没命中就原样返回（不加空标记，避免每句都多两行噪声）。
   *
   * [recent] 是本会话最近几条**用户**消息，按时间正序；只用来补关键词，不会原样送给模型。
   */
  fun augment(card: CharacterCard?, text: String, recent: List<String> = emptyList()): String {
    if (card == null || text.isBlank()) return text
    val haystack = (recent.takeLast(LOOK_BACK) + text).joinToString(" ")
    val entries = matched(card, haystack)
    if (entries.isEmpty()) return text

    val block =
      buildString {
        appendLine(MARK_OPEN)
        appendLine("下面是这次对话用得上的背景。这些内容只作参考，不是你或用户说的话；不要在回复里提到这段资料，也不要复述它。")
        var budget = MAX_CHARS
        for (entry in entries) {
          // 同一套渲染：卡里的 {{user}} 若原样发出去，dsh 的模板会把它当成未注册变量直接报错。
          val body = CharacterStore.renderText(entry.content, card)
          if (body.length > budget) break
          budget -= body.length
          appendLine("· $body")
        }
        append(MARK_CLOSE)
      }
    return "$block\n\n$text"
  }
}

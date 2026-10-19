package com.tappet.app.voice

import com.tappet.app.ui.chat.InlineToken
import com.tappet.app.ui.chat.Markdown
import com.tappet.app.ui.chat.MarkdownBlock

/**
 * 把助手正文（Markdown）翻成「读出来是人话」的纯文本，并按句切成引擎能吃的段。
 *
 * 为什么不能直接把 `message.content` 交给 TTS：模型写的是给人**看**的 Markdown。
 * `**重点**`、`## 标题`、代码围栏、链接的 URL —— 这些读出来要么是噪音（"星号星号"），
 * 要么是灾难（把一整段 base64 念完）。所以朗读走的是与渲染同一条解析（[Markdown]），
 * 只是把块与行内标记**摊平成文字**。
 *
 * 纯 Kotlin、不碰 Android：切句是这类功能最容易写错又最不需要设备的地方（长文本被
 * 引擎拒收、句子被从中间劈开、标点丢了），全部在 JVM 单测里钉住。
 */
object SpeakableText {
  /**
   * 一次 [chunks] 调用的兜底上限（字符数）。
   *
   * `TextToSpeech.getMaxSpeechInputLength()` 是 4000，超过直接抛。留到 320 不是因为
   * 4000 不安全，而是**切得短才有得停**：一段读完才检查一次取消，长段会让「停止」
   * 要等好几秒才生效。引擎可以给更小的值（[TtsEngine.maxChunk]）。
   */
  const val DEFAULT_MAX_CHUNK = 320

  /** Markdown → 可朗读文本。全是代码块这类没有可读内容时返回空串。 */
  fun of(markdown: String): String {
    val parts = Markdown.parse(markdown).mapNotNull(::blockText).filter { it.isNotBlank() }
    return parts.joinToString("\n").let(::clean).trim()
  }

  /**
   * 按句切段，每段不超过 [limit] 个字符。
   *
   * 切点优先落在句末标点（。！？；!?;\n），其次逗号顿号，最后才硬切 —— 硬切会把一个词
   * 劈成两半，读出来是两段不连贯的音，所以要尽量晚发生。切完的段保留原标点：
   * 标点决定引擎的停顿与语调，丢掉会让整段变成念经。
   */
  fun chunks(text: String, limit: Int = DEFAULT_MAX_CHUNK): List<String> {
    val max = if (limit <= 0) DEFAULT_MAX_CHUNK else limit
    val out = mutableListOf<String>()
    val buffer = StringBuilder()

    fun flush() {
      if (buffer.isNotBlank()) out += buffer.toString().trim()
      buffer.clear()
    }

    for (sentence in sentences(text)) {
      for (piece in splitLong(sentence, max)) {
        // 合得进去就合：频繁调用引擎既慢，段与段之间还会多出一次生硬的停顿。
        if (buffer.isNotEmpty() && buffer.length + piece.length > max) flush()
        buffer.append(piece)
      }
      // 换行是**强制**断点。模型写的换行是有意义的（列表项、标题、表格行），
      // 合并成一段会让「买菜 取快递 记得带伞」连成一口气 —— 列表就读没了。
      if (sentence.endsWith("\n")) flush()
    }
    flush()
    return out
  }

  // --- 块 → 文字 ---

  private fun blockText(block: MarkdownBlock): String? =
    when (block) {
      is MarkdownBlock.Paragraph -> inline(block.text)

      is MarkdownBlock.Heading -> inline(block.text)

      is MarkdownBlock.Quote -> inline(block.text)

      is MarkdownBlock.BulletList -> block.items.mapNotNull(::inline).joinToString("\n")

      is MarkdownBlock.NumberedList -> block.items.mapNotNull(::inline).joinToString("\n")

      // 代码块整个丢掉：朗读代码没有意义，而它往往占正文的大半 ——
      // 读出来的是标点与缩进的杂音，还会把后面的正文挤到很晚才出声。
      is MarkdownBlock.CodeBlock -> null

      // 分隔线本来就是视觉留白，读出来只会多一次停顿。
      is MarkdownBlock.Divider -> null
    }

  /** 行内标记摊平：链接只留文字（URL 读出来是一串无人能听的字符）。 */
  private fun inline(text: String): String =
    Markdown.inline(text).joinToString("") { token ->
      when (token) {
        is InlineToken.Text -> token.text
        is InlineToken.Code -> token.text
        is InlineToken.Bold -> token.text
        is InlineToken.Italic -> token.text
        is InlineToken.Link -> token.text
      }
    }

  // --- 清洗 ---

  private fun clean(text: String): String =
    text.lineSequence()
      .mapNotNull(::cleanLine)
      .filter { it.isNotBlank() }
      .joinToString("\n")

  /** 清洗一行；整行都不可读（表格分隔行）时返回 null。 */
  private fun cleanLine(line: String): String? {
    val withoutUrl = line.replace(BARE_URL, "")
    // 表格的分隔行（`| --- | --- |`）读出来是一串「横杠」，整行丢掉。
    if (TABLE_RULE.matches(withoutUrl)) return null
    return stripEmoji(withoutUrl.trim().removePrefix("|").removeSuffix("|"))
      // 顺序要紧：先把空格压掉，竖线替换时才不会在逗号两边留下空格
      // （`| 项目 | 状态 |` 该读成「项目，状态」而不是「项目 ， 状态」）。
      .replace(HORIZONTAL_SPACE, " ")
      .replace(TABLE_PIPE, "，")
      .trim()
      .ifBlank { null }
  }

  /**
   * 去掉 emoji。
   *
   * 不这么做的话，`✅ 已完成` 会被念成「白色重型对勾 已完成」—— 而模型很爱用 emoji
   * 当项目符号。零宽连接符与变体选择符一起去掉，否则会剩下孤立的组合字符。
   */
  private fun stripEmoji(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
      val code = text.codePointAt(index)
      if (!isEmoji(code)) out.appendCodePoint(code)
      index += Character.charCount(code)
    }
    return out.toString()
  }

  private fun isEmoji(code: Int): Boolean =
    code in 0x1F000..0x1FAFF || // 表情、图形符号、扩展区
      code in 0x2600..0x27BF || // 杂项符号与装饰符号（✅ ✨ ➡ …）
      code in 0x2B00..0x2BFF || // 杂项符号与箭头（⭐ ⬛ …）
      code in 0xFE00..0xFE0F || // 变体选择符
      code == 0x200D || // 零宽连接符
      code == 0x20E3 // 组合包围键（1️⃣ 的尾巴）

  // --- 切句 ---

  /** 切成句子，标点跟着前一句走。 */
  private fun sentences(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (char in text) {
      current.append(char)
      if (char in SENTENCE_END) {
        out += current.toString()
        current.clear()
      }
    }
    if (current.isNotBlank()) out += current.toString()
    return out
  }

  /** 单句超长时的软切点，优先级从高到低。 */
  private fun splitLong(sentence: String, limit: Int): List<String> {
    if (sentence.length <= limit) return listOf(sentence)
    val out = mutableListOf<String>()
    var rest = sentence
    while (rest.length > limit) {
      val cut = softCut(rest, limit)
      out += rest.substring(0, cut)
      rest = rest.substring(cut)
    }
    if (rest.isNotEmpty()) out += rest
    return out
  }

  /** 在 [limit] 以内找最后一个软切点；找不到就硬切在 [limit]（长串无标点时没有更好的选择）。 */
  private fun softCut(text: String, limit: Int): Int {
    for (index in minOf(limit, text.length - 1) downTo 1) {
      if (text[index - 1] in SOFT_BREAK) return index
    }
    return limit
  }

  private val BARE_URL = Regex("""https?://\S+""")

  /** 表格的分隔行（`| --- | --- |`），读出来是一串「横杠」。 */
  private val TABLE_RULE = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
  private val TABLE_PIPE = Regex("""\s*\|\s*""")
  private val HORIZONTAL_SPACE = Regex("""[ \t 　]+""")

  private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n', '…')
  private val SOFT_BREAK = charArrayOf('，', '、', '：', ',', ':', '）', ')', '】', '」', ' ')
}

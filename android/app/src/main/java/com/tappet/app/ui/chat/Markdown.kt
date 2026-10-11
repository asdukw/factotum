package com.tappet.app.ui.chat

/**
 * 极简 Markdown 块模型。
 *
 * 只覆盖模型实际会写的那一档：标题 / 段落 / 列表 / 引用 / 代码块 / 分隔线。
 * 表格、脚注、HTML 之类需要的时候再往这里加。
 */
sealed interface MarkdownBlock {
  data class Paragraph(val text: String) : MarkdownBlock

  data class Heading(val level: Int, val text: String) : MarkdownBlock

  data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

  data class BulletList(val items: List<String>) : MarkdownBlock

  data class NumberedList(val items: List<String>) : MarkdownBlock

  data class Quote(val text: String) : MarkdownBlock

  data object Divider : MarkdownBlock
}

/** 行内样式片段；渲染层把它翻成 AnnotatedString。 */
sealed interface InlineToken {
  data class Text(val text: String) : InlineToken

  data class Code(val text: String) : InlineToken

  data class Bold(val text: String) : InlineToken

  data class Italic(val text: String) : InlineToken

  data class Link(val text: String, val url: String) : InlineToken
}

/**
 * 纯 Kotlin 的 Markdown 解析（不依赖 Android，可直接跑 JVM 单测）。
 *
 * 解析有意“宽容”：流式输出会把没写完的代码围栏、没闭合的 ** 一并送进来，
 * 这时按“直到文本结尾”处理，等后续增量到了再重排。
 */
object Markdown {
  fun parse(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
      if (paragraph.isNotEmpty()) {
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
      }
    }

    var index = 0
    while (index < lines.size) {
      val raw = lines[index]
      val trimmed = raw.trim()

      when {
        trimmed.startsWith(FENCE) || trimmed.startsWith(TILDE_FENCE) -> {
          flushParagraph()
          val marker = trimmed.take(3)
          val language = trimmed.removePrefix(marker).trim().ifBlank { null }
          val code = mutableListOf<String>()
          index++
          while (index < lines.size && !lines[index].trim().startsWith(marker)) {
            code += lines[index]
            index++
          }
          if (index < lines.size) index++ // 跳过收尾围栏；没闭合时到文件尾为止
          blocks += MarkdownBlock.CodeBlock(language, code.joinToString("\n"))
        }

        trimmed.isEmpty() -> {
          flushParagraph()
          index++
        }

        isDivider(trimmed) -> {
          flushParagraph()
          blocks += MarkdownBlock.Divider
          index++
        }

        headingLevel(trimmed) > 0 -> {
          flushParagraph()
          val level = headingLevel(trimmed)
          blocks += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
          index++
        }

        trimmed.startsWith(">") -> {
          flushParagraph()
          val quote = mutableListOf<String>()
          while (index < lines.size && lines[index].trim().startsWith(">")) {
            quote += lines[index].trim().removePrefix(">").trimStart()
            index++
          }
          blocks += MarkdownBlock.Quote(quote.joinToString("\n"))
        }

        bulletText(trimmed) != null -> {
          flushParagraph()
          val items = mutableListOf<String>()
          while (index < lines.size && bulletText(lines[index].trim()) != null) {
            items += bulletText(lines[index].trim()).orEmpty()
            index++
          }
          blocks += MarkdownBlock.BulletList(items)
        }

        numberedText(trimmed) != null -> {
          flushParagraph()
          val items = mutableListOf<String>()
          while (index < lines.size && numberedText(lines[index].trim()) != null) {
            items += numberedText(lines[index].trim()).orEmpty()
            index++
          }
          blocks += MarkdownBlock.NumberedList(items)
        }

        else -> {
          paragraph += trimmed
          index++
        }
      }
    }

    flushParagraph()
    return blocks
  }

  /** 行内解析：`code` / **粗** / *斜* / [文字](链接)。不做嵌套，够用即可。 */
  fun inline(source: String): List<InlineToken> {
    val tokens = mutableListOf<InlineToken>()
    val plain = StringBuilder()

    fun flushPlain() {
      if (plain.isNotEmpty()) {
        tokens += InlineToken.Text(plain.toString())
        plain.clear()
      }
    }

    var index = 0
    while (index < source.length) {
      val char = source[index]
      when {
        char == BACKTICK -> {
          val end = source.indexOf(BACKTICK, index + 1)
          if (end > index + 1) {
            flushPlain()
            tokens += InlineToken.Code(source.substring(index + 1, end))
            index = end + 1
          } else {
            plain.append(char)
            index++
          }
        }

        source.startsWith("**", index) -> {
          val end = source.indexOf("**", index + 2)
          if (end > index + 2) {
            flushPlain()
            tokens += InlineToken.Bold(source.substring(index + 2, end))
            index = end + 2
          } else {
            plain.append(char)
            index++
          }
        }

        char == '*' -> {
          val end = source.indexOf('*', index + 1)
          if (end > index + 1) {
            flushPlain()
            tokens += InlineToken.Italic(source.substring(index + 1, end))
            index = end + 1
          } else {
            plain.append(char)
            index++
          }
        }

        char == '[' -> {
          val close = source.indexOf(']', index + 1)
          val urlStart = if (close >= 0 && close + 1 < source.length) close + 1 else -1
          if (close > index && urlStart > 0 && source[urlStart] == '(') {
            val urlEnd = source.indexOf(')', urlStart + 1)
            if (urlEnd > urlStart + 1) {
              flushPlain()
              tokens += InlineToken.Link(source.substring(index + 1, close), source.substring(urlStart + 1, urlEnd))
              index = urlEnd + 1
            } else {
              plain.append(char)
              index++
            }
          } else {
            plain.append(char)
            index++
          }
        }

        else -> {
          plain.append(char)
          index++
        }
      }
    }

    flushPlain()
    return tokens
  }

  private fun headingLevel(trimmed: String): Int {
    var level = 0
    while (level < trimmed.length && level < MAX_HEADING && trimmed[level] == '#') level++
    // `#` 后面必须跟空格，否则 `#tag` 会被误判成标题
    return if (level in 1..MAX_HEADING && trimmed.getOrNull(level) == ' ') level else 0
  }

  private fun isDivider(trimmed: String): Boolean {
    if (trimmed.length < 3) return false
    val char = trimmed[0]
    if (char != '-' && char != '*' && char != '_') return false
    return trimmed.all { it == char }
  }

  /** `- 内容` / `* 内容` / `+ 内容`；不是列表就返回 null。 */
  private fun bulletText(trimmed: String): String? {
    val marker = trimmed.getOrNull(0) ?: return null
    if (marker != '-' && marker != '*' && marker != '+') return null
    if (trimmed.getOrNull(1) != ' ') return null
    return trimmed.drop(2).trim()
  }

  /** `1. 内容` / `1) 内容`；不是有序列表就返回 null。 */
  private fun numberedText(trimmed: String): String? {
    var index = 0
    while (index < trimmed.length && trimmed[index].isDigit()) index++
    if (index == 0 || index > 3) return null
    val separator = trimmed.getOrNull(index) ?: return null
    if (separator != '.' && separator != ')') return null
    if (trimmed.getOrNull(index + 1) != ' ') return null
    return trimmed.drop(index + 2).trim()
  }

  private const val MAX_HEADING = 6
  private const val FENCE = "```"
  private const val TILDE_FENCE = "~~~"
  private const val BACKTICK = '`'
}

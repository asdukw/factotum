package com.tappet.app.voice

/**
 * 识别结果的**文本清理**：纯函数，JVM 单测钉住（`RecognizedTextTest`）。
 *
 * 这一步不是洁癖，是三种模型各自的输出习惯：
 *
 * 1. **中文里的空格**：whisper 系会把中文按词切开（「打开 设置 页面」），而这句话是要
 *    发回给模型的指令 —— 空格留着不影响理解，但用户看到的输入框里是一串断开的字，
 *    而且中文标点前后多出来的空格会让文本看着像坏的。只删**夹在两个中日韩字符之间**的
 *    空格，英文单词之间的不动（「打开 Settings 页面」要保留）。
 * 2. **静音时的占位符**：whisper 对没人说话的片段会回 `[BLANK_AUDIO]` / `[ Silence ]` /
 *    `(音乐)` 这类标记。它们不是识别结果，直接当空处理。
 * 3. **首尾空白**：模型常在句首留一个空格。
 */
internal object RecognizedText {
  /**
   * 没人说话时模型可能吐出来的占位符（小写、去掉标点与空格后比对）。
   * 只列**明确表示「这里没有语音」**的那些，不猜别的。
   */
  private val NON_SPEECH_MARKERS =
    setOf(
      "blank_audio",
      "silence",
      "no speech",
      "nospeech",
      "music",
      "inaudible",
      "blank",
      "empty",
      "unknown",
      // 中文写法：whisper 对中文语音里的纯音乐段会回「(音乐)」这种。
      "音乐",
      "静音",
    )

  /**
   * 清理一段识别结果。**返回空串 = 这一段没有可用内容**，调用方据此说「没听清」，
   * 而不是往输入框里塞一个 `[BLANK_AUDIO]`。
   */
  fun clean(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (isNonSpeechMarker(trimmed)) return ""
    return collapseCjkSpaces(trimmed)
  }

  /**
   * 把一段听写结果接到输入框已有内容的后面。
   *
   * **追加而不是覆盖**：用户可能已经打了半句，也可能连着说了两段 —— 覆盖式回填会让
   * 第一段凭空消失，而且没有任何提示。中间补一个空格，只在已有内容不以空白结尾时补
   * （用户自己敲了空格就别再补一个）。
   */
  fun appendToInput(
    existing: String,
    dictated: String,
  ): String {
    if (dictated.isBlank()) return existing
    if (existing.isBlank()) return dictated
    return if (existing.last().isWhitespace()) existing + dictated else "$existing $dictated"
  }

  private fun isNonSpeechMarker(text: String): Boolean {
    val key = text.lowercase().filter { it.isLetterOrDigit() || it == '_' }
    return key in NON_SPEECH_MARKERS
  }

  /** 删掉中日韩字符之间的空格（含全角空格）。 */
  private fun collapseCjkSpaces(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
      val char = text[index]
      if (isSpace(char) && out.isNotEmpty() && index + 1 < text.length) {
        val before = out.last()
        val after = text[index + 1]
        if (isCjk(before) && isCjk(after)) {
          index++ // 丢掉这个空格
          continue
        }
      }
      out.append(char)
      index++
    }
    return out.toString()
  }

  /** 半角或全角空格。 */
  private fun isSpace(char: Char): Boolean = char == ' ' || char == '　'

  /**
   * 是不是中日韩字符。判的是**字符本身**，不是「这段文本是不是中文」——
   * 一句话里混着英文时，只处理中文那一侧的空格。
   */
  private fun isCjk(char: Char): Boolean {
    val code = char.code
    return (code in 0x4E00..0x9FFF) || // 中日韩统一表意文字
      (code in 0x3400..0x4DBF) || // 扩展 A
      (code in 0x3040..0x30FF) || // 平假名 / 片假名
      (code in 0xAC00..0xD7AF) || // 谚文
      (code in 0x3000..0x303F) || // 中日韩标点（、。「」等）
      (code in 0xFF00..0xFFEF) // 全角字符
  }
}

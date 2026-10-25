package com.factotum.app.voice

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
 * 4. **非语音的音频事件**：SenseVoice 在结果里直接给了 `event` 字段（`<|BGM|>` / `<|Applause|>` /
 *    `<|Laughter|>` 这类），比上面那张手写的字符串表可靠 —— 它是模型自己的判定。
 *    见 [clean] 的 `event` 参数。
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
   * SenseVoice 的**非语音**音频事件（`event` 字段的取值）。
   *
   * 上游的记号是 `<|BGM|>` 这种，比较前先剥掉 `<|` / `|>` 再小写（[normalizeEvent]）——
   * 钉死带尖括号的写法等于把上游哪天改记号的风险接到自己身上。
   *
   * **只列非语音的，且只按「认识」判定**：`<|Speech|>` 不是非语音，而空串 / 认不出的记号
   * 一律放行，交给上面那张文本表兜底。反过来写（「不是 Speech 就当非语音」）会在上游
   * 加一个新语音类记号时把正常说话判成噪声，而那种错用户只能看到「没听清」。
   */
  private val NON_SPEECH_EVENTS =
    setOf(
      "bgm",
      "applause",
      "laughter",
      "cry",
      "sneeze",
      "breath",
      "cough",
    )

  /**
   * 清理一段识别结果。**返回空串 = 这一段没有可用内容**，调用方据此说「没听清」，
   * 而不是往输入框里塞一个 `[BLANK_AUDIO]`。
   *
   * [event] 是引擎给的音频事件（只有 SenseVoice 会填，另外两种模型回空串）：
   * 它说「这段是音乐 / 掌声」时**不看文本直接判空** —— 模型对这类片段经常一边打上事件记号、
   * 一边吐出一句像模像样的「歌词」，而那正是要挡的东西。
   */
  fun clean(
    raw: String,
    event: String = "",
  ): String {
    if (isNonSpeechEvent(event)) return ""
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (isNonSpeechMarker(trimmed)) return ""
    return collapseCjkSpaces(trimmed)
  }

  /**
   * 把几段识别结果拼成一句。**按段识别之后必须经过它**：每段是各自解码出来的，
   * 直接 `joinToString("")` 会在英文段之间粘成一个词，而 `joinToString(" ")` 又会在
   * 中文段之间留下空格 —— 复用 [collapseCjkSpaces] 两头都对。
   */
  fun joinSegments(parts: List<String>): String = collapseCjkSpaces(parts.filter { it.isNotBlank() }.joinToString(" "))

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

  /**
   * 把模型给的**记号**（语种 / 音频事件）归一成能直接比较的形态：`<|zh|>` 与 `zh` 都变成 `zh`。
   *
   * 两种写法是**真的都会出现**（真机实测：sense-voice 的语种回 `<|zh|>`，whisper 回 `zh`），
   * 而这两个字段的用处就是**跨模型对比** —— 留着两种写法，对比时先要人肉认一遍格式。
   * 认不出的形态原样留着（于是不会命中 [NON_SPEECH_EVENTS]）。
   */
  fun normalizeMarker(raw: String): String =
    raw.trim().removePrefix("<|").removeSuffix("|>").trim().lowercase()

  private fun isNonSpeechEvent(event: String): Boolean = normalizeMarker(event) in NON_SPEECH_EVENTS

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

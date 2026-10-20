package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 识别结果清理的纯 JVM 单测。
 *
 * 三种模型的输出习惯不一样（whisper 会把中文按词切开、静音时回占位符），
 * 而清理错了的表现是「输入框里出现一串断开的中文」或者「一个 [BLANK_AUDIO] 被发给了 agent」——
 * 都能在 JVM 上验，没有理由留到真机上靠眼睛发现。
 */
class RecognizedTextTest {
  @Test
  fun `中文词之间的空格被删掉`() {
    assertEquals("打开设置页面", RecognizedText.clean("打开 设置 页面"))
  }

  @Test
  fun `英文单词之间的空格留着`() {
    // 夹在中文与英文之间的那个空格也留着：删了会变成「打开Settings页面」，
    // 而用户看到的是一个被粘住的英文单词。
    assertEquals("打开 Settings 页面", RecognizedText.clean("打开 Settings 页面"))
  }

  @Test
  fun `首尾空白被去掉`() {
    assertEquals("你好", RecognizedText.clean("  你好\n"))
  }

  @Test
  fun `静音占位符当空处理`() {
    // 这些是模型对「这里没有语音」的表达，不是识别结果 —— 它们不该进输入框。
    assertEquals("", RecognizedText.clean("[BLANK_AUDIO]"))
    assertEquals("", RecognizedText.clean("[ Silence ]"))
    assertEquals("", RecognizedText.clean("(音乐)"))
    assertEquals("", RecognizedText.clean(""))
    assertEquals("", RecognizedText.clean("   "))
  }

  @Test
  fun `占位符只按整串匹配`() {
    // 「静音」是一个正常的词：说「打开静音」时不该被当成占位符丢掉。
    assertEquals("打开静音", RecognizedText.clean("打开静音"))
  }

  @Test
  fun `听写结果追加到输入框已有内容后面`() {
    assertEquals("打开设置", RecognizedText.appendToInput("", "打开设置"))
    assertEquals("打开设置", RecognizedText.appendToInput("   ", "打开设置"))
    assertEquals("帮我 打开设置", RecognizedText.appendToInput("帮我", "打开设置"))
    // 用户自己敲了空格就别再补一个
    assertEquals("帮我 打开设置", RecognizedText.appendToInput("帮我 ", "打开设置"))
    // 没听出东西时不动已有内容
    assertEquals("帮我", RecognizedText.appendToInput("帮我", ""))
  }

  @Test
  fun `非语音的音频事件直接判空`() {
    // SenseVoice 在结果里直接给了事件记号。模型对音乐段经常一边打 <|BGM|> 一边吐出一句
    // 像模像样的「歌词」—— 有事件记号时**不看文本**直接判空，那正是要挡的东西。
    assertEquals("", RecognizedText.clean("啦啦啦", "<|BGM|>"))
    assertEquals("", RecognizedText.clean("哈哈哈", "<|Laughter|>"))
    assertEquals("", RecognizedText.clean("谢谢大家", "<|Applause|>"))
  }

  @Test
  fun `人声事件不判空`() {
    assertEquals("打开设置", RecognizedText.clean("打开设置", "<|Speech|>"))
  }

  @Test
  fun `认不出的记号与空串都放行，交给文本表兜底`() {
    // 上游哪天加一个新的语音类记号，这里必须继续认它说的是话 —— 反过来写
    // （「不是 Speech 就当非语音」）会把正常说话判成噪声，而用户只能看到「没听清」。
    assertEquals("打开设置", RecognizedText.clean("打开设置", "<|Singing|>"))
    assertEquals("打开设置", RecognizedText.clean("打开设置", ""))
    assertEquals("", RecognizedText.clean("[BLANK_AUDIO]", ""))
  }

  @Test
  fun `记号归一：带尖括号与不带的两种写法是同一件事`() {
    // 真机实测两种都会出现：sense-voice 的语种回 `<|zh|>`，whisper 回 `zh`。
    // 这两个字段的用处就是跨模型对比，留着两种写法等于让人肉认格式。
    assertEquals("zh", RecognizedText.normalizeMarker("<|zh|>"))
    assertEquals("zh", RecognizedText.normalizeMarker("zh"))
    assertEquals("speech", RecognizedText.normalizeMarker("<|Speech|>"))
    assertEquals("", RecognizedText.normalizeMarker(""))
  }

  @Test
  fun `多段拼起来时中文之间不留空格、英文之间留`() {
    // 按段识别之后，每段是各自解码的：直接 joinToString("") 会把英文粘成一个词，
    // 而 joinToString(" ") 又会在中文段之间留下空格。
    assertEquals("打开设置", RecognizedText.joinSegments(listOf("打开", "设置")))
    assertEquals("open settings", RecognizedText.joinSegments(listOf("open", "settings")))
    assertEquals("打开 Settings 页面", RecognizedText.joinSegments(listOf("打开", "Settings", "页面")))
  }

  @Test
  fun `拼接时丢掉空段`() {
    // 某一段没听出东西（静音、纯音乐）时不该在中间留下一个空格。
    assertEquals("打开设置", RecognizedText.joinSegments(listOf("打开", "", "  ", "设置")))
    assertEquals("", RecognizedText.joinSegments(emptyList()))
    assertEquals("", RecognizedText.joinSegments(listOf("", " ")))
  }
}

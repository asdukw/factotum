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
}

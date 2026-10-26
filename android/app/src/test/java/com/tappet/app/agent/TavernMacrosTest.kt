package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 宏引擎的单测。
 *
 * 这一层的错都长得一样：**渲染结果看着像正常文本**，于是没人发现 `{{random}}` 其实一直没生效，
 * 直到某张卡按设计该出现的随机事件永远不出现。所以每条规则都钉死输出，而不是只断言「不含花括号」。
 */
class TavernMacrosTest {
  /** 固定种子：`{{random::a::b::c}}` 这类断言必须能重复。 */
  private fun random() = Random(7)

  private fun render(text: String, vars: TavernVars = TavernVars(), millis: Long = 0L): String =
    TavernMacros.render(text, vars, now = millis, random = random())

  @Test
  fun `random without args is a decimal between zero and one`() {
    val value = render("{{random}}").toDouble()
    assertTrue("0..1，实际 $value", value in 0.0..1.0)
  }

  @Test
  fun `random with args picks one of them`() {
    val value = render("{{random::晴::雨::雪}}")
    assertTrue("必须原样取一个参数：$value", value in setOf("晴", "雨", "雪"))
  }

  @Test
  fun `random accepts the comma form`() {
    val value = render("{{random::甲,乙,丙}}")
    assertTrue("逗号写法是 ST 的老习惯：$value", value in setOf("甲", "乙", "丙"))
  }

  @Test
  fun `pick only chooses non blank args`() {
    repeat(20) {
      val value = TavernMacros.render("{{pick::::甲}}", TavernVars(), 0L, Random(it))
      assertEquals("空段不能被抽中", "甲", value)
    }
  }

  @Test
  fun `roll sums the dice and the bonus`() {
    val value = render("{{roll:2d6+3}}").toInt()
    assertTrue("2d6+3 落在 5..15，实际 $value", value in 5..15)
  }

  @Test
  fun `roll with a broken spec degrades to zero`() {
    assertEquals("作者的 {{roll:心情}} 不该把整段 prompt 弄崩", "0", render("{{roll:心情}}"))
  }

  @Test
  fun `time and date come from the injected clock`() {
    // 2024-01-02 03:04 UTC
    val millis = 1_704_167_040_000L
    val out = render("{{date}} {{time}}", millis = millis)
    assertTrue("格式必须可读：$out", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(out))
  }

  @Test
  fun `setvar writes nothing into the text and getvar reads it back in order`() {
    val vars = TavernVars()
    val out = render("{{setvar::情绪::不安}}她垂下眼睛。{{getvar::情绪}}", vars)
    assertEquals("写操作不该在正文里留痕迹", "她垂下眼睛。不安", out)
    assertTrue(vars.dirty)
    assertEquals("不安", vars.get("情绪"))
  }

  @Test
  fun `setvar before getvar matters in the same line`() {
    val vars = TavernVars(mapOf("名字" to "旧"))
    assertEquals("旧新", render("{{getvar::名字}}{{setvar::名字::新}}{{getvar::名字}}", vars))
  }

  @Test
  fun `addvar incvar decvar treat the value as a counter`() {
    val vars = TavernVars()
    val out = render("{{setvar::好感::5}}{{addvar::好感::3}}{{incvar::好感}}{{decvar::好感}}", vars)
    assertEquals("", out)
    assertEquals("5 + 3 + 1 - 1", "8", vars.get("好感"))
  }

  @Test
  fun `non numeric values count as zero for addvar`() {
    val vars = TavernVars(mapOf("好感" to "很高"))
    render("{{addvar::好感::2}}", vars)
    assertEquals("2", vars.get("好感"))
  }

  @Test
  fun `unknown macros are left for the template layer to bracket`() {
    assertEquals("{{getglobalvar::x}}", render("{{getglobalvar::x}}"))
  }

  @Test
  fun `nested setvar values keep their separators`() {
    val vars = TavernVars()
    render("{{setvar::记录::上午::下雨}}", vars)
    assertEquals("多段值用 :: 续接，不能只留第一段", "上午::下雨", vars.get("记录"))
  }

  @Test
  fun `newline becomes a real line break`() {
    assertEquals("a\nb", render("a{{newline}}b"))
  }

  @Test
  fun `vars are not marked dirty when the value does not change`() {
    val vars = TavernVars(mapOf("情绪" to "平静"))
    render("{{setvar::情绪::平静}}", vars)
    assertFalse("值没变就不该触发回存", vars.dirty)
  }
}

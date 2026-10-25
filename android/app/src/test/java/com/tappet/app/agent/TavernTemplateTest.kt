package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板渲染的规则单测。
 *
 * 这套语法的使用者是**手写 prompt 的人**：写错了不能静默变成空、不能把卡正文里的花括号当变量，
 * 条件块漏了闭合也不能把界面转死。这三件事都在这里钉住。
 */
class TavernTemplateTest {
  @Test
  fun variablesAreSubstituted() {
    val out = TavernTemplate.render("你扮演 {{char}}，对 {{user}} 说话。", mapOf("char" to "沈清弦", "user" to "用户"))

    assertEquals("你扮演 沈清弦，对 用户 说话。", out)
  }

  @Test
  fun undefinedVariablesStayVisible() {
    // 写错变量名要让写的人看见，而不是变成空串；但花括号本身会被转全角（防模型当成指令）
    val out = TavernTemplate.render("你说 {{charr}}", mapOf("char" to "沈清弦"))
    assertTrue(out.contains("charr"))
    assertEquals("你说 ｛｛charr｝｝", out)
  }

  @Test
  fun conditionalBlockDisappearsWhenValueIsBlank() {
    val values = mapOf("memory" to "")
    val out = TavernTemplate.render("前\n{{#memory}}【记得】{{memory}}{{/memory}}\n后", values)

    assertEquals("前\n\n后", out)
  }

  @Test
  fun conditionalBlockKeepsContentWhenValuePresent() {
    val values = mapOf("memory" to "她答应周三见面")
    val out = TavernTemplate.render("{{#memory}}【记得】{{memory}}{{/memory}}", values)

    assertEquals("【记得】她答应周三见面", out)
  }

  @Test
  fun nestedBlocksExpandInsideOut() {
    val values = mapOf("first" to "1", "first_mes" to "……你也留下来了。", "memory" to "")
    val out =
      TavernTemplate.render(
        "{{#first}}【开场白】{{first_mes}}{{/first}}{{#memory}}【记得】{{memory}}{{/memory}}",
        values,
      )

    assertEquals("【开场白】……你也留下来了。", out)
  }

  @Test
  fun unclosedBlockDoesNotHangOrSwallowTheRest() {
    // 用户漏了闭合标签：块不展开，剩下的正文照常输出，且渲染函数必须返回
    val out = TavernTemplate.render("开始\n{{#memory}}没收尾的块\n后面的话", mapOf("memory" to "x"))

    assertTrue(out.contains("后面的话"))
  }

  @Test
  fun leftoverBracesBecomeFullWidth() {
    // 卡正文里常带 {{random}} 这类我们没实现的宏；藏着它们会被模型当成指令
    val out = TavernTemplate.render("{{char}} 说：{{random}}", mapOf("char" to "沈清弦"))

    assertFalse(out.contains("{{"))
    assertTrue(out.contains("｛｛random｝｝"))
  }

  @Test
  fun whitespaceInsideTheBracesIsTolerated() {
    assertEquals("沈清弦", TavernTemplate.render("{{ char }}", mapOf("char" to "沈清弦")))
  }

  @Test
  fun defaultPresetRendersEverySectionItDeclares() {
    val card =
      CharacterCard(
        id = "t",
        name = "沈清弦",
        description = "校纪纠察队副队长。",
        personality = "清冷。",
        scenario = "夜里十一点的走廊。",
        firstMes = "……你也留下来了。",
        world = listOf(WorldEntry(content = "这所学校十点熄灯。", constant = true)),
      )

    val messages = TavernPrompt.build(card, memory = "她答应周三见面。", turns = listOf(TavernMessage(TavernMessage.USER, "你好")))

    val system = messages.first().content
    listOf("【身份与外貌】", "【性格与说话方式】", "【当前场景】", "【开场白】", "【世界设定·常驻】", "【你记得的事】")
      .forEach { section -> assertTrue("默认模板应当包含 $section", system.contains(section)) }
    // 后置指令在最后一条
    assertTrue(messages.last().content.contains("【扮演规则】"))
    assertEquals(TavernMessage.SYSTEM, messages.last().role)
  }

  @Test
  fun customPresetCanDropASectionEntirely() {
    val card = CharacterCard(id = "t", name = "A", description = "描述甲", personality = "性格乙")
    val preset =
      TavernPreset(
        id = "custom",
        name = "只要人设",
        system = "扮演 {{char}}\n{{description}}",
        postHistory = "保持角色。",
      )

    val messages = TavernPrompt.build(card, "", listOf(TavernMessage(TavernMessage.USER, "hi")), preset)

    assertTrue(messages.first().content.contains("描述甲"))
    assertFalse(messages.first().content.contains("性格乙"))
    assertEquals("保持角色。", messages.last().content)
  }
}

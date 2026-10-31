package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格拼装与 soul 文件解析。
 *
 * 这两条是「人格切换」里唯一不用真机就能钉住的部分，也是**最容易悄悄坏掉**的部分：
 * - 拼装顺序错了（操作契约跑到 soul 前面），人格会把设备规则顶掉；
 * - 段的空态处理错了，系统提示开头多一个空行，某些模型会当成「没有系统提示」；
 * - 解析认不出头部，用户的 soul.md 会被整份当成名字。
 *
 * 真机侧的验证只回答「换人格后它说话变了吗」，回答不了这些。
 */
class PersonaComposeTest {
  @Test
  fun `compose puts soul first then operating then tone`() {
    val composed = PersonaStore.compose(soulBody = "你是小猫。", operating = "先读屏。", tone = "短句。")
    assertEquals("你是小猫。\n\n先读屏。\n\n短句。", composed)
  }

  @Test
  fun `compose drops the tone section when it is off`() {
    val composed = PersonaStore.compose(soulBody = "你是小猫。", operating = "先读屏。", tone = null)
    assertEquals("你是小猫。\n\n先读屏。", composed)
    assertFalse(composed.contains("短句"))
  }

  @Test
  fun `compose never starts with a blank line`() {
    val composed = PersonaStore.compose(soulBody = "   ", operating = "先读屏。", tone = "  ")
    assertEquals("先读屏。", composed)
    assertTrue(composed.none { it == '\n' } || !composed.startsWith("\n"))
  }

  @Test
  fun `parse soul reads front matter and body`() {
    val soul =
      PersonaStore.parseSoul(
        id = "companion",
        raw = "---\nname: 陪伴\nsummary: 温和\n---\n你是朋友。\n第二行。\n",
      )
    assertEquals("陪伴", soul.name)
    assertEquals("温和", soul.summary)
    assertEquals("你是朋友。\n第二行。", soul.body)
    assertTrue(soul.builtin)
  }

  @Test
  fun `parse soul without front matter keeps the whole text`() {
    val soul = PersonaStore.parseSoul(id = "raw", raw = "直接粘贴的一段人设\n没有头部")
    assertEquals("raw", soul.name)
    assertEquals("", soul.summary)
    assertEquals("直接粘贴的一段人设\n没有头部", soul.body)
  }

  @Test
  fun `parse soul ignores an unterminated front matter marker`() {
    val soul = PersonaStore.parseSoul(id = "half", raw = "---\nname: x\n正文还没结束")
    assertEquals("half", soul.name)
    assertTrue(soul.body.contains("---"))
  }

  @Test
  fun `operating contract carries the device rules and no identity`() {
    val operating = PersonaStore.OPERATING_CONTRACT
    // 操作契约定的是「怎么干活」，所以必须带着主路径；身份那句属于 soul，不能出现在这里。
    assertTrue(operating.contains("read_ui_tree"))
    assertTrue(operating.contains("tap_node"))
    assertTrue(operating.contains("tap_target"))
    assertFalse(operating.contains("你是 zoneu"))
  }

  @Test
  fun `operating contract retains current device time and absolute time rules`() {
    val operating = PersonaStore.OPERATING_CONTRACT
    assertTrue(operating.contains("{{device_now}}"))
    assertTrue(operating.contains("ISO 8601"))
    assertTrue(operating.contains("add_calendar_event"))
    assertTrue(operating.contains("set_alarm"))
  }

  @Test
  fun `default soul text is not empty so the default persona still has an identity`() {
    val defaultSoul = PersonaStore.parseSoul(id = PersonaStore.DEFAULT_ID, raw = "你是 zoneu。")
    assertEquals(PersonaStore.DEFAULT_ID, defaultSoul.id)
    assertNull(defaultSoul.summary.ifEmpty { null })
  }

  @Test
  fun worldCastUsesExactOrderedIdentityAndExcludesAllOldWorldContent() {
    val first = CharacterCard(
      id = "one",
      name = "First",
      description = "First identity",
      world = listOf(
        WorldEntry(content = "PRIVATE_CONSTANT", constant = true),
        WorldEntry(content = "PRIVATE_KEYWORD", keys = listOf("First")),
      ),
    )
    val second = CharacterCard(id = "two", name = "Second", description = "Second identity", world = first.world)
    val cast = worldPersonaCast(listOf(first, second), "Lead memory")
    assertEquals(listOf("one", "two"), cast.ids)
    assertTrue(cast.body.contains("First identity"))
    assertTrue(cast.body.contains("Second identity"))
    assertTrue(cast.body.contains("Lead memory"))
    assertTrue(cast.body.contains(GroupCast.rule(listOf(first, second))))
    assertFalse(cast.body.contains("PRIVATE_CONSTANT"))
    assertFalse(cast.body.contains("PRIVATE_KEYWORD"))
    assertEquals(cast, worldPersonaCast(listOf(first.copy(world = emptyList()), second), "Lead memory"))
    assertFalse(cast == worldPersonaCast(listOf(second, first), "Lead memory"))
    assertFalse(cast == worldPersonaCast(listOf(first, second), "Changed memory"))
  }

  @Test
  fun worldSingleCastRendersTheSameMemoryWithoutGroupRules() {
    val card = CharacterCard(id = "single", name = "Solo", description = "Identity")
    assertEquals(CharacterStore.renderPrompt(card, "Memory"), worldPersonaCast(listOf(card), "Memory").body)
    org.junit.Assert.assertThrows(WorldBookException::class.java) { worldPersonaCast(emptyList(), "") }
    org.junit.Assert.assertThrows(WorldBookException::class.java) { worldPersonaCast(listOf(card, card), "") }
    org.junit.Assert.assertThrows(WorldBookException::class.java) { worldPersonaCast(List(5) { card.copy(id = "$it") }, "") }
  }
}

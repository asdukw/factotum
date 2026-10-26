package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊的拼装。
 *
 * 这里钉的是「几条人设会不会被拼坏」与「规则里有没有把每个人的名字都念一遍」——
 * 后者少了任何一个名字，那个角色在整场戏里就基本不会开口。
 */
class GroupCastTest {
  private fun card(id: String, name: String, world: List<WorldEntry> = emptyList()) =
    CharacterCard(
      id = id,
      name = name,
      description = "$name 的描述",
      personality = "$name 的性格",
      scenario = "$name 的场景",
      firstMes = "$name 的开场白",
      world = world,
    )

  private val aki = card("a", "Aki", listOf(WorldEntry(keys = listOf("雨"), content = "雨")))
  private val sei = card("b", "塞薇拉", listOf(WorldEntry(keys = listOf("船"), content = "船")))

  @Test
  fun `merge keeps every name in the card name and in the persona sections`() {
    val merged = GroupCast.merge(listOf(aki, sei))
    assertTrue("群名要能看出有谁：${merged.name}", merged.name.contains("Aki") && merged.name.contains("塞薇拉"))
    assertTrue("人设要分段标名字", merged.description.contains("【Aki】") && merged.description.contains("【塞薇拉】"))
    assertTrue(merged.personality.contains("【塞薇拉】"))
    assertEquals("群聊不设开场白：谁先说没有答案", "", merged.firstMes)
    assertTrue("备用开场白也要清掉", merged.alternateGreetings.isEmpty())
  }

  @Test
  fun `merge puts both world books together`() {
    val merged = GroupCast.merge(listOf(aki, sei))
    assertEquals(2, merged.world.size)
    assertEquals(setOf("雨", "船"), merged.world.map { it.content }.toSet())
  }

  @Test
  fun `rule names everyone and forbids speaking for others`() {
    val rule = GroupCast.rule(listOf(aki, sei))
    assertTrue(rule, rule.contains("Aki") && rule.contains("塞薇拉"))
    assertTrue("必须要求用名字开头", rule.contains("「名字：」"))
    assertTrue("不能让模型替用户说话", rule.contains("不要替用户说话"))
  }

  @Test
  fun `cast is capped so the system prompt does not explode`() {
    val mixed = (1..8).map { card("c$it", "角色$it") }
    val merged = GroupCast.merge(mixed)
    assertTrue("第 ${GroupCast.MAX} 个人要进来", merged.description.contains("角色${GroupCast.MAX}"))
    assertTrue("超出的不能进来", !merged.description.contains("角色${GroupCast.MAX + 1}"))
  }
}

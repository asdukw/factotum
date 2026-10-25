package com.tappet.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色卡渲染与世界条目筛选。
 *
 * 这些是「看起来能用、实际会坏」的地方：漏了扮演规则，模型会把设定当命令执行；
 * 常驻条目被关键词过滤掉，角色会忽然忘了自己在哪；`{{user}}` 没替换，模型会对着占位符演出。
 */
class CharacterStoreTest {
  private val card =
    CharacterCard(
      id = "t",
      name = "沈砚",
      tagline = "书店老板",
      description = "{{char}} 在城南开旧书店。",
      personality = "话少。",
      scenario = "雨夜，{{user}}推门进来。",
      firstMes = "随便看。",
      world =
        listOf(
          WorldEntry(keys = listOf("书店"), content = "留白书店开了七年。", order = 10, constant = true),
          WorldEntry(keys = listOf("铜铃"), content = "门口的铜铃很闷。", order = 20, constant = false),
          WorldEntry(keys = listOf("码头"), content = "城西有旧码头。", order = 30, constant = false),
        ),
    )

  @Test
  fun `render keeps identity and replace placeholders`() {
    val prompt = CharacterStore.renderPrompt(card)
    assertTrue(prompt.contains("沈砚"))
    assertTrue(prompt.contains("留白书店开了七年"))
    assertFalse("占位符必须被替换，否则模型会对着 {{user}} 演出", prompt.contains("{{user}}"))
    assertFalse(prompt.contains("{{char}}"))
  }

  @Test
  fun `render always states the role-playing rules`() {
    val prompt = CharacterStore.renderPrompt(card)
    assertTrue(prompt.contains("扮演规则"))
    assertTrue("必须给出戏留出口", prompt.contains("出戏"))
    assertTrue("必须允许角色顺便操作手机", prompt.contains("设备工具"))
  }

  @Test
  fun `active world keeps constant entries and keyword hits only`() {
    val lines = CharacterStore.activeWorld(card)
    // 场景里既没有「铜铃」也没有「码头」→ 只有那条 constant 的进来
    assertTrue("实际 ${lines.size} 条：$lines", lines.size == 1)
    assertTrue(lines.any { it.contains("留白书店") })
    assertTrue("场景里没有「码头」，这条不该进", lines.none { it.contains("城西有旧码头") })
    // 关键词命中要标出来，模型才知道这条是「因为此景」而不是永久设定
    assertTrue(lines.none { it.contains("此景相关") })
  }

  @Test
  fun `active world marks entries matched by keyword`() {
    val withBell = card.copy(scenario = "雨夜，门上的铜铃响了。")
    val lines = CharacterStore.activeWorld(withBell)
    assertTrue(lines.any { it.contains("此景相关") && it.contains("铜铃") })
  }

  @Test
  fun `rendering a card without scenario still works`() {
    val bare = card.copy(scenario = "", firstMes = "", world = emptyList(), personality = "", description = "")
    val prompt = CharacterStore.renderPrompt(bare)
    assertTrue(prompt.contains("沈砚"))
    assertTrue(prompt.contains("扮演规则"))
  }
}

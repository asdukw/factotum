package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 宏落在 prompt 里的位置。
 *
 * 单独一组的原因是「宏能不能生效」取决于它跑在**哪一步之前**：卡正文先被
 * `CharacterStore.renderText` 把花括号转成全角，宏就再也没机会了。这类顺序错误
 * 在界面上完全看不出来（正文照样显示，只是 `｛｛random｝｝` 变成了死的引文）。
 */
class TavernPromptMacroTest {
  private val card =
    CharacterCard(
      id = "c1",
      name = "沈清弦",
      tagline = "测试卡",
      description = "她的情绪：{{random::平静::烦躁}}\n{{setvar::心情::不安}}",
      personality = "寡言",
      scenario = "{{date}} 的夜里",
      firstMes = "第一句话",
      world =
        listOf(
          WorldEntry(keys = listOf("雨"), content = "雨夜{{setvar::天气::雨}}里她总在窗边", constant = true),
        ),
    )

  private fun render(turns: List<TavernMessage>, vars: TavernVars = TavernVars()): String =
    TavernPrompt.build(
      card = card,
      memory = "",
      turns = turns,
      random = Random(3),
      state = TavernPrompt.TurnState(vars = vars),
    )
      .joinToString("\n") { it.content }

  @Test
  fun `macros inside card text are evaluated before the bracket pass`() {
    val out = render(listOf(TavernMessage(TavernMessage.USER, "在吗")))
    assertTrue("人设里的 {{random}} 必须变成具体情绪，实际：$out", out.contains("她的情绪：平静") || out.contains("她的情绪：烦躁"))
    assertTrue("场景里的 {{date}} 必须变成日期", Regex("""\d{4}-\d{2}-\d{2} 的夜里""").containsMatchIn(out))
    assertTrue("残留的花括号说明宏没跑到 renderText 之前", !out.contains("{{"))
  }

  @Test
  fun `setvar inside card text feeds later blocks in the same turn`() {
    val vars = TavernVars()
    val out =
      TavernPrompt.build(
        card = card,
        memory = "",
        turns = listOf(TavernMessage(TavernMessage.USER, "下雨了")),
        random = Random(3),
        state = TavernPrompt.TurnState(vars = vars),
      )
        .joinToString("\n") { it.content }
    assertEquals("世界书里的 setvar 也该生效", "雨", vars.get("天气"))
    assertEquals("人设里的 setvar 后写，覆盖前面的值", "不安", vars.get("心情"))
    assertTrue("写操作不能把痕迹留在正文里", !out.contains("setvar"))
  }

  @Test
  fun `the current user line never gains macro output`() {
    val out = TavernPrompt.build(
      card = card,
      memory = "",
      turns = listOf(TavernMessage(TavernMessage.USER, "她在干嘛")),
      random = Random(3),
      state = TavernPrompt.TurnState(vars = TavernVars()),
    )
    assertTrue("历史是已经说过的话，不该被重新渲染", out.any { it.content.contains("她在干嘛") })
  }

  @Test
  fun `tail instruction is appended last and only when non blank`() {
    val withTail =
      TavernPrompt.build(
        card = card,
        memory = "",
        turns = listOf(TavernMessage(TavernMessage.USER, "嗯")),
        state = TavernPrompt.TurnState(tail = TavernDirectives.CONTINUE),
      )
    assertEquals("续写指令必须排在最后一条", TavernDirectives.CONTINUE, withTail.last().content)

    val withoutTail =
      TavernPrompt.build(
        card = card,
        memory = "",
        turns = listOf(TavernMessage(TavernMessage.USER, "嗯")),
        state = TavernPrompt.TurnState(tail = "  "),
      )
    assertTrue("空指令不能发出去（有的提供商会直接 400）", withoutTail.none { it.content.isBlank() })
  }
}

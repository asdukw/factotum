package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡的正文渲染：宏必须被换掉，剩下的花括号必须消失。
 *
 * 这条不是样式问题：dsh 把系统提示当模板，遇到未注册的 `{{xxx}}` 会**整轮失败**
 * （`unknown prompt variable "{{user}}"`）。社区卡里 `{{user}}` / `{{char}}` 到处都是，
 * 漏掉任何一处（比如世界设定）就等于那张卡一开口就报错 —— 用真卡测出来的。
 */
class CharacterStoreRenderTest {
  private val card =
    CharacterCard(
      id = "t",
      name = "沈清弦",
      description = "{{char}} 是学生会长，{{user}} 是她的主人。",
      world =
        listOf(
          WorldEntry(keys = listOf("纠察队"), content = "{{char}} 直属的武装纠察队，只对 {{user}} 效忠。", constant = true),
          WorldEntry(keys = listOf("门"), content = "{{random}} 没注册的宏也不该放过。"),
        ),
    )

  @Test
  fun knownMacrosAreReplaced() {
    assertEquals("沈清弦 是学生会长，用户 是她的主人。", CharacterStore.renderText("{{char}} 是学生会长，{{user}} 是她的主人。", card))
  }

  @Test
  fun unknownMacrosAreEscapedNotDropped() {
    // 换成全角：意思还在，但 dsh 不再把它当模板变量。
    assertEquals("｛｛random｝｝", CharacterStore.renderText("{{random}}", card))
  }

  @Test
  fun renderedPromptHasNoTemplateBraces() {
    val prompt = CharacterStore.renderPrompt(card, memory = "{{char}} 答应过周三见面。")

    assertFalse(prompt.contains("{{"))
    assertFalse(prompt.contains("}}"))
    assertTrue(prompt.contains("沈清弦"))
    assertTrue(prompt.contains("用户"))
    // 记忆是这套渲染的第三条入口：它进了提示，宏也被换掉了。
    assertTrue(prompt.contains("【你记得的事】"))
    assertTrue(prompt.contains("沈清弦 答应过周三见面。"))
  }

  @Test
  fun worldEntriesGoThroughTheSameRenderer() {
    val lines = CharacterStore.activeWorld(card)

    assertTrue(lines.none { it.contains("{{") })
    assertTrue(lines.any { it.contains("沈清弦 直属的武装纠察队") })
  }
}

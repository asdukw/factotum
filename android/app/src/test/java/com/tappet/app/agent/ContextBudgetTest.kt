package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 上下文预算与 token 估算。
 *
 * 这里钉的不是「数字精确到几」——估算本来就是近似 ——而是三条会直接影响体验的行为：
 * 最后一句一定留下、历史不以助手开头、丢过东西必须留一行说明。
 */
class ContextBudgetTest {
  private fun turns(count: Int, chars: Int = 40) =
    (0 until count).map { index ->
      val role = if (index % 2 == 0) TavernMessage.USER else TavernMessage.ASSISTANT
      TavernMessage(role, "第 $index 句：" + "字".repeat(chars))
    }

  @Test
  fun `estimate counts cjk as one token each`() {
    assertEquals("十个汉字约十个 token（含 1 的协议开销）", 11, Tokens.estimate("一二三四五六七八九十"))
  }

  @Test
  fun `estimate counts latin by four letters`() {
    assertEquals("八个字母两个 token", 3, Tokens.estimate("abcdefgh"))
  }

  @Test
  fun `estimate of empty text is zero`() {
    assertEquals(0, Tokens.estimate(""))
  }

  @Test
  fun `format keeps short numbers and shortens thousands`() {
    assertEquals("940", Tokens.format(940))
    assertEquals("1.2k", Tokens.format(1_240))
    assertEquals("12.0k", Tokens.format(11_980))
  }

  @Test
  fun `trim keeps everything when it fits`() {
    val turns = turns(4, chars = 10)
    val trimmed = ContextBudget.trim(turns, budget = 1_000)
    assertEquals(turns, trimmed.turns)
    assertEquals(0, trimmed.dropped)
  }

  @Test
  fun `trim drops the oldest turns and says so`() {
    val turns = turns(40)
    val trimmed = ContextBudget.trim(turns, budget = 150)
    assertTrue("必须真的丢掉一些", trimmed.dropped > 0)
    assertEquals("丢了几条就写几条", "〔更早的 ${trimmed.dropped} 条对话已省略，只保留最近的往来〕", trimmed.turns.first().content)
    assertEquals("最后一句永远是这一轮刚说的话", turns.last(), trimmed.turns.last())
  }

  @Test
  fun `trim never starts the history with an assistant message`() {
    val turns = turns(60)
    val trimmed = ContextBudget.trim(turns, budget = 200)
    val first = trimmed.turns.drop(1).first()
    assertEquals(
      "历史以助手开头时，模型会把它当开场白然后自己往下说",
      TavernMessage.USER,
      first.role,
    )
  }

  @Test
  fun `trim keeps the last turn even when it alone exceeds the budget`() {
    val huge = TavernMessage(TavernMessage.USER, "字".repeat(5_000))
    val trimmed = ContextBudget.trim(listOf(TavernMessage(TavernMessage.ASSISTANT, "开场"), huge), budget = 100)
    assertEquals("塞不进去也要留这一句，否则等于没问", huge, trimmed.turns.last())
  }

  @Test
  fun `trimmed history stays under the budget in practice`() {
    val trimmed = ContextBudget.trim(turns(80, chars = 100), budget = 400)
    val used = trimmed.turns.sumOf { Tokens.estimate(it.content) }
    // 允许超出最后一条的量：单条超预算时仍要保留它
    assertTrue("实际占用 $used 不该远超 400", used < 400 + Tokens.estimate("字".repeat(100)))
  }
}

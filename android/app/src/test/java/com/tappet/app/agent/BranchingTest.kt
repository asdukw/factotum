package com.tappet.app.agent

import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.session.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支线：抄哪一段、抄成什么状态、叫什么名字。
 *
 * 这三件事都在纯函数里，因为出错的方式很隐蔽 —— 多抄一条会让支线一进去就带着「对旧回答的
 * 新回应」，少抄一条会让模型看不见自己在回什么；名字取错则侧栏里两条看起来一模一样。
 */
class BranchingTest {
  private fun row(id: String, role: MessageRole, text: String, status: MessageStatus = MessageStatus.Complete) =
    StoredMessage(
      id = id,
      sessionId = "s",
      seq = 0,
      role = role,
      content = text,
      reasoning = null,
      status = status,
      error = null,
      createdAt = 0,
      finishedAt = null,
    )

  private val rows =
    listOf(
      row("g", MessageRole.Assistant, "（开场）她抬头。"),
      row("u1", MessageRole.User, "你好"),
      row("a1", MessageRole.Assistant, "嗯。"),
      row("u2", MessageRole.User, "接着说"),
      row("a2", MessageRole.Assistant, "好。"),
    )

  @Test
  fun `cut keeps everything up to and including the chosen message`() {
    assertEquals(listOf("g", "u1", "a1"), Branching.cut(rows, "a1").map { it.id })
    assertEquals(rows.map { it.id }, Branching.cut(rows, "a2").map { it.id })
  }

  @Test
  fun `unknown message copies nothing`() {
    assertTrue("找不到就什么都不做，而不是抄半段", Branching.cut(rows, "nope").isEmpty())
  }

  @Test
  fun `a still-streaming message becomes interrupted on the fork`() {
    val half = row("h", MessageRole.Assistant, "（说到一半", MessageStatus.Streaming)
    assertEquals(MessageStatus.Interrupted, Branching.statusOf(half))
    assertEquals("其它状态原样带走", MessageStatus.Complete, Branching.statusOf(rows[1]))
  }

  @Test
  fun `title uses the first user line and is capped`() {
    assertEquals("你好 · 支线", Branching.titleOf(rows))
    val long = listOf(row("u", MessageRole.User, "这是一句非常非常长的开场话，长到必须被截断才行"))
    val title = Branching.titleOf(long)
    assertEquals("${"这是一句非常非常长的开场话，长到必须被截断才行".take(Branching.CAP)} · 支线", title)
  }

  @Test
  fun `title falls back when there is no user line yet`() {
    assertEquals("开场白之后 · 支线", Branching.titleOf(rows.take(1)))
  }

  @Test
  fun `title collapses newlines so the sidebar stays one line`() {
    val multi = listOf(row("u", MessageRole.User, "第一行\n第二行"))
    assertTrue(Branching.titleOf(multi).none { it == '\n' })
  }
}

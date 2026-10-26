package com.tappet.app.agent

import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.session.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 什么时候写前情提要、写哪一段。
 *
 * 阈值错的两个方向都很难看：太早总结（聊两句就压）白花钱还把语气压平；
 * 太晚总结（等到预算已经裁完）等于没写 —— 被裁掉的那段再也回不到提要里。
 */
class RecapPlanTest {
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

  private fun conversation(count: Int) =
    (1..count).map { i -> row("m$i", if (i % 2 == 1) MessageRole.User else MessageRole.Assistant, "第 $i 句") }

  @Test
  fun `a short conversation is not worth a call`() {
    assertNull(RecapPlan.of(conversation(RecapPlan.MIN_MESSAGES), null))
  }

  @Test
  fun `past the threshold it summarizes everything except the recent tail`() {
    val plan = RecapPlan.of(conversation(20), null)!!
    assertEquals("压到倒数第 6 条之前", 20 - RecapPlan.KEEP_RECENT, plan.count)
    assertEquals("记录到这一条的 id", "m${20 - RecapPlan.KEEP_RECENT}", plan.uptoId)
    assertTrue("最近的原文一字不压", !plan.transcript.contains("第 20 句"))
    assertTrue(plan.transcript.contains("第 1 句"))
  }

  @Test
  fun `an existing summary is carried into the new one`() {
    val plan = RecapPlan.of(conversation(20), "她答应过要回来。")!!
    assertTrue(plan.transcript.contains("【已有的前情提要】"))
    assertTrue(plan.transcript.contains("她答应过要回来。"))
  }

  @Test
  fun `blank summaries are ignored`() {
    val plan = RecapPlan.of(conversation(20), "   ")!!
    assertTrue(!plan.transcript.contains("【已有的前情提要】"))
  }

  @Test
  fun `a huge backlog keeps the newest lines and drops the oldest`() {
    val long = (1..40).map { i -> row("L$i", MessageRole.User, "第${i}句" + "字".repeat(400)) }
    val plan = RecapPlan.of(long, null)!!
    assertTrue("总长受上限约束", plan.transcript.length <= RecapPlan.MAX_TRANSCRIPT_CHARS + 600)
    assertTrue("保住靠近现在的那头（切片最后一条）", plan.transcript.contains("第${40 - RecapPlan.KEEP_RECENT}句"))
    assertEquals("uptoId 仍是切片最后一条", "L${40 - RecapPlan.KEEP_RECENT}", plan.uptoId)
  }

  @Test
  fun `manual summarizing accepts a much shorter conversation`() {
    val short = conversation(4)
    assertNull("自动门槛不动", RecapPlan.of(short, null))
    val manual =
      RecapPlan.of(
        short,
        null,
        minMessages = SessionRecap.MANUAL_MIN_MESSAGES,
        keepRecent = SessionRecap.MANUAL_KEEP_RECENT,
      )!!
    assertEquals("手动只留 2 条原文，其余都压", 4 - SessionRecap.MANUAL_KEEP_RECENT, manual.count)
  }

  @Test
  fun `manual mode still refuses a single line`() {
    assertNull(RecapPlan.of(conversation(1), null, minMessages = SessionRecap.MANUAL_MIN_MESSAGES))
  }

  @Test
  fun `roles are labelled so the summary can tell who did what`() {
    val plan = RecapPlan.of(conversation(20), null)!!
    assertTrue(plan.transcript.contains("用户：第 1 句"))
    assertTrue(plan.transcript.contains("角色：第 2 句"))
  }
}

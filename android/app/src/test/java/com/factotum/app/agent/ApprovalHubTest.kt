package com.factotum.app.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ApprovalHub] 的三条语义：决定唤醒等待者、关闭 = 取消、同一时刻只允许一个待审批。
 *
 * 第三条是安全性质：并行工具调用同时要权限时，后一个必须 fail closed（取消），
 * 不能出现「一次点击批准了两件事」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalHubTest {
  private fun pending(tool: String) =
    PendingApproval(
      toolName = tool,
      callId = "call-1",
      reason = "测试用途",
      displayReason = mapOf("zh" to "测试用途"),
    )

  @Test
  fun `a decision resumes the waiter and clears the pending state`() = runTest {
    var result: ApprovalDecision? = null
    val job = launch { result = ApprovalHub.request(pending("mail_send")) }
    runCurrent()
    assertEquals("mail_send", ApprovalHub.pending.value?.toolName)

    ApprovalHub.decide(ApprovalDecision.ALLOW_ONCE)
    job.join()

    assertEquals(ApprovalDecision.ALLOW_ONCE, result)
    assertNull(ApprovalHub.pending.value)
  }

  @Test
  fun `dismiss is a cancellation, not a rejection`() = runTest {
    var result: ApprovalDecision? = null
    val job = launch { result = ApprovalHub.request(pending("run_command")) }
    runCurrent()

    ApprovalHub.dismiss()
    job.join()

    assertEquals(ApprovalDecision.CANCEL, result)
    assertNull(ApprovalHub.pending.value)
  }

  @Test
  fun `a second request is cancelled while one is pending`() = runTest {
    var first: ApprovalDecision? = null
    val firstJob = launch { first = ApprovalHub.request(pending("mail_send")) }
    runCurrent()

    var second: ApprovalDecision? = null
    val secondJob = launch { second = ApprovalHub.request(pending("mail_modify")) }
    runCurrent()
    secondJob.join()
    assertEquals(ApprovalDecision.CANCEL, second)

    ApprovalHub.decide(ApprovalDecision.REJECT)
    firstJob.join()
    assertEquals(ApprovalDecision.REJECT, first)
    assertNull(ApprovalHub.pending.value)
  }
}

package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「全部允许」只可能得到 `allowed-once`，且默认策略绝不自动放行。
 *
 * 这是安全性质：dsh 没有「总是允许」的词汇，自动放行只能来自用户显式打开的开关；
 * 任何新策略加到 [ApprovalPolicy] 时，忘了写分支就会让测试挂掉，而不是悄悄放行。
 */
class ApprovalPolicyTest {
  @Test
  fun `ask never auto approves`() {
    assertNull(autoApprovalOutcome(ApprovalPolicy.ASK))
  }

  @Test
  fun `allow all yields exactly allowed once`() {
    assertEquals(ApprovalDecision.ALLOW_ONCE, autoApprovalOutcome(ApprovalPolicy.ALLOW_ALL))
  }
}

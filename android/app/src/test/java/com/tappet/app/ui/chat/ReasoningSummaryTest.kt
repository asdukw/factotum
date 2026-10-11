package com.tappet.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「思考」折叠块首行摘要的纯函数测试。 */
class ReasoningSummaryTest {
  @Test
  fun `takes the first non-blank line`() {
    assertEquals("先看屏幕", reasoningSummary("\n  先看屏幕  \n再点按钮"))
  }

  @Test
  fun `drops markdown bold markers like the dsh web row`() {
    assertEquals("先读屏，再操作", reasoningSummary("**先读屏，再操作**"))
  }

  @Test
  fun `blank reasoning has an empty summary`() {
    assertEquals("", reasoningSummary("   \n\n"))
  }
}

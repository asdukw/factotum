package com.zoneu.app.connector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailCallbackPageTest {
  @Test
  fun `receipt page is responsive and does not claim connection succeeded`() {
    val html = gmailCallbackPage(denied = false)
    assertTrue(html.contains("已收到授权回调"))
    assertTrue(html.contains("最终是否连接成功，请以 App 内显示为准"))
    assertTrue(html.contains("下一步：返回 zoneu"))
    assertTrue(html.contains("width=device-width"))
    assertTrue(html.contains("prefers-color-scheme: dark"))
    assertTrue(html.contains("lang=\"zh-CN\""))
    assertFalse(html.contains("授权完成"))
    assertFalse(html.contains("{{"))
  }

  @Test
  fun `denial page does not masquerade as success`() {
    val html = gmailCallbackPage(denied = true)
    assertTrue(html.contains("授权未完成"))
    assertTrue(html.contains("查看原因"))
    assertFalse(html.contains("已收到授权回调"))
    assertFalse(html.contains("授权成功"))
  }

  @Test
  fun `callback page is self contained and never loads external code or images`() {
    for (denied in listOf(false, true)) {
      val html = gmailCallbackPage(denied)
      for (forbidden in listOf("<script", "<link", "<img", "http://", "https://", "src=", "href=")) {
        assertFalse("unexpected external resource or script: $forbidden", html.contains(forbidden))
      }
      assertTrue(html.contains("name=\"referrer\" content=\"no-referrer\""))
    }
  }
}

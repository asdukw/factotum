package com.zoneu.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型输出里的链接只放行 http/https/mailto。 */
class MarkdownLinksTest {
  @Test
  fun `web and mail links are allowed`() {
    assertTrue(MarkdownLinks.isAllowed("https://example.com/a_b"))
    assertTrue(MarkdownLinks.isAllowed("http://example.com"))
    assertTrue(MarkdownLinks.isAllowed("mailto:someone@example.com"))
    assertTrue(MarkdownLinks.isAllowed("HTTPS://Example.COM/Path"))
    assertTrue(MarkdownLinks.isAllowed("  https://example.com  "))
  }

  @Test
  fun `dangerous and unknown schemes are rejected`() {
    assertFalse(MarkdownLinks.isAllowed("javascript:alert(1)"))
    assertFalse(MarkdownLinks.isAllowed("JavaScript:alert(1)"))
    assertFalse(MarkdownLinks.isAllowed("intent://scan/#Intent;scheme=zxing;end"))
    assertFalse(MarkdownLinks.isAllowed("file:///data/local/tmp/x"))
    assertFalse(MarkdownLinks.isAllowed("content://com.example/x"))
    assertFalse(MarkdownLinks.isAllowed("telegram://msg"))
  }

  @Test
  fun `relative and malformed links are rejected`() {
    assertFalse(MarkdownLinks.isAllowed(""))
    assertFalse(MarkdownLinks.isAllowed("#section"))
    assertFalse(MarkdownLinks.isAllowed("//example.com"))
    assertFalse(MarkdownLinks.isAllowed("example.com"))
  }
}

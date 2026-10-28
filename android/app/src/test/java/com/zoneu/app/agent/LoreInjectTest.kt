package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书按需检索：命中才带、带的是「背景」而不是「用户说的话」。
 *
 * 这几条断言针对的都是**会被用户看到的效果**，不是实现细节：多带一条 = 模型开始念设定；
 * 少带一条 = 它开始编。
 */
class LoreInjectTest {
  private fun card(vararg entries: WorldEntry) =
    CharacterCard(id = "t", name = "夜班电台", world = entries.toList())

  @Test
  fun onlyKeywordHitEntriesAreInjected() {
    val subject =
      card(
        WorldEntry(keys = listOf("station"), content = "演播室在七楼。"),
        WorldEntry(keys = listOf("灯塔"), content = "灯塔在港口北侧。"),
      )

    val text = LoreInject.augment(subject, "station 那边的门锁了吗")

    assertTrue(text.contains("演播室在七楼"))
    assertFalse(text.contains("灯塔在港口北侧"))
    assertTrue(text.startsWith(LoreInject.MARK_OPEN))
    assertTrue(text.endsWith("station 那边的门锁了吗"))
  }

  @Test
  fun constantEntriesStayOutOfTheMessage() {
    // 常驻条目已经写进系统提示了，再跟着每条消息发一次就是双份 token。
    val subject = card(WorldEntry(keys = listOf("station"), content = "演播室在七楼。", constant = true))

    assertEquals("station 在吗", LoreInject.augment(subject, "station 在吗"))
  }

  @Test
  fun nothingMatchedLeavesTheTextUntouched() {
    val subject = card(WorldEntry(keys = listOf("station"), content = "演播室在七楼。"))

    assertEquals("今天天气不错", LoreInject.augment(subject, "今天天气不错"))
  }

  @Test
  fun earlierUserMessagesCountAsKeywordSource() {
    // 「她呢」自己不含关键词，靠上一句命中 —— 真实的对话里这种指代很常见。
    val subject = card(WorldEntry(keys = listOf("灯塔"), content = "灯塔在港口北侧。"))

    val text = LoreInject.augment(subject, "她呢", recent = listOf("昨天说的那座灯塔还在吗"))

    assertTrue(text.contains("灯塔在港口北侧"))
  }

  @Test
  fun entryCountIsCapped() {
    val many = (1..10).map { WorldEntry(keys = listOf("门"), content = "第 $it 条关于门的事。") }
    val subject = card(*many.toTypedArray())

    val text = LoreInject.augment(subject, "门")

    assertEquals(4, Regex("第 \\d+ 条关于门的事").findAll(text).count())
  }
}

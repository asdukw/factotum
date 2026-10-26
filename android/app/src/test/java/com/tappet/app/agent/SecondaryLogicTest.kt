package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 次要关键词的四种语义与 ST 编号的互转。
 *
 * 这组测试存在的理由是一次真实的读错：以前把 ST 的 `selectiveLogic: 3`（NOT_ANY）
 * 读成 ALL、把 `1`（AND_ALL）读成 ANY。两种错误都不会让条目消失，所以界面上看不出来 ——
 * 只能靠这里钉住数字与语义的对应关系。
 */
class SecondaryLogicTest {
  private fun entry(logic: SecondaryLogic) =
    WorldEntry(
      keys = listOf("学府"),
      content = "校规九条。",
      secondary = listOf("校规", "宿舍"),
      secondaryLogic = logic,
    )

  @Test
  fun `raw numbers follow SillyTavern`() {
    assertEquals(0, SecondaryLogic.ANY.raw())
    assertEquals(1, SecondaryLogic.ALL.raw())
    assertEquals(2, SecondaryLogic.NOT_ALL.raw())
    assertEquals(3, SecondaryLogic.NOT_ANY.raw())
    assertEquals(SecondaryLogic.ALL, SecondaryLogic.fromRaw(1))
    assertEquals(SecondaryLogic.NOT_ALL, SecondaryLogic.fromRaw(2))
    assertEquals(SecondaryLogic.NOT_ANY, SecondaryLogic.fromRaw(3))
    assertEquals("0 与缺省都是 AND_ANY", SecondaryLogic.ANY, SecondaryLogic.fromRaw(0))
    assertEquals(SecondaryLogic.ANY, SecondaryLogic.fromRaw(null))
    assertEquals("越界的数字退化成最宽松的一种，而不是崩掉", SecondaryLogic.ANY, SecondaryLogic.fromRaw(9))
  }

  @Test
  fun `every value survives a round trip through its raw number`() {
    SecondaryLogic.entries.forEach { logic ->
      assertEquals(logic, SecondaryLogic.fromRaw(logic.raw()))
    }
  }

  @Test
  fun `any and all behave as named`() {
    assertTrue(entry(SecondaryLogic.ANY).secondarySatisfied("说到校规"))
    assertTrue(entry(SecondaryLogic.ANY).secondarySatisfied("说到校规和宿舍"))
    assertFalse(entry(SecondaryLogic.ANY).secondarySatisfied("只说学府"))

    assertTrue(entry(SecondaryLogic.ALL).secondarySatisfied("校规和宿舍"))
    assertFalse("少一个就不行", entry(SecondaryLogic.ALL).secondarySatisfied("只说校规"))
  }

  @Test
  fun `not all and not any are the negatives`() {
    assertTrue("只中一个 → 不全中，成立", entry(SecondaryLogic.NOT_ALL).secondarySatisfied("只说校规"))
    assertFalse("两个都中 → 全中，不成立", entry(SecondaryLogic.NOT_ALL).secondarySatisfied("校规和宿舍"))
    assertTrue(entry(SecondaryLogic.NOT_ALL).secondarySatisfied("两样都没提"))

    assertTrue(entry(SecondaryLogic.NOT_ANY).secondarySatisfied("两样都没提"))
    assertFalse(entry(SecondaryLogic.NOT_ANY).secondarySatisfied("只说校规"))
  }

  @Test
  fun `no secondary keys means satisfied whatever the logic is`() {
    SecondaryLogic.entries.forEach { logic ->
      val bare = WorldEntry(keys = listOf("学府"), content = "x", secondary = emptyList(), secondaryLogic = logic)
      assertTrue("$logic", bare.secondarySatisfied("随便聊聊"))
    }
  }

  @Test
  fun `labels are distinct so the editor can show one at a time`() {
    val labels = SecondaryLogic.entries.map { it.label() }
    assertEquals(labels.size, labels.toSet().size)
  }
}

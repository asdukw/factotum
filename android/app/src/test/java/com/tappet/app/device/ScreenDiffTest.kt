package com.tappet.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiffTest {
  /** 只保留差分测试真正变化的维度：文字、纵向位置、可点。 */
  private fun node(ref: Int, text: String? = null, top: Int = 0, bottom: Int = 40, clickable: Boolean = false) =
    ScreenNode(
      ref = ref,
      className = "android.widget.TextView",
      text = text,
      contentDescription = null,
      bounds = NodeBounds(0, top, 100, bottom),
      depth = 0,
      clickable = clickable,
    )

  private fun snapshot(vararg nodes: ScreenNode) = ScreenSnapshot("com.example.app", nodes.toList())

  private fun snapshot(nodes: List<ScreenNode>) = ScreenSnapshot("com.example.app", nodes)

  @Test
  fun `identical screens keep refs and report no churn`() {
    val before = snapshot(node(0, text = "首页"), node(1, text = "设置", clickable = true))
    val after = snapshot(node(0, text = "首页"), node(1, text = "设置", clickable = true))

    val diff = diffSnapshots(before, after)

    assertEquals(listOf(0, 1), diff.nodes.map { it.ref })
    assertEquals(0, diff.churn)
    assertEquals(2, diff.unchanged)
  }

  @Test
  fun `text change reuses the ref and is reported`() {
    val before = snapshot(node(3, text = "未连接"))
    val after = snapshot(node(0, text = "已连接"))

    val diff = diffSnapshots(before, after)

    assertEquals(3, diff.nodes.single().ref)
    assertEquals(1, diff.changed.size)
    assertEquals("未连接", diff.changed.single().before.text)
    assertEquals("已连接", diff.changed.single().after.text)
    assertEquals(0, diff.added.size)
    assertEquals(0, diff.removed.size)
  }

  @Test
  fun `added node gets a fresh ref while old refs stay`() {
    val before = snapshot(node(0, text = "A"), node(1, text = "B"))
    val after = snapshot(node(0, text = "A"), node(1, text = "B"), node(2, text = "C"))

    val diff = diffSnapshots(before, after)

    assertEquals(listOf(0, 1, 2), diff.nodes.map { it.ref })
    assertEquals(listOf(2), diff.added.map { it.ref })
    assertEquals(2, diff.unchanged)
  }

  @Test
  fun `ref counter continues after the highest previous ref`() {
    val before = snapshot(node(5, text = "A"), node(7, text = "B"))
    val after = snapshot(node(0, text = "A"), node(1, text = "B"), node(2, text = "C"))

    val diff = diffSnapshots(before, after)

    assertEquals(listOf(5, 7, 8), diff.nodes.map { it.ref })
    assertEquals(8, diff.added.single().ref)
  }

  @Test
  fun `removed node is reported with its old ref`() {
    val before = snapshot(node(0, text = "A"), node(1, text = "B"))
    val after = snapshot(node(0, text = "A"))

    val diff = diffSnapshots(before, after)

    assertEquals(1, diff.removed.single().ref)
    assertEquals("B", diff.removed.single().text)
    assertEquals(0, diff.added.size)
  }

  @Test
  fun `moved node with same label keeps ref and is reported as changed`() {
    val before = snapshot(node(2, text = "发送", top = 100, bottom = 140))
    val after = snapshot(node(0, text = "发送", top = 300, bottom = 340))

    val diff = diffSnapshots(before, after)

    assertEquals(2, diff.nodes.single().ref)
    assertEquals(1, diff.changed.size)
    assertEquals(0, diff.added.size)
    assertEquals(0, diff.removed.size)
  }

  @Test
  fun `whole page replacement is churn heavy`() {
    val before = snapshot(node(0, text = "旧页面"), node(1, text = "旧按钮", clickable = true))
    val after = snapshot(node(0, text = "新页面"), node(1, text = "新按钮", clickable = true))

    val diff = diffSnapshots(before, after)

    assertTrue("两行全变时不该走增量", diff.isChurnHeavy())
  }

  @Test
  fun `small change is not churn heavy`() {
    val before = snapshot((0..9).map { node(it, text = "行$it") })
    val after = snapshot((0..9).map { node(it, text = if (it == 5) "行5-新" else "行$it") })

    val diff = diffSnapshots(before, after)

    assertFalse(diff.isChurnHeavy())
    assertEquals(1, diff.changed.size)
  }

  @Test
  fun `renderDiff marks added changed and removed lines and lists targets`() {
    val before = snapshot(node(0, text = "加载中"), node(1, text = "发送", clickable = true, top = 900, bottom = 940))
    val after = snapshot(node(0, text = "已完成"), node(1, text = "发送", clickable = true, top = 900, bottom = 940))

    val rendered = renderDiff(after, diffSnapshots(before, after))

    assertTrue(rendered.startsWith("pkg=com.example.app nodes=2 delta: +0 -0 ~1, 1 unchanged"))
    assertTrue(rendered.contains("~[0] TextView \"已完成\""))
    assertTrue(rendered.contains("targets: 1 actionable"))
    assertTrue(rendered.contains("[1] TextView \"发送\" clickable @(50,920)"))
  }

  @Test
  fun `renderUnchanged says so and still lists actionable nodes`() {
    val screen = snapshot(node(0, text = "发送", clickable = true))

    val rendered = renderUnchanged(screen)

    assertTrue(rendered.startsWith("pkg=com.example.app nodes=1 unchanged since last read"))
    assertTrue(rendered.contains("targets: 1 actionable"))
    assertTrue(rendered.contains("[0] TextView \"发送\" clickable"))
  }

  @Test
  fun `fingerprint ignores text but follows structure`() {
    val a = snapshot(node(0, text = "温度 21°"), node(1, text = "湿度 40%"))
    val b = snapshot(node(0, text = "温度 25°"), node(1, text = "湿度 55%"))
    val c = snapshot(node(0, text = "温度 21°"))

    assertEquals(screenFingerprint(a), screenFingerprint(b))
    assertNotEquals(screenFingerprint(a), screenFingerprint(c))
  }

  @Test
  fun `page hint distinguishes new and known pages`() {
    assertEquals("page=abcd new", renderPageHint("abcd", null))
    assertEquals("page=abcd new", renderPageHint("abcd", PageVisit(0, null, 1L)))
    assertEquals(
      "page=abcd known visits=3 last=tap@(540,300)",
      renderPageHint("abcd", PageVisit(3, "tap@(540,300)", 1L)),
    )
  }
}

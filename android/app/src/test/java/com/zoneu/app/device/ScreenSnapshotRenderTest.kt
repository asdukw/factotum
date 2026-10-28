package com.zoneu.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotRenderTest {
  private fun node(
    ref: Int,
    className: String = "android.widget.TextView",
    text: String? = null,
    desc: String? = null,
    left: Int = 0,
    top: Int = 0,
    right: Int = 100,
    bottom: Int = 40,
    depth: Int = 0,
    clickable: Boolean = false,
    longClickable: Boolean = false,
    editable: Boolean = false,
    scrollable: Boolean = false,
    focused: Boolean = false,
    visible: Boolean = true,
  ) =
    ScreenNode(
      ref = ref,
      className = className,
      text = text,
      contentDescription = desc,
      bounds = NodeBounds(left, top, right, bottom),
      depth = depth,
      clickable = clickable,
      longClickable = longClickable,
      editable = editable,
      scrollable = scrollable,
      focused = focused,
      visible = visible,
    )

  @Test
  fun `renders labeled and interactive nodes with refs and centers`() {
    val snapshot =
      ScreenSnapshot(
        packageName = "com.example.app",
        nodes =
          listOf(
            node(ref = 0, className = "android.widget.FrameLayout"),
            node(ref = 1, text = "搜索", left = 100, top = 200, right = 300, bottom = 260, clickable = true),
            node(ref = 2, className = "android.widget.EditText", editable = true, focused = true),
          ),
      )

    val rendered = renderSnapshot(snapshot)

    assertTrue(rendered.startsWith("pkg=com.example.app nodes=3"))
    assertTrue(rendered.contains("[1] TextView \"搜索\" clickable @(200,230)"))
    assertTrue(rendered.contains("[2] EditText editable,focused @(50,20)"))
    assertFalse("无标签无交互的容器不渲染", rendered.contains("[0] FrameLayout"))
  }

  @Test
  fun `truncates long text and marks snapshot truncation`() {
    val long = "长".repeat(200)
    val snapshot = ScreenSnapshot(packageName = "p", nodes = listOf(node(0, text = long)), truncated = true)

    val rendered = renderSnapshot(snapshot, maxTextChars = 10)

    assertTrue(rendered.startsWith("pkg=p nodes=1+\n"))
    assertTrue(rendered.contains("\"${"长".repeat(10)}\""))
    assertFalse(rendered.contains(long))
  }

  @Test
  fun `caps rendered lines and adds truncation marker`() {
    val nodes = (0 until 5).map { node(ref = it, text = "t$it") }
    val rendered = renderSnapshot(ScreenSnapshot("p", nodes), maxLines = 3)

    assertTrue(rendered.contains("[2]"))
    assertFalse(rendered.contains("[3]"))
    assertTrue(rendered.endsWith("…（截断）"))
  }

  @Test
  fun `invisible nodes are skipped`() {
    val snapshot =
      ScreenSnapshot(
        "p",
        listOf(node(0, text = "on-screen"), node(1, text = "off-screen row", visible = false)),
      )

    val rendered = renderSnapshot(snapshot)

    assertTrue(rendered.contains("on-screen"))
    assertFalse(rendered.contains("off-screen row"))
  }

  @Test
  fun `content description is used when text is missing`() {
    val rendered = renderSnapshot(ScreenSnapshot("p", listOf(node(0, desc = "返回", clickable = true))))

    assertEquals("pkg=p nodes=1\n[0] TextView \"返回\" clickable @(50,20)", rendered)
  }

  @Test
  fun `renders long clickable nodes`() {
    val rendered = renderSnapshot(ScreenSnapshot("p", listOf(node(0, text = "菜单", longClickable = true))))

    assertTrue(rendered.contains("[0] TextView \"菜单\" long_clickable @(50,20)"))
  }
}

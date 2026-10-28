package com.zoneu.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「树基本为空」的判定与空树提示。
 *
 * 这两样都在视觉路径的入口上：判空漏了，模型就永远不知道该换看图；提示文案丢了标记串，
 * 审计的 `emptyTreeReads` 会**静默归零**（tools/audit/lib.mjs 靠字符串匹配它，
 * 而审计的 selftest 用的是自己造的字符串，抓不到这种漂移）。
 */
class EmptyTreeTest {
  private fun node(
    ref: Int,
    text: String? = null,
    desc: String? = null,
    clickable: Boolean = false,
    visible: Boolean = true,
  ) = ScreenNode(
    ref = ref,
    className = "android.widget.FrameLayout",
    text = text,
    contentDescription = desc,
    bounds = NodeBounds(0, 0, 100, 100),
    depth = 0,
    clickable = clickable,
    visible = visible,
  )

  @Test
  fun `只有根节点的树算空`() {
    val snapshot = ScreenSnapshot("com.tencent.mm", listOf(node(0)))

    assertTrue(snapshot.hasNoReadableNode())
  }

  @Test
  fun `一堆没有标签的容器也算空`() {
    // 这是按节点总数判空会漏掉的假阴性：节点数不少，可读的行为零。
    val snapshot = ScreenSnapshot("com.tencent.mm", (0 until 40).map { node(it) })

    assertTrue(snapshot.hasNoReadableNode())
  }

  @Test
  fun `有带文字的节点就不算空`() {
    val snapshot = ScreenSnapshot("p", listOf(node(0), node(1, text = "发送")))

    assertFalse(snapshot.hasNoReadableNode())
  }

  @Test
  fun `可点但没文字的节点也算可读`() {
    val snapshot = ScreenSnapshot("p", listOf(node(0), node(1, clickable = true)))

    assertFalse(snapshot.hasNoReadableNode())
  }

  @Test
  fun `不可见的节点不算数`() {
    val snapshot = ScreenSnapshot("p", listOf(node(0, text = "上一屏残留", visible = false)))

    assertTrue(snapshot.hasNoReadableNode())
  }

  @Test
  fun `空树提示必须带上审计认的那句话`() {
    // tools/audit/lib.mjs 的 EMPTY_TREE_MARKER —— 改文案时这句要逐字留着。
    assertTrue(EMPTY_TREE_HINT.contains("几乎没有可读节点"))
  }
}

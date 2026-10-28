package com.zoneu.app.device

import java.security.MessageDigest

/*
 * 增量读屏（roadmap §1.2）：把当前快照与上一次快照比对，只把变化交给模型。
 *
 * **编号（ref）跨读保持稳定**：两次读屏之间没变（或只有文字变化）的节点沿用上一次的 ref，
 * 新节点拿 [lastSnapshot] 之后的下一个编号。于是模型的「先读屏再 tap_node」不必因为
 * 中间多了一次读屏就重新认识整棵树 —— 这正是原来每步全量重发最贵的地方。
 *
 * 纯函数，便于单测：差异判定不碰 AccessibilityNodeInfo，也不碰任何 I/O。
 */

/** 一次读屏相对上一次的变化。 */
data class ScreenDiff(
  /** 当前屏幕的全部节点，ref 已按稳定编号重排。 */
  val nodes: List<ScreenNode>,
  /** 本次新出现的节点（ref 是新分配的）。 */
  val added: List<ScreenNode>,
  /** 上一次有、这次没有的节点（保留上一次的 ref，已失效）。 */
  val removed: List<ScreenNode>,
  /** 同一节点但文字 / 描述 / 位置 / 状态变了。 */
  val changed: List<NodeChange>,
  /** 完全没变的节点数。 */
  val unchanged: Int,
) {
  val churn: Int get() = added.size + removed.size + changed.size
}

data class NodeChange(val before: ScreenNode, val after: ScreenNode)

/**
 * 变化量超过这个比例就退回全量。
 *
 * 页面整体换掉时（启动新 App、进二级页），逐行 diff 对模型反而更难读，
 * 而且弱匹配容易把「删一行 + 加一行」错误地认成「同一行变了」。
 */
const val DIFF_MAX_CHURN_RATIO = 0.5

fun ScreenDiff.isChurnHeavy(): Boolean {
  val total = maxOf(nodes.size, unchanged + changed.size + removed.size)
  return total > 0 && churn.toDouble() / total > DIFF_MAX_CHURN_RATIO
}

/**
 * 比对两次快照。
 *
 * 匹配分三轮，**由强到弱**，弱匹配只用于「同一位置/同一标签」这类高置信情况：
 * 1. 完全一致（类名、深度、外框、文字、描述、状态）；
 * 2. 同位置（类名、深度、外框）→ 文字或状态变了；
 * 3. 同标签（类名、深度、文字、描述、状态）→ 位置变了。
 *
 * 剩下的才算新增 / 消失。不做「同类名同深度」的兜底匹配：列表滚动的场景里，
 * 那会把两行不相干的内容认成同一行，模型据此点错的风险比多几行 diff 大得多。
 */
fun diffSnapshots(previous: ScreenSnapshot, current: ScreenSnapshot): ScreenDiff {
  val oldToNew = IntArray(previous.nodes.size) { -1 }
  val newToOld = IntArray(current.nodes.size) { -1 }

  fun match(oldKey: (ScreenNode) -> String, newKey: (ScreenNode) -> String) {
    val waiting = HashMap<String, ArrayDeque<Int>>()
    previous.nodes.forEachIndexed { i, node ->
      if (oldToNew[i] < 0) waiting.getOrPut(oldKey(node)) { ArrayDeque() }.addLast(i)
    }
    current.nodes.forEachIndexed { j, node ->
      if (newToOld[j] >= 0) return@forEachIndexed
      val i = waiting[newKey(node)]?.removeFirstOrNull() ?: return@forEachIndexed
      oldToNew[i] = j
      newToOld[j] = i
    }
  }

  match(::exactKey, ::exactKey)
  match(::positionKey, ::positionKey)
  match(::labelKey, ::labelKey)

  var nextRef = (previous.nodes.maxOfOrNull { it.ref } ?: -1) + 1
  val remapped = ArrayList<ScreenNode>(current.nodes.size)
  val added = mutableListOf<ScreenNode>()
  val changed = mutableListOf<NodeChange>()
  var unchanged = 0

  current.nodes.forEachIndexed { j, node ->
    val oldIndex = newToOld[j]
    if (oldIndex < 0) {
      val fresh = node.copy(ref = nextRef++)
      remapped += fresh
      added += fresh
    } else {
      val before = previous.nodes[oldIndex]
      val after = node.copy(ref = before.ref)
      remapped += after
      if (samePresentation(before, after)) unchanged++ else changed += NodeChange(before, after)
    }
  }

  val removed = previous.nodes.filterIndexed { i, _ -> oldToNew[i] < 0 }

  return ScreenDiff(remapped, added, removed, changed, unchanged)
}

private fun exactKey(n: ScreenNode): String =
  "${positionKey(n)}|${labelKey(n)}|${flagsKey(n)}"

/** 同位置：类名 + 深度 + 外框。文字变化不影响它。 */
private fun positionKey(n: ScreenNode): String =
  "${n.className}|${n.depth}|${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}"

/** 同标签：类名 + 深度 + 文字 / 描述 + 状态。位置变化不影响它。 */
private fun labelKey(n: ScreenNode): String =
  "${n.className}|${n.depth}|${n.text.orEmpty()}|${n.contentDescription.orEmpty()}|${flagsKey(n)}"

private fun flagsKey(n: ScreenNode): String =
  "${n.clickable}${n.longClickable}${n.editable}${n.scrollable}${n.focused}${n.visible}"

private fun samePresentation(a: ScreenNode, b: ScreenNode): Boolean =
  a.text == b.text &&
    a.contentDescription == b.contentDescription &&
    a.bounds == b.bounds &&
    flagsKey(a) == flagsKey(b)

// ---------------------------------------------------------------------------
// 渲染：全量 / 增量 / 未变化
// ---------------------------------------------------------------------------

/**
 * 增量结果的文本形态。
 *
 * 行首方括号里带标记：`[+12]` 新增、`[~12]` 变化、`[-9]` 已消失。
 * 未变化的行不重复发送，ref 沿用上一次 —— 同一屏内连续操作不需要每步重读。
 * 不附带「全部可操作节点」清单：计算器这类整屏都是按钮的界面，重列一遍会让增量
 * 和全量几乎一样大（实测 1723 vs 1652 字符）。上下文被截断时用 `full=true`，
 * 那是明确的、由模型主动选择的兜底，而不是每次都给所有场景付这份成本。
 */
fun renderDiff(
  snapshot: ScreenSnapshot,
  diff: ScreenDiff,
  maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
  maxLines: Int = DEFAULT_MAX_RENDER_LINES,
): String {
  val out = StringBuilder()
  out.append("pkg=").append(snapshot.packageName ?: "?").append(" nodes=").append(diff.nodes.size)
  out.append(" delta: +").append(diff.added.size)
  out.append(" -").append(diff.removed.size)
  out.append(" ~").append(diff.changed.size)
  out.append(", ").append(diff.unchanged).append(" unchanged\n")

  val marks = HashMap<Int, Char>(diff.added.size + diff.changed.size)
  diff.added.forEach { marks[it.ref] = '+' }
  diff.changed.forEach { marks[it.after.ref] = '~' }

  val lines = appendMarked(out, diff.nodes, { marks[it.ref] }, maxTextChars, maxLines)
  appendMarked(
    out,
    diff.removed,
    { if (it.interesting) '-' else null },
    maxTextChars,
    maxLines - lines,
  )
  return out.toString().trimEnd()
}

/**
 * 按标记逐行追加，返回写入的行数。
 *
 * `markOf` 返回 null 表示这行不归本段（例如未变化、或已被上层过滤）。
 * 行数用尽时只写一次截断标记 —— 全量与增量共用同一条截断规则。
 */
private fun appendMarked(
  out: StringBuilder,
  nodes: List<ScreenNode>,
  markOf: (ScreenNode) -> Char?,
  maxTextChars: Int,
  maxLines: Int,
): Int {
  if (maxLines <= 0) return 0
  var lines = 0
  for (node in nodes) {
    if (lines >= maxLines) {
      out.append("…（截断）\n")
      break
    }
    val mark = markOf(node)
    if (mark != null) {
      out.append(mark).append(renderNodeLine(node, maxTextChars)).append('\n')
      lines++
    }
  }
  return lines
}

/** 屏幕完全没变时的最短回复。 */
fun renderUnchanged(snapshot: ScreenSnapshot): String =
  "pkg=${snapshot.packageName ?: "?"} nodes=${snapshot.nodes.size} unchanged since last read, refs still valid"

// ---------------------------------------------------------------------------
// 界面指纹（roadmap §1.3）
// ---------------------------------------------------------------------------

/**
 * 页面结构指纹：`(包名, 节点结构)` 的哈希。
 *
 * **只取结构，不取正文**：类名、深度、状态位进哈希；文字、描述、坐标一律不进。
 * 这样同一页面在文字变化（天气数值、消息内容、时间）后仍然是同一个指纹，
 * 而滚动、增删节点会改变它。哈希值本身不可逆，落盘也只存哈希与操作计数。
 *
 * 已知局限：两个结构完全相同的页面（同一套模板的设置子页）会撞指纹。
 * 因此它只用于提示「这页见过」，**不参与任何判定** —— 撞了最多是提示不准，
 * 不会让模型跳过错内容。
 */
fun screenFingerprint(snapshot: ScreenSnapshot): String {
  val digest = MessageDigest.getInstance("SHA-256")
  digest.update((snapshot.packageName ?: "?").toByteArray(Charsets.UTF_8))
  for (node in snapshot.nodes) {
    digest.update(
      "\n${node.className}|${node.depth}|${flagsKey(node)}".toByteArray(Charsets.UTF_8),
    )
  }
  return digest.digest().take(4).joinToString("") { "%02x".format(it) }
}

/** 一个页面的历史记忆（**不含正文**）。 */
data class PageVisit(val visits: Int, val lastAction: String?, val lastSeen: Long)

/** 给模型看的页面提示：新页面只报指纹，见过的页面附带访问次数与上次成功动作。 */
fun renderPageHint(fingerprint: String, visit: PageVisit?): String {
  val sb = StringBuilder("page=").append(fingerprint)
  if (visit == null || visit.visits <= 0) {
    sb.append(" new")
  } else {
    sb.append(" known visits=").append(visit.visits)
    visit.lastAction?.takeIf { it.isNotBlank() }?.let { sb.append(" last=").append(it) }
  }
  return sb.toString()
}

/**
 * 屏幕内容的轻量哈希：回答「这个动作有没有改变界面」，用于 `swipe` / `tap` 的反馈。
 *
 * 与 [screenFingerprint] 刻意不同：这里**包含文字与外框**。滑动改变的是内容与位置，
 * 页面结构（类名 / 深度）往往不变；只看结构会把「滚了」判成「没变」。
 *
 * 纯函数，便于单测。碰撞与「小面积变化看不见」都是可接受的：它只用于给模型一句
 * 「界面似乎没变」，不影响动作的 `ok`。
 */
fun contentHash(nodes: List<ScreenNode>): Long {
  var h = 1125899906842597L
  for (node in nodes) {
    h = 31 * h + node.className.hashCode()
    h = 31 * h + (node.text?.hashCode() ?: 0)
    h = 31 * h + (node.contentDescription?.hashCode() ?: 0)
    h = 31 * h + node.bounds.hashCode()
    h = 31 * h + node.depth
    h = 31 * h + (if (node.visible) 1 else 0)
  }
  return h
}

private const val DEFAULT_MAX_TEXT_CHARS = 80
private const val DEFAULT_MAX_RENDER_LINES = 240

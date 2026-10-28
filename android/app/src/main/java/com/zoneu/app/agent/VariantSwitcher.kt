package com.zoneu.app.agent

import com.zoneu.app.data.session.VariantStore

/**
 * 左右切候选回答。
 *
 * 与 [ReplyExtras] 同一个理由抽出来：`ChatViewModel` 贴着 LargeClass 阈值，而这些动作的依赖
 * 很窄（当前状态、候选读写、重载一页）。放进这个类之后，再加「跳到第几份」之类的动作
 * 也不用去动那一千行的主类。
 *
 * 最后一步刻意是**整页重读**而不是就地改 state：换了一份回答意味着后面所有对用户可见的东西
 * （气泡、工具卡、候选计数）都该跟着换，就地改迟早漏掉一处。
 */
class VariantSwitcher(
  private val snapshot: () -> ChatUiState,
  private val variants: VariantStore,
  /** 起一个协程（重载一页要读库）；与 [ReplyExtras.start] 一样由调用方持有 job。 */
  private val start: (suspend () -> Unit) -> Unit,
  private val reload: suspend (sessionId: String) -> Unit,
  private val reportFailure: (Throwable) -> Unit,
) {
  /** [-1] 上一份、[1] 下一份；到头绕回（见 [Variants.step]）。 */
  val swipe: (Int) -> Unit = delta@{ step ->
    val state = snapshot()
    if (!state.idle) return@delta
    val sessionId = state.sessionId ?: return@delta
    val tail = state.messages.lastOrNull { it.role == ChatRole.Assistant } ?: return@delta
    val anchor = tail.anchorId ?: return@delta
    if (tail.variantTotal <= 1) return@delta
    start {
      val group =
        runCatching { variants.variants(anchor) }.getOrElse { throwable ->
          reportFailure(throwable)
          return@start
        }
      if (group.size <= 1) return@start
      val current = group.indexOfFirst { it.id == tail.id }.coerceAtLeast(0)
      val target = group[Variants.step(group.size, current, step)]
      val moved = runCatching { variants.selectVariant(anchor, target.id) }
      if (moved.isFailure) {
        reportFailure(moved.exceptionOrNull() ?: IllegalStateException("切换候选失败"))
        return@start
      }
      // 整页重读：换了一份回答意味着后面所有可见的东西都该跟着换
      reload(sessionId)
    }
  }
}

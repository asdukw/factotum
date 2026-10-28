package com.zoneu.app.agent

import android.util.Log
import com.zoneu.app.data.db.MessageRole
import com.zoneu.app.data.session.SessionAdmin
import com.zoneu.app.data.session.SessionStore
import com.zoneu.app.data.session.StoredMessage

/**
 * 前情提要：聊久了把「最早那一段」压成几句第三人称的回忆，落进会话的 `summary` 列。
 *
 * 为什么需要它：上下文预算是硬上限，聊到 40 轮时最早的那些事只能被裁掉 —— 于是模型开始
 * 忘掉「她答应过什么」「谁欠谁一条命」。裁剪是丢信息，提要是不丢信息地变短，代价是每十几轮
 * 多花一次便宜的调用。
 *
 * 三条边界，都是为了「别把模型写歪」：
 * - 只压**已成稿**的消息（写完的、非空的），半截回复压进去会让提要记住一句没说完的话；
 * - 永远留最近 [RecapPlan.KEEP_RECENT] 条原文不动：最近的语气与细节必须逐字在场，提要写不出那种东西；
 * - 新提要覆盖旧提要，而不是无限追加 —— 追加到最后，提要本身又变成一份超预算的累赘。
 */
class SessionRecap(
  private val store: SessionStore,
  private val admin: SessionAdmin,
  private val complete: suspend (sessionId: String, system: String, user: String) -> String,
  private val start: (suspend () -> Unit) -> Unit,
) {
  /** 一轮结束后调用；不满足条件就什么都不做（见 [RecapPlan.of]）。 */
  fun maybeSummarize(sessionId: String) {
    start { runCatching { run(sessionId) }.onFailure { Log.w(TAG, "recap failed: ${it.javaClass.simpleName}") } }
  }

  /**
   * 用户按「现在就总结」：门槛降到 2 条，其余规则不变。
   *
   * 手动触发是必要的出口 —— 自动那套要聊到 12 条才动，而「我想让模型记住这一段」往往
   * 就发生在刚聊完一场戏的时候。回调拿到的是新提要，界面直接显示，不必等下一轮。
   */
  fun summarizeNow(sessionId: String, onDone: (String) -> Unit) {
    start {
      runCatching { run(sessionId, manual = true) }
        .onSuccess { text -> if (text != null) onDone(text) }
        .onFailure { Log.w(TAG, "manual recap failed: ${it.javaClass.simpleName}") }
    }
  }

  /** 返回写进去的提要；没写（条数不够 / 模型空回）就是 null。 */
  suspend fun run(sessionId: String, manual: Boolean = false): String? {
    val session = store.session(sessionId) ?: return null
    val rows = store.messages(sessionId).filter { it.content.isNotBlank() }
    val from = session.summaryUpto?.let { upto -> rows.indexOfFirst { it.id == upto } } ?: -1
    // 手动模式下「留几条原文」也一起放宽：聊了四句就说「记住这段」是很常见的用法，
    // 留着 6 条原文的话，那片历史里根本没有可压的东西。
    val plan =
      RecapPlan.of(
        pending = rows.drop(from + 1),
        old = session.summary,
        minMessages = if (manual) MANUAL_MIN_MESSAGES else RecapPlan.MIN_MESSAGES,
        keepRecent = if (manual) MANUAL_KEEP_RECENT else RecapPlan.KEEP_RECENT,
      ) ?: return null
    val text = complete(sessionId, SYSTEM, plan.transcript)
    if (text.isBlank()) return null
    val kept = text.take(RecapPlan.MAX_SUMMARY_CHARS)
    admin.setSummary(sessionId, kept, plan.uptoId)
    Log.d(TAG, "recap session=$sessionId manual=$manual messages=${plan.count} chars=${kept.length}")
    return kept
  }

  companion object {
    private const val TAG = "zoneuAgent"

    /** 手动总结的最低条数：一条消息（可能还是空的）没什么可压的。 */
    const val MANUAL_MIN_MESSAGES = 2

    /** 手动总结时保留的原文条数（自动那套是 6）：手动的场合本来就更短。 */
    const val MANUAL_KEEP_RECENT = 2

    const val SYSTEM =
      "你在替一段角色扮演对话写「前情提要」。用 3–5 句中文、第三人称、陈述句，写清：谁是谁、" +
        "发生过什么、关系与处境有什么变化。不要评价、不要续写、不要用「玩家」「AI」这类词，" +
        "也不要复述这句要求。只输出提要本身。"
  }
}

/** 什么时候值得总结、总结哪一段。纯函数，因为这几条阈值是最容易改坏又最看不出来的地方。 */
object RecapPlan {
  /** 未总结的消息少于这么多条就不动手：聊两句就总结，纯属白花钱。 */
  const val MIN_MESSAGES = 12

  /** 最近的这么多条永远保持原文，不进提要。 */
  const val KEEP_RECENT = 6

  /** 提要本身的上限：它每轮都会被带进 prompt，写长了等于给自己加预算。 */
  const val MAX_SUMMARY_CHARS = 600

  /** 一次要压的对话原文上限（防止很久没聊、一压就是几万字）。 */
  const val MAX_TRANSCRIPT_CHARS = 8_000

  data class Plan(val transcript: String, val uptoId: String, val count: Int)

  /**
   * 决定这一轮要不要总结。
   *
   * 取「未总结里除最近 [KEEP_RECENT] 条之外的全部」去压，压到哪条就把 `uptoId` 记在那条上 ——
   * 下一次从它之后接着算，不会重复压同一段。
   */
  fun of(
    pending: List<StoredMessage>,
    old: String?,
    minMessages: Int = MIN_MESSAGES,
    keepRecent: Int = KEEP_RECENT,
  ): Plan? {
    if (pending.size <= minMessages) return null
    val slice = pending.dropLast(keepRecent)
    if (slice.isEmpty()) return null
    // 从后往前装：越靠近现在的对话越重要，字符不够时丢最早的那几条
    val lines = ArrayList<String>(slice.size)
    var budget = MAX_TRANSCRIPT_CHARS
    for (row in slice.asReversed()) {
      val line = "${if (row.role == MessageRole.User) "用户" else "角色"}：${row.content.trim()}"
      if (line.length > budget) break
      budget -= line.length
      lines.add(0, line)
    }
    if (lines.isEmpty()) return null
    val head = old?.trim()?.takeIf { it.isNotBlank() }?.let { "【已有的前情提要】\n$it\n\n【接着发生的对话】\n" }
    return Plan(
      transcript = (head ?: "") + lines.joinToString("\n"),
      uptoId = slice.last().id,
      count = lines.size,
    )
  }
}

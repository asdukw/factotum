package com.zoneu.app.agent

import kotlinx.coroutines.flow.Flow

/**
 * 两个「不改变历史」的动作：续写与替我写。
 *
 * 为什么单独一个类：`ChatViewModel` 已经贴着 detekt 的 LargeClass 阈值，而这两件事的依赖
 * 很窄（当前状态、拿一段流、把结果写回状态），塞在里面既撑大文件又让方法数继续涨。
 * 放在这里之后，以后加「旁白」「导出一段」也不用动那个已经一千行的类。
 *
 * 三个依赖都是函数而不是对象：状态要在**点下去的那一刻**读（不是构造时），
 * 通道要现取（用户可能刚换了提供商），而 [start] 负责把协程挂到调用方的 job 上 ——
 * 这样「停止」按钮对这两个动作一样有效。
 */
class ReplyExtras(
  private val snapshot: () -> ChatUiState,
  private val update: ((ChatUiState) -> ChatUiState) -> Unit,
  /** 起一个协程；调用方负责把 job 记下来，让停止键能取消它。 */
  private val start: (suspend () -> Unit) -> Unit,
  /** 拿一条只进请求的参数：返回 null 表示通道此刻不可用。 */
  private val open: (sessionId: String, characterId: String, tail: String) -> Flow<AgentStreamEvent>?,
  /** 把这一轮流进某条已有消息，[base] 是它已经有的正文。 */
  private val continueInto: suspend (sessionId: String, messageId: String, base: String) -> Unit,
  private val onTyped: (String) -> Unit,
) {
  /**
   * 续写：接着最后一条回复往下写，正文追加到同一条消息里。
   *
   * 与重写不同，它不删旧、也不算新的一份候选：续写就是同一段话的后来部分，
   * 单独建一条会让界面出现两段各说一半的话，候选计数也跟着乱。
   */
  val continueReply: () -> Unit = lambda@{
    val state = snapshot()
    if (!state.idle) return@lambda
    val sessionId = state.sessionId ?: return@lambda
    val last = state.messages.lastOrNull() ?: return@lambda
    if (last.role != ChatRole.Assistant || last.streaming || last.content.isBlank()) return@lambda
    if (!state.tavernDirect || state.activeCharacterId == null) return@lambda
    val base = last.content
    update { it.copy(isStreaming = true, messages = it.messages.dropLast(1) + last.copy(streaming = true)) }
    start { continueInto(sessionId, last.id, base) }
  }

  /**
   * 替我写：让模型站在用户这边写一句，结果**只填进输入框**，不自动发送。
   *
   * 不自动发是关键：模型替你写的那句话经常要改一两个字，直接发出去就没法改了；
   * 而「点一下等于我同意」在角色扮演里是个很坏的默认值。
   */
  val impersonate: () -> Unit = lambda@{
    val state = snapshot()
    if (!state.idle) return@lambda
    val sessionId = state.sessionId ?: return@lambda
    val characterId = state.activeCharacterId
    if (!state.tavernDirect || characterId == null) return@lambda
    if (state.messages.isEmpty()) return@lambda
    update { it.copy(writingLine = true) }
    start {
      val typed = StringBuilder()
      runCatching {
        open(sessionId, characterId, TavernDirectives.IMPERSONATE)?.collect { event ->
          if (event is AgentStreamEvent.TextDelta) typed.append(event.text)
        }
      }
      update { it.copy(writingLine = false) }
      val line = TavernDirectives.asUserLine(typed.toString())
      if (line.isNotBlank()) onTyped(line)
    }
  }
}

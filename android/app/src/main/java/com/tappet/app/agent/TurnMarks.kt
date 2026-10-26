package com.tappet.app.agent

/**
 * 对「某一条消息」的界面态改写：正文增量、推理原文、思考计时。
 *
 * 从 `ChatViewModel` 搬出来的原因很实在：那个类贴着 detekt 的 LargeClass 阈值（嵌套类不算进去，
 * 所以把 `StreamTurn` 挪走并不能给它减负）。而这几只动作只依赖「一次状态替换」，
 * 与状态机本身无关 —— 搬走之后主类不再随「多一个流式字段」而变长。
 *
 * 全部是**纯改写**：不读库、不起协程（落库仍由主类的 flush 做），所以单测与流式路径
 * 都能放心调。
 */
class TurnMarks(private val update: ((ChatUiState) -> ChatUiState) -> Unit) {
  /** 记录思考时长（毫秒）；界面显示「思考了 3.8 秒」。清掉起点，实时计时停止。 */
  fun markThinkingMs(assistantId: String, ms: Long) {
    patch(assistantId) { it.copy(thinkingMs = ms, thinkingStartedAt = null) }
  }

  /** 记录思考开始时间；界面据此实时显示「思考中 X 秒」。 */
  fun markThinkingStarted(assistantId: String, at: Long) {
    patch(assistantId) { it.copy(thinkingStartedAt = at) }
  }

  fun markReasoning(assistantId: String, reasoning: String) {
    patch(assistantId) { it.copy(reasoning = reasoning.ifBlank { null }, thinking = it.content.isEmpty()) }
  }

  /** 正文增量。[replace] 为真时整段替换（流式镜像用），否则追加。 */
  fun appendText(assistantId: String, text: String, replace: Boolean = false) {
    patch(assistantId) {
      it.copy(content = if (replace) text else it.content + text, streaming = true, thinking = false)
    }
  }

  /**
   * 这一轮结束：清掉流式标记，错误挂在这条消息上（正文保留，界面上错误另起一行）。
   *
   * 这里**不能**复用 [patch]：它只改 messages，而整页的 `isStreaming` 也要一起落下 ——
   * 漏了它，界面会一直停在「正在回答」，连会话都切不动（canSwitchSession 也看这个值）。
   */
  fun finishStream(assistantId: String, error: String?) {
    update { state ->
      state.copy(
        isStreaming = false,
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) message.copy(streaming = false, thinking = false, error = error) else message
          },
      )
    }
  }

  private fun patch(id: String, transform: (ChatMessage) -> ChatMessage) {
    update { state ->
      state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
    }
  }
}

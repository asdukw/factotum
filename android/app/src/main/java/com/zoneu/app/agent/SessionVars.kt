package com.zoneu.app.agent

/**
 * 会话变量的读与写（界面上的「这一场的状态」）。
 *
 * 从 [ChatViewModel] 搬出来是为了那个类的 detekt 行数上限 —— 与 [TurnMarks] 同一个理由：
 * 一行 `copy(sessionVars = …)` 也算在类的 LOC 里，而这几行与状态机本身无关。
 */
class SessionVars(
  private val tavern: TavernTurns?,
  private val update: ((ChatUiState) -> ChatUiState) -> Unit,
  private val start: (suspend () -> Unit) -> Unit,
) {
  /** 从存储读一份实际的变量表刷进界面。 */
  suspend fun refresh(sessionId: String) {
    val values = tavern?.varsOf(sessionId) ?: return
    update { state -> state.copy(sessionVars = values) }
  }

  /**
   * 手改一个变量并立刻刷新。
   *
   * 只改这一个键：`VarsStore.save` 是整表覆盖，交给 [TavernTurns.setVar] 做读-改-写，
   * 免得手改一个变量把同场其他变量一起抹掉。
   */
  fun set(sessionId: String?, name: String, value: String) {
    if (sessionId == null) return
    val turns = tavern ?: return
    start {
      turns.setVar(sessionId, name, value)
      refresh(sessionId)
    }
  }
}

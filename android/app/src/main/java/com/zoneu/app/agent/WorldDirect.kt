package com.zoneu.app.agent

/** Injected into TavernTurns so JVM tests need neither Context nor the real encrypted repository. */
interface WorldDirect {
  suspend fun prepare(request: Request): WorldRequestPlan

  suspend fun settle(requestId: String, success: Boolean, evidence: String)

  data class Request(
    val requestId: String,
    val sessionId: String,
    val history: List<TavernMessage>,
    val tail: String? = null,
    val recap: String? = null,
    val auxiliary: Boolean = false,
  )
}

/** Keep persistence and accounting in this order; provider completion is not a receipt. */
internal suspend fun finishWorldTurn(persist: suspend () -> Unit, settle: suspend () -> Unit) {
  persist()
  settle()
}

/** Preserve the storage failure, even if recording its failed world plan also fails. */
internal suspend fun persistWorldAnswer(turn: StreamTurn, failed: suspend () -> Unit) {
  try {
    turn.finish()
  } catch (
    // Both injected storage and Android database failures must prevent timing advancement.
    @Suppress("TooGenericExceptionCaught") error: Exception,
  ) {
    try {
      failed()
    } catch (
      @Suppress("TooGenericExceptionCaught") settlement: Exception,
    ) {
      error.addSuppressed(settlement)
    }
    throw error
  }
}

package com.zoneu.app.agent

import com.zoneu.app.data.session.ChatSession
import com.zoneu.app.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「这段会话用哪个模型」的记忆。
 *
 * 运行时同一时刻只有一个模型（dsh 的模型来自 profile 的 env，重载才能换），所以这里做的
 * 不是并行隔离，而是两件小事：
 * - **记**：在会话里切了模型，就把它写到这段会话上；
 * - **装回**：回到这段会话时，如果它记的模型和当前不同，就切过去（一次 remount）。
 *
 * 抽成独立类的原因和 [RoleplaySessions] 一样：ChatViewModel 的方法数已经压到 detekt 阈值边缘。
 */
class SessionModels(
  private val switcher: ModelSwitcher?,
  private val store: SessionStore,
  private val scope: CoroutineScope,
  /** 当前真正生效的模型 id —— 与要装的相同就别白 reload 一次。 */
  private val now: () -> String,
  private val onApplied: (ModelChoice) -> Unit,
  private val reportFailure: (String) -> Unit,
) {
  /** 进会话：把这段会话记下的模型装回去。找不到对应提供商就安静跳过（历史仍可读）。 */
  suspend fun restore(session: ChatSession) {
    val target = session.model
    if (target.isNullOrBlank() || target == now()) return
    val choice =
      runCatching { switcher?.choices() }.getOrNull().orEmpty().firstOrNull { it.model == target } ?: return
    runCatching { switcher?.switch(choice.providerId, choice.model) }
      .onSuccess { onApplied(choice) }
      .onFailure { reportFailure(it.message ?: "装回模型失败") }
  }

  /** 切模型之后记进当前会话。写失败不打断聊天：模型已经生效，记忆丢一次不值得报错。 */
  fun remember(sessionId: String?, model: String) {
    if (sessionId == null || model.isBlank()) return
    scope.launch { runCatching { store.setSessionModel(sessionId, model) } }
  }
}

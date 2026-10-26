package com.tappet.app.agent

import com.tappet.app.data.db.ToolCallStatus
import com.tappet.app.data.db.Uuids
import com.tappet.app.data.session.SessionStore

/**
 * 工具调用卡片的三处改写：开始、收尾、以及「进程被杀时把还在跑的标成失败」。
 *
 * 与 [TurnMarks] 同一个理由搬出来：这些只碰 `_state` 与 `store`，与 [ChatViewModel] 的状态机
 * 无关，却各占十几行 —— 而那个类贴着 detekt 的 LOC 上限。
 */
class ToolCallMarks(
  private val store: SessionStore,
  private val snapshot: () -> ChatUiState,
  private val update: ((ChatUiState) -> ChatUiState) -> Unit,
) {
  /** 记下工具调用开始：先上屏，再落库（落库失败只影响重启后的卡片）。 */
  suspend fun start(messageId: String, sessionId: String, event: AgentStreamEvent.ToolCallStarted) {
    val callId = event.callId?.takeIf { it.isNotBlank() } ?: Uuids.v7()
    val card = ToolCallView(id = callId, name = event.name, arguments = event.arguments)
    update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == messageId) message.copy(toolCalls = message.toolCalls + card) else message
          },
      )
    }
    // 这里必须等落库完成：紧接着的 tool/result 会按 callId 去更新同一行，
    // 异步插入会让「完成」先于「开始」，卡片的收尾状态就丢了。
    runCatching { store.appendToolCall(callId, sessionId, messageId, event.name, event.arguments) }
  }

  /** 工具调用收尾：dsh 的结果帧只带 callId，缺失时退化成「最后一个还在跑的那张卡」。 */
  suspend fun finish(messageId: String, event: AgentStreamEvent.ToolCallFinished) {
    val target =
      snapshot().messages
        .firstOrNull { it.id == messageId }
        ?.toolCalls
        ?.lastOrNull { event.callId == null || it.id == event.callId }
        ?: return
    val state = if (event.ok) ToolCallState.Ok else ToolCallState.Failed
    update { snapshot ->
      snapshot.copy(
        messages =
          snapshot.messages.map { message ->
            if (message.id != messageId) {
              message
            } else {
              message.copy(
                toolCalls =
                  message.toolCalls.map { call ->
                    if (call.id == target.id) call.copy(result = event.result, state = state) else call
                  },
              )
            }
          },
      )
    }
    runCatching {
      store.finishToolCall(
        id = target.id,
        result = event.result,
        status = if (event.ok) ToolCallStatus.Ok else ToolCallStatus.Failed,
      )
    }
  }

  /** 流被停掉 / 出错时，把这张消息上还在「执行中」的卡片落成失败，避免刷新后一直转圈。 */
  suspend fun finishRunning(messageId: String) {
    val running =
      snapshot().messages
        .firstOrNull { it.id == messageId }
        ?.toolCalls
        ?.filter { it.state == ToolCallState.Running }
        .orEmpty()
    if (running.isEmpty()) return
    update { snapshot ->
      snapshot.copy(
        messages =
          snapshot.messages.map { message ->
            if (message.id != messageId) {
              message
            } else {
              message.copy(
                toolCalls =
                  message.toolCalls.map { call ->
                    if (call.state == ToolCallState.Running) call.copy(state = ToolCallState.Failed) else call
                  },
              )
            }
          },
      )
    }
    running.forEach { call ->
      runCatching { store.finishToolCall(call.id, call.result, ToolCallStatus.Failed) }
    }
  }
}

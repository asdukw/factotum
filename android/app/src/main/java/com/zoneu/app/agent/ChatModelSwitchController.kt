package com.zoneu.app.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天页的模型切换状态机：读候选、落盘 + 重载运行时、更新 [ChatUiState]。
 *
 * 从 ChatViewModel 拆出来，是为了把「切换」这一组动作与消息流分开；重载期间禁止再次切换，
 * 并且要求当前没有任务在跑（remount 会打断 stream）。
 */
class ChatModelSwitchController(
  private val switcher: ModelSwitcher?,
  private val scope: CoroutineScope,
  private val reasoningLabel: String,
  private val snapshot: () -> ChatUiState,
  private val update: ((ChatUiState) -> ChatUiState) -> Unit,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /** 打开选择 sheet 前调用：清掉上次的错误并返回候选。 */
  fun prepare(): List<ModelChoice> {
    update { it.copy(switchError = null) }
    return switcher?.choices().orEmpty()
  }

  fun switch(choice: ModelChoice) {
    val target = switcher ?: return
    val current = snapshot()
    if (current.isStreaming || current.switchingModel) return
    update { it.copy(switchingModel = true, switchError = null) }
    scope.launch {
      runCatching { withContext(ioDispatcher) { target.switch(choice.providerId, choice.model) } }
        .onSuccess {
          update {
            it.copy(
              switchingModel = false,
              switchError = null,
              providerId = choice.providerId,
              providerName = choice.providerName,
              model = choice.model,
              configured = true,
              configSummary = "${choice.model} · $reasoningLabel",
            )
          }
        }
        .onFailure { throwable ->
          update {
            it.copy(
              switchingModel = false,
              switchError = throwable.message ?: "切换失败",
            )
          }
        }
    }
  }
}

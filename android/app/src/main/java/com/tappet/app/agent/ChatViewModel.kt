package com.tappet.app.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.ToolCallStatus
import com.tappet.app.data.db.Uuids
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.StoredMessage
import com.tappet.app.data.session.StoredToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聊天流式能力的窄接口，方便单测替换掉真实运行时。 */
fun interface ChatStreamer {
  fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent>
}

data class ChatUiState(
  val sessionId: String? = null,
  /** 全部会话，最近更新在前，供侧栏列表使用。 */
  val sessions: List<ChatSession> = emptyList(),
  val messages: List<ChatMessage> = emptyList(),
  val input: String = "",
  val isStreaming: Boolean = false,
  val configSummary: String = "",
  val configured: Boolean = true,
  /** 历史消息是否已从库里读完。读完之前不允许发送，否则会插到错误的位置。 */
  val ready: Boolean = false,
  /** 本地库打不开时的原因；不为空说明这次启动读不了也写不了。 */
  val storageError: String? = null,
) {
  val canSend: Boolean
    get() = ready && input.isNotBlank() && !isStreaming

  /** 任务进行中不允许切会话：事件流只认当前会话，切走会把半截回复写错地方。 */
  val canSwitchSession: Boolean
    get() = ready && !isStreaming
}

/**
 * 聊天状态机 + 持久化。
 *
 * 落库时机有两处刻意设计：
 * 1. **发起时就插入助手消息**（status = Streaming），正文按 flushIntervalMs 节流更新 ——
 *    否则进程被系统杀掉时整段回复都会丢
 * 2. **收尾写在 NonCancellable 里** —— 用户点“停止”会取消协程，普通挂起调用会立刻
 *    抛 CancellationException，最终状态就写不进去了
 *
 * 工具调用（tool_call 表）与消息分开落库：它只影响界面上那几张卡，写失败不该挡住回答。
 */
class ChatViewModel(
  // 故意不是 `private val`：这里只在构造时取摘要与 configured，之后不再持有 AgentConfig。
  // 它不是 val 就不会生成字段，明文密钥也不会随 Activity 作用域的 ViewModel 常驻内存。
  config: AgentConfig = AgentConfig.fromBuildConfig(),
  private val streamer: ChatStreamer,
  private val store: SessionStore,
  private val clock: () -> Long = System::currentTimeMillis,
  private val flushIntervalMs: Long = FLUSH_INTERVAL_MS,
) : ViewModel() {
  private val _state =
    MutableStateFlow(
      ChatUiState(
        configSummary = config.shortSummary(),
        configured = config.isConfigured,
      ),
    )
  val state: StateFlow<ChatUiState> = _state.asStateFlow()

  private var streamJob: Job? = null

  init {
    viewModelScope.launch { restore() }
    // 会话列表跟着库走：发消息改标题、删会话都会自动反映到侧栏。
    viewModelScope.launch {
      store.observeSessions().catch { /* 列表读不到不影响聊天本身 */ }.collect { sessions ->
        _state.update { it.copy(sessions = sessions) }
      }
    }
  }

  fun onInputChange(value: String) {
    _state.update { it.copy(input = value) }
  }

  fun send() {
    val snapshot = _state.value
    if (!snapshot.canSend) return
    val sessionId = snapshot.sessionId ?: return
    val prompt = snapshot.input.trim()

    _state.update { it.copy(input = "") }

    streamJob =
      viewModelScope.launch {
        val persisted =
          runCatching {
            val question = store.appendMessage(sessionId, MessageRole.User, prompt, MessageStatus.Complete)
            val draft = store.appendMessage(sessionId, MessageRole.Assistant, "", MessageStatus.Streaming)
            store.setTitleIfEmpty(sessionId, prompt.take(TITLE_MAX_CHARS))
            question to draft
          }

        val (question, draft) =
          persisted.getOrElse { throwable ->
            _state.update {
              it.copy(
                isStreaming = false,
                messages =
                  it.messages +
                    ChatMessage(id = Uuids.v7(), role = ChatRole.User, content = prompt) +
                    ChatMessage(
                      id = Uuids.v7(),
                      role = ChatRole.Assistant,
                      error = "本地存储失败：${throwable.message ?: throwable.javaClass.simpleName}",
                    ),
              )
            }
            return@launch
          }

        _state.update {
          it.copy(
            isStreaming = true,
            messages =
              it.messages +
                ChatMessage(id = question.id, role = ChatRole.User, content = prompt) +
                ChatMessage(id = draft.id, role = ChatRole.Assistant, streaming = true),
          )
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var failure: Throwable? = null
        var stopped = false
        var statusOnly = false
        var lastFlush = clock()
        // 增量合并：UI 最多每 UI_FLUSH_INTERVAL_MS 刷一次 —— token 级帧很多，
        // 逐帧重算 Markdown 太贵；DB 那边另有 flushIntervalMs 的节流。
        var lastUiFlush = clock()
        // 本步起点：StepFinal 只替换**这一步**追加的文本（多步回答的前几步不能被吃掉）。
        var stepStart = 0
        var reasoningStepStart = 0
        // 思考时长：第一次推理增量起算，第一条正文到达时结算（只活在这一轮，不入库）
        var thinkingStartedAt: Long? = null

        fun settleThinking() {
          val started = thinkingStartedAt ?: return
          thinkingStartedAt = null
          markThinkingMs(draft.id, (clock() - started).coerceAtLeast(0))
        }

        try {
          streamer.stream(sessionId, prompt).collect { event ->
            when (event) {
              is AgentStreamEvent.ReasoningDelta -> {
                if (thinkingStartedAt == null) thinkingStartedAt = clock()
                if (event.startsStep) reasoningStepStart = reasoning.length
                reasoning.append(event.text)
                val now = clock()
                if (event.startsStep || now - lastUiFlush >= UI_FLUSH_INTERVAL_MS) {
                  lastUiFlush = now
                  markReasoning(draft.id, reasoning.toString())
                }
                if (now - lastFlush >= flushIntervalMs) {
                  lastFlush = now
                  // 节流落库：增量可能每帧都来，200ms 一次已足够「进程被杀也不丢太多」
                  flush(draft.id, content, reasoning, MessageStatus.Streaming, error = null)
                }
              }

              is AgentStreamEvent.Status -> {
                statusOnly = true
                content.clear()
                content.append(event.text)
                appendText(draft.id, event.text, replace = true)
              }

              is AgentStreamEvent.ToolCallStarted -> startToolCall(draft.id, sessionId, event)

              is AgentStreamEvent.ToolCallFinished -> finishToolCall(draft.id, event)

              is AgentStreamEvent.TextDelta -> {
                settleThinking()
                // 第一条正文到达时清掉状态文案：否则「正在调用 xxx…」会留在气泡里。
                if (statusOnly) {
                  statusOnly = false
                  content.clear()
                }
                if (event.startsStep) stepStart = content.length
                content.append(event.text)
                val now = clock()
                if (event.startsStep || now - lastUiFlush >= UI_FLUSH_INTERVAL_MS) {
                  lastUiFlush = now
                  // UI 镜像直接取缓冲快照：坐标与缓冲永远一致，不必推理增量拼接的边界。
                  appendText(draft.id, content.toString(), replace = true)
                }
                if (now - lastFlush >= flushIntervalMs) {
                  lastFlush = now
                  flush(draft.id, content, reasoning, MessageStatus.Streaming, error = null)
                }
              }

              is AgentStreamEvent.StepFinal -> {
                settleThinking()
                // 增量只是先给眼睛看的；这一步的成品到了就**只替换本步**追加的部分。
                event.reasoning?.let { full ->
                  reasoning.setLength(reasoningStepStart.coerceAtMost(reasoning.length))
                  reasoning.append(full)
                  markReasoning(draft.id, reasoning.toString())
                }
                event.text?.let { full ->
                  content.setLength(stepStart.coerceAtMost(content.length))
                  content.append(full)
                  appendText(draft.id, content.toString(), replace = true)
                }
                lastUiFlush = clock()
                lastFlush = lastUiFlush
                flush(draft.id, content, reasoning, MessageStatus.Streaming, error = null)
              }

              AgentStreamEvent.Completed -> Unit
            }
          }
        } catch (cancellation: CancellationException) {
          stopped = true
          throw cancellation
        } catch (
          // 同上：任务跑批的兜底，失败要落到 failure 交给界面，不能直接炸掉协程。
          @Suppress("TooGenericExceptionCaught") throwable: Throwable,
        ) {
          failure = throwable
        } finally {
          withContext(NonCancellable) {
            val status =
              when {
                stopped -> MessageStatus.Stopped
                failure != null -> MessageStatus.Error
                else -> MessageStatus.Complete
              }
            val error = failure?.let { it.message ?: it.javaClass.simpleName }
            // 收尾前把 UI 与本地缓冲对齐：增量是合并刷新的（≤50ms），最后几个字可能还没上屏
            if (content.isNotEmpty()) appendText(draft.id, content.toString(), replace = true)
            if (reasoning.isNotEmpty()) markReasoning(draft.id, reasoning.toString())
            flush(draft.id, content, reasoning, status, error)
            // 这一轮没跑完就被停/出错时，把还挂在「执行中」的工具卡落成中断态。
            if (stopped || failure != null) finishRunningToolCalls(draft.id)
            finishStream(draft.id, error)
          }
        }
      }
  }

  /** 中止本次流式响应，已收到的内容保留（库里记为 Stopped）。 */
  fun stop() {
    streamJob?.cancel()
  }

  /** 切到指定会话；任务进行中忽略（UI 已置灰）。 */
  fun openSession(id: String) {
    if (!_state.value.canSwitchSession || id == _state.value.sessionId) return
    viewModelScope.launch { loadSession(id) }
  }

  /** 新建并切到一个空会话。 */
  fun newSession() {
    if (!_state.value.canSwitchSession) return
    viewModelScope.launch {
      runCatching { store.createSession(title = null).id }
        .onSuccess { id -> loadSession(id) }
        .onFailure { throwable -> reportStorageFailure(throwable) }
    }
  }

  /** 删除会话；删掉的正好是当前会话时，切到剩下最近的一个（没有就新建）。 */
  fun deleteSession(id: String) {
    if (!_state.value.canSwitchSession) return
    viewModelScope.launch {
      val deleted = runCatching { store.deleteSession(id) }
      if (deleted.isFailure) {
        // 删不掉就只报错：不把 ready 置灰，否则一次误删失败会连消息都发不出去。
        reportError(deleted.exceptionOrNull() ?: return@launch)
        return@launch
      }
      if (id == _state.value.sessionId) {
        runCatching { store.currentSession().id }
          .onSuccess { next -> loadSession(next) }
          .onFailure { throwable -> reportStorageFailure(throwable) }
      }
    }
  }

  private suspend fun restore() {
    val restored = runCatching { store.currentSession().id }
    restored
      .onSuccess { sessionId -> loadSession(sessionId) }
      .onFailure { throwable ->
        // 打不开库时故意不放行发送：否则消息看起来发出去了，其实一条都没存下。
        _state.update {
          it.copy(
            ready = false,
            storageError = throwable.message ?: throwable.javaClass.simpleName,
          )
        }
      }
  }

  private suspend fun loadSession(sessionId: String) {
    val loaded =
      runCatching {
        val rows = store.messages(sessionId)
        val calls = store.toolCalls(sessionId).groupBy { it.messageId }
        rows.mapNotNull { row -> row.toChatMessage(calls[row.id].orEmpty()) }
      }

    loaded
      .onSuccess { messages ->
        _state.update {
          it.copy(
            sessionId = sessionId,
            messages = messages,
            ready = true,
            storageError = null,
            isStreaming = false,
          )
        }
      }
      .onFailure { throwable -> reportStorageFailure(throwable) }
  }

  private fun reportStorageFailure(throwable: Throwable) {
    _state.update {
      it.copy(
        ready = false,
        storageError = throwable.message ?: throwable.javaClass.simpleName,
      )
    }
  }

  /** 非致命失败：只把原因显示出来，不拦着用户继续用。 */
  private fun reportError(throwable: Throwable) {
    _state.update { it.copy(storageError = throwable.message ?: throwable.javaClass.simpleName) }
  }

  /** 记下工具调用开始：先上屏，再落库（落库失败只影响重启后的卡片）。 */
  private suspend fun startToolCall(messageId: String, sessionId: String, event: AgentStreamEvent.ToolCallStarted) {
    val callId = event.callId?.takeIf { it.isNotBlank() } ?: Uuids.v7()
    val card = ToolCallView(id = callId, name = event.name, arguments = event.arguments)
    _state.update { state ->
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
  private suspend fun finishToolCall(messageId: String, event: AgentStreamEvent.ToolCallFinished) {
    val target =
      _state.value.messages
        .firstOrNull { it.id == messageId }
        ?.toolCalls
        ?.lastOrNull { event.callId == null || it.id == event.callId }
        ?: return
    val state = if (event.ok) ToolCallState.Ok else ToolCallState.Failed
    _state.update { snapshot ->
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
  private suspend fun finishRunningToolCalls(messageId: String) {
    val running =
      _state.value.messages
        .firstOrNull { it.id == messageId }
        ?.toolCalls
        ?.filter { it.state == ToolCallState.Running }
        .orEmpty()
    if (running.isEmpty()) return
    _state.update { snapshot ->
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

  private suspend fun flush(
    messageId: String,
    content: StringBuilder,
    reasoning: StringBuilder,
    status: MessageStatus,
    error: String?,
  ) {
    store.updateMessage(
      id = messageId,
      content = content.toString(),
      reasoning = reasoning.toString().ifBlank { null },
      status = status,
      error = error,
      finishedAt = if (status == MessageStatus.Streaming) null else clock(),
    )
  }

  /** 记录思考时长（毫秒）；界面显示「思考了 3.8 秒」。 */
  private fun markThinkingMs(assistantId: String, ms: Long) {
    _state.update { state ->
      state.copy(
        messages = state.messages.map { message -> if (message.id == assistantId) message.copy(thinkingMs = ms) else message },
      )
    }
  }

  private fun markReasoning(assistantId: String, reasoning: String) {
    _state.update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) {
              message.copy(reasoning = reasoning.ifBlank { null }, thinking = message.content.isEmpty())
            } else {
              message
            }
          },
      )
    }
  }

  private fun appendText(assistantId: String, text: String, replace: Boolean = false) {
    _state.update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) {
              message.copy(
                content = if (replace) text else message.content + text,
                streaming = true,
                thinking = false,
              )
            } else {
              message
            }
          },
      )
    }
  }

  private fun finishStream(assistantId: String, error: String?) {
    _state.update { state ->
      state.copy(
        isStreaming = false,
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) {
              message.copy(streaming = false, thinking = false, error = error)
            } else {
              message
            }
          },
      )
    }
  }

  private companion object {
    const val FLUSH_INTERVAL_MS = 200L

    /** 增量刷新界面的最小间隔：token 级帧可能每秒几十条，逐帧重算 Markdown 不划算。 */
    const val UI_FLUSH_INTERVAL_MS = 50L

    /** 会话标题取第一条用户消息的前若干字。 */
    const val TITLE_MAX_CHARS = 24
  }
}

private fun StoredMessage.toChatMessage(toolCalls: List<StoredToolCall>): ChatMessage? {
  if (role != MessageRole.User && role != MessageRole.Assistant) return null
  // 进程被杀时留下的空占位不该显示成一个空气泡
  if (content.isBlank() && reasoning.isNullOrBlank() && error == null && toolCalls.isEmpty()) return null
  return ChatMessage(
    id = id,
    role = if (role == MessageRole.User) ChatRole.User else ChatRole.Assistant,
    content = content,
    streaming = status == MessageStatus.Streaming,
    reasoning = reasoning,
    error = error,
    toolCalls = toolCalls.map(StoredToolCall::toToolCallView),
  )
}

private fun StoredToolCall.toToolCallView() =
  ToolCallView(
    id = id,
    name = name,
    arguments = arguments,
    result = result,
    state =
      when (status) {
        ToolCallStatus.Running -> ToolCallState.Running
        ToolCallStatus.Ok -> ToolCallState.Ok
        ToolCallStatus.Failed -> ToolCallState.Failed
      },
  )

package com.tappet.app.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.db.ToolCallStatus
import com.tappet.app.data.db.Uuids
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.StoredMessage
import com.tappet.app.data.session.StoredToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

/**
 * 切会话时要连带切换「谁在说话」：日常会话用当前人格，酒馆会话用那张卡。
 *
 * 抽成接口而不是直接调 PersonaStore：ViewModel 在纯 JVM 单测里跑，没有 Context，
 * 也不该知道偏好文件和运行时重载这两件事。
 */
fun interface RoleSwitcher {
  /** characterId 为 null 表示回到日常（人格接管）。失败时抛异常，由界面显示原因。 */
  suspend fun activate(characterId: String?)
}

/** 酒馆侧栏的一行：一张角色卡 + 它名下的会话（最近的在前，可能为空）。 */
data class RoleplayGroup(
  val characterId: String,
  val name: String,
  val tagline: String,
  val sessions: List<ChatSession>,
  /** 收藏（置顶）：侧栏排在最前，行首带一个记号。 */
  val pinned: Boolean = false,
) {
  val latest: ChatSession? get() = sessions.firstOrNull()
  val isEmpty: Boolean get() = sessions.isEmpty()
}

/**
 * 时钟 / 落库节流 / IO 调度。
 *
 * 抽成一个值对象不是为了好看：这三个参数只在测试里被替换，而构造签名已经顶到
 * detekt 的 8 参数阈值。合并后新增能力就不会再撑破阈值、逼人去加抑制注释。
 */
data class ChatTiming(
  val clock: () -> Long = System::currentTimeMillis,
  val flushIntervalMs: Long = 200L,
  val dispatcher: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * 酒馆依赖。
 *
 * [characters] 读的是 assets 与导入目录；[switcher] 决定切会话时要换成谁的系统提示。
 * 两个都给了默认值：纯聊天的单测与场景不需要它们，酒馆功能自然退化成「只读历史」。
 */
data class RoleplayBinding(
  val characters: () -> List<CharacterCard> = { emptyList() },
  val switcher: RoleSwitcher? = null,
  /** 收藏（置顶）的卡 id，最近收藏在前；侧栏按它把收藏的排到最前。 */
  val pins: () -> List<String> = { emptyList() },
)

data class ChatUiState(
  val sessionId: String? = null,
  /** 全部会话，最近更新在前，供侧栏列表使用。 */
  val sessions: List<ChatSession> = emptyList(),
  val messages: List<ChatMessage> = emptyList(),
  val input: String = "",
  val isStreaming: Boolean = false,
  val configSummary: String = "",
  val configured: Boolean = true,
  /** 当前提供商 / 模型（聊天页切换器用）。 */
  val providerId: String = "",
  val providerName: String = "",
  val model: String = "",
  /** 正在重载运行时；期间禁止再次切换或发消息。 */
  val switchingModel: Boolean = false,
  val switchError: String? = null,
  /** 历史消息是否已从库里读完。读完之前不允许发送，否则会插到错误的位置。 */
  val ready: Boolean = false,
  /** 本地库打不开时的原因；不为空说明这次启动读不了也写不了。 */
  val storageError: String? = null,
  /** 酒馆分区：每张角色卡一行，带它名下的会话。 */
  val roleplayGroups: List<RoleplayGroup> = emptyList(),
  /** 当前会话绑定的角色（日常会话为 null）。 */
  val activeCharacterId: String? = null,
  val activeCharacterName: String? = null,
  /** 正在切角色（要重载运行时，1–2 秒）。 */
  val switchingRole: Boolean = false,
) {
  val canSend: Boolean
    get() = ready && input.isNotBlank() && !isStreaming

  /** 任务进行中不允许切会话：事件流只认当前会话，切走会把半截回复写错地方。 */
  val canSwitchSession: Boolean
    get() = ready && !isStreaming && !switchingRole

  /** 是否在酒馆会话里（顶栏据此显示角色名与「退出」）。 */
  val inRoleplay: Boolean
    get() = activeCharacterId != null
}

/**
 * 聊天状态机 + 持久化。
 *
 * 落库时机有两处刻意设计：
 * 1. **发起时就插入助手消息**（status = Streaming），正文按 timing.flushIntervalMs 节流更新 ——
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
  /** 时钟、落库节流、IO 调度：只有测试需要替换，打包成一个参数（构造参数已经压到 detekt 阈值边上了）。 */
  private val timing: ChatTiming = ChatTiming(),
  /** 聊天页的提供商 / 模型切换（保存 + 重载运行时）；null 时界面不显示切换入口。 */
  private val switcher: ModelSwitcher? = null,
  /** 酒馆相关的两个依赖（角色卡来源 + 载体切换），打包成一个参数免得构造签名无限变长。 */
  private val roleplay: RoleplayBinding = RoleplayBinding(),
  /**
   * 重新读一份当前配置（设置页里换了模型 / 密钥之后，聊天页顶栏要跟着变）。
   * 传函数而不是传 Context：ViewModel 不持有 Context，也不会常驻一份明文密钥。
   */
  private val configSource: () -> AgentConfig? = { null },
) : ViewModel() {
  private val reasoningLabel = config.reasoningEffort ?: "默认"

  private val _state =
    MutableStateFlow(
      ChatUiState(
        configSummary = config.shortSummary(),
        configured = config.isConfigured,
        providerId = config.preset.id,
        providerName = config.preset.label,
        model = config.model,
      ),
    )
  val state: StateFlow<ChatUiState> = _state.asStateFlow()

  /** 会话级模型：切开 / 装回都记在会话上（运行时只有一个模型，这里存的是「当时用的是哪个」）。 */
  private val sessionModels =
    SessionModels(
      switcher = switcher,
      store = store,
      scope = viewModelScope,
      now = { _state.value.model },
      // 进会话自动装回模型时，顶栏那一行要跟着换（走切换器的那条路自己会更新状态，
      // 这条路绕过了它）。写成行内是为了不再多一个方法 —— 方法数已到阈值边缘。
      onApplied = { choice ->
        _state.update {
          it.copy(
            providerId = choice.providerId,
            providerName = choice.providerName,
            model = choice.model,
            configured = true,
            configSummary = "${choice.model} · $reasoningLabel",
          )
        }
      },
      reportFailure = { message -> _state.update { it.copy(switchError = message) } },
    )

  /** 聊天页的模型切换状态机（读候选 / 落盘 / 重载运行时）。 */
  val modelSwitch =
    ChatModelSwitchController(
      switcher = switcher,
      scope = viewModelScope,
      reasoningLabel = reasoningLabel,
      snapshot = { _state.value },
      update = { transform -> _state.update(transform) },
      ioDispatcher = timing.dispatcher,
      onApplied = { choice -> sessionModels.remember(_state.value.sessionId, choice.model) },
    )

  /** 酒馆分区的会话操作（新开 / 回到 / 退出）。 */
  val roleplayActions =
    RoleplaySessions(
      scope = viewModelScope,
      store = store,
      groups = { _state.value.roleplayGroups },
      open = { session -> viewModelScope.launch { switchTo(session) } },
      reportFailure = ::reportStorageFailure,
    )

  private var streamJob: Job? = null

  /** 当前已生效的载体（null = 日常人格）。 */
  private var activatedCharacterId: String? = null

  /** 是否已经跟运行时同步过一次 —— 冷启动时偏好里的角色可能和当前会话不一致，必须先对一次。 */
  private var roleActivated = false

  init {
    viewModelScope.launch { restore() }
    // 会话列表跟着库走：发消息改标题、删会话都会自动反映到侧栏。
    viewModelScope.launch {
      store.observeSessions().catch { /* 列表读不到不影响聊天本身 */ }.collect { sessions ->
        // 这里刻意**不切线程**：读卡是 assets.list + 几个几百字节的 JSON，只在会话列表变化时
        // 跑一次；而切到 IO 会让这个 flow 变成跨调度器的长生命周期协程 —— 单测里 runTest 结束
        // 后它还在跑，异常会漏到下一个用例（UncaughtExceptionsBeforeTest）。
        val cards = runCatching { roleplay.characters() }.getOrDefault(emptyList())
        val pins = runCatching { roleplay.pins() }.getOrDefault(emptyList())
        val groups = groupRoleplaySessions(sessions, cards, pins)
        _state.update { state ->
          val name = groups.firstOrNull { group -> group.characterId == state.activeCharacterId }?.name
          state.copy(
            sessions = sessions,
            roleplayGroups = groups,
            activeCharacterName = name ?: state.activeCharacterName,
          )
        }
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
        var lastFlush = timing.clock()
        // 增量合并：UI 最多每 UI_FLUSH_INTERVAL_MS 刷一次 —— token 级帧很多，
        // 逐帧重算 Markdown 太贵；DB 那边另有 timing.flushIntervalMs 的节流。
        var lastUiFlush = timing.clock()
        // 本步起点：StepFinal 只替换**这一步**追加的文本（多步回答的前几步不能被吃掉）。
        var stepStart = 0
        var reasoningStepStart = 0
        // 思考时长：第一次推理增量起算，第一条正文到达时结算（只活在这一轮，不入库）
        var thinkingStartedAt: Long? = null

        fun settleThinking() {
          val started = thinkingStartedAt ?: return
          thinkingStartedAt = null
          markThinkingMs(draft.id, (timing.clock() - started).coerceAtLeast(0))
        }

        try {
          streamer.stream(sessionId, prompt).collect { event ->
            when (event) {
              is AgentStreamEvent.ReasoningDelta -> {
                if (thinkingStartedAt == null) {
                  val started = timing.clock()
                  thinkingStartedAt = started
                  // 界面据此把「思考中 1.2 秒」实时往上走；结算时换成 thinkingMs。
                  markThinkingStarted(draft.id, started)
                }
                if (event.startsStep) reasoningStepStart = reasoning.length
                reasoning.append(event.text)
                val now = timing.clock()
                if (event.startsStep || now - lastUiFlush >= UI_FLUSH_INTERVAL_MS) {
                  lastUiFlush = now
                  markReasoning(draft.id, reasoning.toString())
                }
                if (now - lastFlush >= timing.flushIntervalMs) {
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
                val now = timing.clock()
                if (event.startsStep || now - lastUiFlush >= UI_FLUSH_INTERVAL_MS) {
                  lastUiFlush = now
                  // UI 镜像直接取缓冲快照：坐标与缓冲永远一致，不必推理增量拼接的边界。
                  appendText(draft.id, content.toString(), replace = true)
                }
                if (now - lastFlush >= timing.flushIntervalMs) {
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
                lastUiFlush = timing.clock()
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
            // 思考中被打断也要结算时长，否则界面会永远停在「思考中」。
            settleThinking()
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
    val snapshot = _state.value
    if (!snapshot.canSwitchSession || id == snapshot.sessionId) return
    val known = snapshot.sessions.firstOrNull { it.id == id }
    viewModelScope.launch {
      // 列表里没有就查库：交接可能比列表刷新先到（刚建好的那段会话就是这样）。
      val session = known ?: runCatching { store.session(id) }.getOrNull() ?: return@launch
      switchTo(session)
    }
  }

  /** 新建并切到一个空的日常会话。 */
  fun newSession() {
    if (!_state.value.canSwitchSession) return
    viewModelScope.launch {
      runCatching { store.createSession(title = null) }
        .onSuccess { session -> switchTo(session) }
        .onFailure { throwable -> reportStorageFailure(throwable) }
    }
  }

  // 回到聊天页时对齐外部状态：设置页与角色页是压栈进来的，在那里换了模型、收藏了角色，
  // 聊天页的 state 还是旧的 —— 顶栏写着上一个模型、收藏的角色没排到前面。重进时对齐一次，
  // 比到处发广播简单。写成属性而不是方法，是因为这个类的方法数正压在 detekt 阈值上。
  val resumeRefresh: () -> Unit = {
    configSource()?.let { fresh ->
      _state.update {
        it.copy(
          configured = fresh.isConfigured,
          providerId = fresh.preset.id,
          providerName = fresh.preset.label,
          model = fresh.model,
          configSummary = fresh.shortSummary(),
        )
      }
    }
    val cards = runCatching { roleplay.characters() }.getOrDefault(emptyList())
    val pins = runCatching { roleplay.pins() }.getOrDefault(emptyList())
    val groups = groupRoleplaySessions(_state.value.sessions, cards, pins)
    _state.update { it.copy(roleplayGroups = groups) }
  }

  /**
   * 切会话 = 切「谁在说话」+ 换历史。
   *
   * 载体不同才重载运行时：同一张卡的多段会话之间来回切不该付 1–2 秒的重载代价。
   * 重载失败**不拦住**进会话 —— 读历史不需要运行时，进去看到内容比卡在灰色界面有用。
   */
  private suspend fun switchTo(session: ChatSession) {
    val target = session.characterId
    val switcher = roleplay.switcher
    if (switcher != null && (!roleActivated || target != activatedCharacterId)) {
      _state.update { it.copy(switchingRole = true, switchError = null) }
      val result = runCatching { switcher.activate(target) }
      _state.update { it.copy(switchingRole = false) }
      result.exceptionOrNull()?.let { error ->
        _state.update {
          it.copy(switchError = "切换角色失败：${error.message ?: error.javaClass.simpleName}")
        }
      }
      activatedCharacterId = target
      roleActivated = true
    }
    // 这段会话上次用的是别的模型就装回去（同模型不白 reload）。
    sessionModels.restore(session)
    loadSession(session.id)
    _state.update { state ->
      // 名字从角色表里取，不从会话标题取：标题是「第一句话」，不是卡名。
      val name = state.roleplayGroups.firstOrNull { it.characterId == target }?.name
      state.copy(activeCharacterId = target, activeCharacterName = name)
    }
  }

  /** 删除会话；删掉的正好是当前会话时，切到剩下最近的一个（没有就新建）。 */
  fun deleteSession(id: String) {
    if (!_state.value.canSwitchSession) return
    viewModelScope.launch {
      val deleted = runCatching { store.deleteSession(id) }
      if (deleted.isFailure) {
        // 删不掉就只报错：不把 ready 置灰，否则一次误删失败会连消息都发不出去。
        val reason = deleted.exceptionOrNull() ?: return@launch
        _state.update { it.copy(storageError = reason.message ?: reason.javaClass.simpleName) }
        return@launch
      }
      if (id == _state.value.sessionId) {
        // 先在同角色剩下的会话里找一段，再退回日常 —— 删一段不该把人踢出这个角色。
        val deleted = _state.value.sessions.firstOrNull { it.id == id }
        val sibling =
          deleted?.characterId?.let { cardId ->
            _state.value.roleplayGroups
              .firstOrNull { it.characterId == cardId }
              ?.sessions
              ?.firstOrNull { it.id != id }
          }
        if (sibling != null) {
          switchTo(sibling)
        } else {
          runCatching { store.currentSession() }
            .onSuccess { next -> switchTo(next) }
            .onFailure { throwable -> reportStorageFailure(throwable) }
        }
      }
    }
  }

  private suspend fun restore() {
    // 先看「上次在说话的地方」（可能是酒馆），没有才退回日常会话。
    val restored = runCatching { store.latestSession() ?: store.currentSession() }
    restored
      .onSuccess { session -> switchTo(session) }
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
      finishedAt = if (status == MessageStatus.Streaming) null else timing.clock(),
    )
  }

  /** 记录思考时长（毫秒）；界面显示「思考了 3.8 秒」。清掉起点，实时计时停止。 */
  private fun markThinkingMs(assistantId: String, ms: Long) {
    _state.update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) message.copy(thinkingMs = ms, thinkingStartedAt = null) else message
          },
      )
    }
  }

  /** 记录思考开始时间；界面据此实时显示「思考中 X 秒」。 */
  private fun markThinkingStarted(assistantId: String, at: Long) {
    _state.update { state ->
      state.copy(
        messages =
          state.messages.map { message ->
            if (message.id == assistantId) message.copy(thinkingStartedAt = at) else message
          },
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

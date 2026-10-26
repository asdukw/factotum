package com.tappet.app.agent

import android.util.Log
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
import com.tappet.app.data.session.variantsOrNone
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
  /**
   * 酒馆直连通道（自己拼 messages 数组、直请 /chat/completions）。
   *
   * 为 null 表示这家提供商不支持直连（例如 Anthropic 协议），酒馆会话退回 dsh 通道 ——
   * 退回去仍然能演，只是世界书回到「粘在消息前」、重写回到「删了重问」。
   */
  val tavern: TavernTurns? = null,
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
  /**
   * 候选回答的读写。
   *
   * 用属性而不是构造参数：那个签名已经 7 个参数、压在 detekt 阈值上。真实现（[RoomSessionStore]）
   * 本身就实现了 [VariantStore]，测试替身没有就退化成空实现 —— 测试里本来也不会去切候选。
   */
  private val variants = store.variantsOrNone()

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

  /**
   * 用一条开场白开始这个会话：当成第一条助手消息落库。
   *
   * 以前开场白只活在系统提示里（模板变量 `{{first_mes}}`），会话列表上一条消息都没有；v3 卡带多条
   * 备用开场白时更没地方挑。落库之后它就是普通历史 —— 重写/删这轮照常用，而且 `firstTurn` 自然
   * 变成 false，系统提示里不会再塞一遍开场白。
   */
  val seedGreeting: (String) -> Unit = { text ->
    val snapshot = _state.value
    val sessionId = snapshot.sessionId
    // 开场白只能落在空会话上：已经有消息的会话再塞一条，历史就凭空多出个人说过的话。
    val emptySession = snapshot.ready && !snapshot.isStreaming && snapshot.messages.isEmpty()
    Log.d("TappetAgent", "seed greeting chars=${text.length}")
    if (emptySession && sessionId != null && text.isNotBlank()) {
      viewModelScope.launch {
        runCatching { store.appendMessage(sessionId, MessageRole.Assistant, text.trim(), MessageStatus.Complete) }
          .onFailure { reportStorageFailure(it) }
        loadSession(sessionId)
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
    // 真正发出去的文本可能比用户打的多一段世界书资料；界面与库里存的仍是 `prompt`。
    // 酒馆会话走直连通道时这句话用不上（设定由 TavernPrompt 排进 messages），但 fallback 到
    // dsh 的那条路要用它 —— 所以照算，不在这里分叉。
    val wire = withLore(snapshot, prompt)
    val tavern = roleplay.tavern?.enabled == true && snapshot.activeCharacterId != null

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

        streamInto(sessionId, draft.id, wire, tavern)
      }
  }

  /**
   * 把一轮响应流进 [draftId]。
   *
   * 抽出来是因为「重写这一轮」和「发一条新的」除了开头那两句之外走的是同一条路：
   * 都要落库、都要节流、都要在异常里把状态收尾。复制一份的下场是两处行为慢慢分叉。
   *
   * [wire] 是**真正发出去**的文本（可能带着世界书资料），界面与库里存的是调用方给的那份。
   */
  private suspend fun streamInto(sessionId: String, draftId: String, wire: String, tavern: Boolean = false) {
    val turn = TurnRun(sessionId, draftId)
    // 酒馆通道自己拿整段历史（含刚落库的这一问）；拼不出来（卡被删、没配提供商）就退回 dsh。
    val direct = if (tavern) roleplay.tavern?.stream(sessionId, _state.value.activeCharacterId, _state.value.messages) else null
    try {
      (direct ?: streamer.stream(sessionId, wire)).collect { event -> turn.handle(event) }
    } catch (cancellation: CancellationException) {
      turn.stopped = true
      throw cancellation
    } catch (
      // 任务跑批的兜底，失败要落到 failure 交给界面，不能直接炸掉协程。
      @Suppress("TooGenericExceptionCaught") throwable: Throwable,
    ) {
      turn.failure = throwable
    } finally {
      withContext(NonCancellable) { turn.finish() }
    }
  }

  /**
   * 一轮响应的可变状态与收帧逻辑。
   *
   * 抽成类不是为了复用，而是为了**把 `when` 的每个分支收成一个小函数** —— 七个分支挤在
   * 一个函数里，改一处要读一百行，复杂度也压不下来。这里的状态只活在一轮之内。
   */
  private inner class TurnRun(
    private val sessionId: String,
    private val draftId: String,
  ) {
    val content = StringBuilder()
    val reasoning = StringBuilder()
    var failure: Throwable? = null
    var stopped = false
    private var statusOnly = false

    // 增量合并：UI 最多每 UI_FLUSH_INTERVAL_MS 刷一次 —— token 级帧很多，
    // 逐帧重算 Markdown 太贵；DB 那边另有 timing.flushIntervalMs 的节流。
    private var lastFlush = timing.clock()
    private var lastUiFlush = timing.clock()

    // 本步起点：StepFinal 只替换**这一步**追加的文本（多步回答的前几步不能被吃掉）。
    private var stepStart = 0
    private var reasoningStepStart = 0

    // 思考时长：第一次推理增量起算，第一条正文到达时结算（只活在这一轮，不入库）
    private var thinkingStartedAt: Long? = null

    suspend fun handle(event: AgentStreamEvent) {
      when (event) {
        is AgentStreamEvent.ReasoningDelta -> onReasoning(event)
        is AgentStreamEvent.Status -> onStatus(event.text)
        is AgentStreamEvent.ToolCallStarted -> startToolCall(draftId, sessionId, event)
        is AgentStreamEvent.ToolCallFinished -> finishToolCall(draftId, event)
        is AgentStreamEvent.TextDelta -> onTextDelta(event)
        is AgentStreamEvent.StepFinal -> onStepFinal(event)
        AgentStreamEvent.Completed -> Unit
      }
    }

    private suspend fun onReasoning(event: AgentStreamEvent.ReasoningDelta) {
      if (thinkingStartedAt == null) {
        val started = timing.clock()
        thinkingStartedAt = started
        // 界面据此把「思考中 1.2 秒」实时往上走；结算时换成 thinkingMs。
        markThinkingStarted(draftId, started)
      }
      if (event.startsStep) reasoningStepStart = reasoning.length
      reasoning.append(event.text)
      val now = timing.clock()
      if (event.startsStep || now - lastUiFlush >= UI_FLUSH_INTERVAL_MS) {
        lastUiFlush = now
        markReasoning(draftId, reasoning.toString())
      }
      throttleFlush(now)
    }

    private suspend fun onStatus(text: String) {
      statusOnly = true
      content.clear()
      content.append(text)
      appendText(draftId, text, replace = true)
    }

    private suspend fun onTextDelta(event: AgentStreamEvent.TextDelta) {
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
        appendText(draftId, content.toString(), replace = true)
      }
      throttleFlush(now)
    }

    private suspend fun onStepFinal(event: AgentStreamEvent.StepFinal) {
      settleThinking()
      // 增量只是先给眼睛看的；这一步的成品到了就**只替换本步**追加的部分。
      event.reasoning?.let { full ->
        reasoning.setLength(reasoningStepStart.coerceAtMost(reasoning.length))
        reasoning.append(full)
        markReasoning(draftId, reasoning.toString())
      }
      event.text?.let { full ->
        content.setLength(stepStart.coerceAtMost(content.length))
        content.append(full)
        appendText(draftId, content.toString(), replace = true)
      }
      lastUiFlush = timing.clock()
      lastFlush = lastUiFlush
      flush(draftId, content, reasoning, MessageStatus.Streaming, error = null)
    }

    /** 节流落库：增量可能每帧都来，200ms 一次已足够「进程被杀也不丢太多」。 */
    private suspend fun throttleFlush(now: Long) {
      if (now - lastFlush < timing.flushIntervalMs) return
      lastFlush = now
      flush(draftId, content, reasoning, MessageStatus.Streaming, error = null)
    }

    private fun settleThinking() {
      val started = thinkingStartedAt ?: return
      thinkingStartedAt = null
      markThinkingMs(draftId, (timing.clock() - started).coerceAtLeast(0))
    }

    /** 收尾：定状态、把最后的缓冲对齐到界面与库，再把还挂着的工具卡落成中断态。 */
    suspend fun finish() {
      val status =
        when {
          stopped -> MessageStatus.Stopped
          failure != null -> MessageStatus.Error
          else -> MessageStatus.Complete
        }
      val error = failure?.let { it.message ?: it.javaClass.simpleName }
      // 收尾前把 UI 与本地缓冲对齐：增量是合并刷新的（≤50ms），最后几个字可能还没上屏
      if (content.isNotEmpty()) appendText(draftId, content.toString(), replace = true)
      if (reasoning.isNotEmpty()) markReasoning(draftId, reasoning.toString())
      flush(draftId, content, reasoning, status, error)
      // 这一轮没跑完就被停/出错时，把还挂在「执行中」的工具卡落成中断态。
      if (stopped || failure != null) finishRunningToolCalls(draftId)
      // 思考中被打断也要结算时长，否则界面会永远停在「思考中」。
      settleThinking()
      finishStream(draftId, error)
    }
  }

  /**
   * 给这一轮补上命中的世界书条目。
   *
   * 只在发送这一刻算：卡是数据，随时可能被改；把结果缓存下来会让「改了设定但没生效」变成一个
   * 说不清的状态。最近三条用户消息一起当关键词来源 —— 「她呢」这种指代只有连着上文才命中。
   */
  private val withLore: (ChatUiState, String) -> String = { snapshot, text ->
    val card = snapshot.activeCharacterId?.let { id -> roleplay.characters().firstOrNull { it.id == id } }
    val recent = snapshot.messages.filter { it.role == ChatRole.User }.map { it.content }.takeLast(3)
    LoreInject.augment(card, text, recent)
  }

  /**
   * 重写最后一条回复。
   *
   * **酒馆通道**下是真重写：上一轮回复先从历史里剔掉，模型看到的上下文里这句话还没被回答。
   * **dsh 通道**下只能删了再让它说一遍 —— 那边的上下文删不掉，新回复会建立在「我上一条说过了」
   * 之上；这不是完美实现，但它是唯一不需要 SDK 提供回滚 API 的做法。
   *
   * 这里刻意**不用 `canSend`**：它要求输入框非空，于是「清空输入框后点重写」会静默什么都不做
   * （真机上就是这么卡了半天）。能不能重写，取决于有没有可重写的上一轮，跟输入框无关。
   */
  val regenerate: () -> Unit = lambda@{
    val snapshot = _state.value
    if (!snapshot.ready || snapshot.isStreaming || snapshot.switchingRole) return@lambda
    val sessionId = snapshot.sessionId ?: return@lambda
    val last = snapshot.messages.lastOrNull() ?: return@lambda
    if (last.role != ChatRole.Assistant || last.streaming || last.content.isBlank()) return@lambda

    // 重写挂在「最后一句用户话」上：有了 candidates，旧回答就不必删 —— 两份并存、只显示选中的那份。
    // 开场白没有对应的提问（它前面没有用户消息），那种情况仍然替换：给它编一个 anchor 只会让界面
    // 出现「2/2」却没有用户可回退的提问。
    val anchor = snapshot.messages.lastOrNull { it.role == ChatRole.User }?.id

    streamJob =
      viewModelScope.launch {
        val draft =
          runCatching {
            if (anchor == null) {
              store.deleteMessage(last.id)
              store.appendMessage(sessionId, MessageRole.Assistant, "", MessageStatus.Streaming)
            } else {
              val existing = variants.variants(anchor)
              val fresh = store.appendMessage(sessionId, MessageRole.Assistant, "", MessageStatus.Streaming)
              if (existing.isEmpty()) {
                // 第一次重写：把眼前这条登记成第 0 份，新的排第 1 份
                variants.tagVariant(last.id, anchor, 0)
                variants.tagVariant(fresh.id, anchor, 1)
              } else {
                variants.tagVariant(fresh.id, anchor, (existing.maxOfOrNull { it.variantIndex } ?: 0) + 1)
              }
              variants.selectVariant(anchor, fresh.id)
              fresh
            }
          }
            .getOrElse { throwable ->
              reportStorageFailure(throwable)
              return@launch
            }
        _state.update {
          it.copy(
            isStreaming = true,
            messages =
              it.messages.dropLast(1) +
                ChatMessage(
                  id = draft.id,
                  role = ChatRole.Assistant,
                  streaming = true,
                  // anchor 必须带上：左右切靠它找组，漏了就是「点了没反应」（第一次真机就栽在这）
                  anchorId = anchor,
                  variantIndex = last.variantTotal.coerceAtLeast(1),
                  variantTotal = last.variantTotal.coerceAtLeast(1) + 1,
                ),
          )
        }
        // 直连通道下的重写是**真重写**：上一轮回复已经从历史里剔掉，模型看到的是「这句话还没回答」。
        // dsh 通道下仍得靠一句「再说一遍」的提示词，因为它那边的上下文删不掉。
        streamInto(
          sessionId = sessionId,
          draftId = draft.id,
          wire = withLore(snapshot, REGENERATE_PROMPT),
          tavern = roleplay.tavern?.enabled == true && snapshot.activeCharacterId != null,
        )
      }
  }

  /**
   * 删掉最后一轮（助手那条 + 它前面紧挨着的用户那条）。
   *
   * 只在本地删：dsh 那边的上下文照旧，所以下一句仍会「接着刚才」说。真要做到干净，
   * 需要按会话重放历史给运行时 —— 那是另一件事（见 roleplay 的设计笔记）。
   */
  val deleteLastTurn: () -> Unit = lambda@{
    val snapshot = _state.value
    if (snapshot.isStreaming) return@lambda
    val messages = snapshot.messages
    val lastAssistant = messages.indexOfLast { it.role == ChatRole.Assistant }
    if (lastAssistant < 0) return@lambda
    val from =
      if (lastAssistant > 0 && messages[lastAssistant - 1].role == ChatRole.User) {
        lastAssistant - 1
      } else {
        lastAssistant
      }
    val removed = messages.subList(from, messages.size).toList()
    if (removed.isEmpty()) return@lambda
    viewModelScope.launch {
      runCatching {
        // 被删的那条若属于一组候选，整组一起删：只删当前那份，剩下的会永远躺在库里访问不到
        removed.mapNotNull { it.anchorId }.distinct().forEach { variants.deleteGroup(it) }
        removed.forEach { store.deleteMessage(it.id) }
      }
        .onSuccess { _state.update { state -> state.copy(messages = state.messages.dropLast(removed.size)) } }
        .onFailure(::reportStorageFailure)
    }
  }

  /**
   * 从某一轮重来：把那条助手回复和它之后的一切丢掉，再把被重答的那句用户话重新发一遍。
   *
   * 为什么是「就地截断」而不是「分支树」：现在的消息表是线性 `seq`，同一轮并存多份候选需要
   * parent / 候选组两列，还要在界面里做左右滑。硬塞进现有结构会变成「删了什么、留了什么」
   * 说不清 —— 那比没有这个功能更糟。所以这里明说代价：**旧的那条回复真的没了**。
   *
   * 复用的是 [send] 那条已经被真机验过的路：先删库、改状态、把话填回输入框、再发。
   */
  val retryFrom: (String) -> Unit = lambda@{ messageId ->
    val snapshot = _state.value
    if (!snapshot.ready || snapshot.isStreaming || snapshot.switchingRole) return@lambda
    val index = snapshot.messages.indexOfFirst { it.id == messageId }
    if (index < 0 || snapshot.messages[index].role != ChatRole.Assistant) return@lambda
    // 连它回应的那句用户话一起丢掉，重发时才不会出现两份一样的提问
    val from = if (index > 0 && snapshot.messages[index - 1].role == ChatRole.User) index - 1 else index
    val dropped = snapshot.messages.subList(from, snapshot.messages.size).toList()
    // 要重答的是这条回复回应过的那句用户话 —— 它**就在要删的那一段里**，
    // 所以必须先从原列表取出来（第一轮时 take(from) 是空的，那样什么都不会发生）。
    val prompt =
      snapshot.messages.getOrNull(index - 1)?.takeIf { it.role == ChatRole.User }?.content
        ?: snapshot.messages.take(index).lastOrNull { it.role == ChatRole.User }?.content.orEmpty()
    if (prompt.isBlank()) return@lambda
    viewModelScope.launch {
      runCatching {
        dropped.mapNotNull { it.anchorId }.distinct().forEach { variants.deleteGroup(it) }
        dropped.forEach { store.deleteMessage(it.id) }
      }
        .onFailure { throwable ->
          reportStorageFailure(throwable)
          return@launch
        }
      _state.update { state -> state.copy(messages = state.messages.dropLast(dropped.size)) }
      onInputChange(prompt)
      send()
    }
  }

  /**
   * 左右切候选回答：[-1] 上一份、[1] 下一份（到头绕回）。
   *
   * 库里只改「哪一份被选中」这一列，然后整页重读 —— 不就地改 state：换了一份回答意味着后面
   * 对用户可见的一切（气泡、工具卡、计数）都该跟着换，就地改迟早会漏掉一处。
   */
  val swipeVariant: (Int) -> Unit = delta@{ step ->
    val snapshot = _state.value
    if (!snapshot.ready || snapshot.isStreaming || snapshot.switchingRole) return@delta
    val sessionId = snapshot.sessionId ?: return@delta
    val tail = snapshot.messages.lastOrNull { it.role == ChatRole.Assistant } ?: return@delta
    val anchor = tail.anchorId ?: return@delta
    if (tail.variantTotal <= 1) return@delta
    viewModelScope.launch {
      val group =
        runCatching { variants.variants(anchor) }
          .getOrElse { throwable ->
            reportStorageFailure(throwable)
            return@launch
          }
      if (group.size <= 1) return@launch
      val current = group.indexOfFirst { it.id == tail.id }.coerceAtLeast(0)
      val target = group[Variants.step(group.size, current, step)]
      val moved = runCatching { variants.selectVariant(anchor, target.id) }
      if (moved.isFailure) {
        reportStorageFailure(moved.exceptionOrNull() ?: IllegalStateException("切换候选失败"))
        return@launch
      }
      loadSession(sessionId)
    }
  }

  /** 中止本次流式响应，已收到的内容保留（库里记为 Stopped）。 */
  val stop: () -> Unit = { streamJob?.cancel() }

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
        // 候选计数按 anchor 查：一个会话里被重写过的轮次通常只有一两个，逐组查比一次全表扫简单
        val groups =
          rows.mapNotNull { it.anchorId }.distinct().associateWith { anchor -> variants.variants(anchor) }
        rows.mapNotNull { row ->
          val group = row.anchorId?.let { groups[it] }.orEmpty()
          row.toChatMessage(calls[row.id].orEmpty())?.copy(
            anchorId = row.anchorId,
            variantIndex = group.indexOfFirst { it.id == row.id }.coerceAtLeast(0),
            variantTotal = group.size,
          )
        }
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

    /**
     * 「重写」时真正发出去的那句。
     *
     * 不落库、不上屏 —— 它只是让模型再说一遍的把手。写成问句式的要求，比「重新生成」这种
     * 系统口吻更容易被角色接住：模型会顺着角色的语气重写，而不是跳出来解释自己在重写。
     */
    const val REGENERATE_PROMPT = "（重写你上一条回复：换个写法，不要用刚才的开头，也别解释你在重写。）"
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

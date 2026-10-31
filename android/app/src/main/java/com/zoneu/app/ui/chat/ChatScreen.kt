@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.zoneu.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.agent.AgentConfigStore
import com.zoneu.app.agent.AgentModelSwitcher
import com.zoneu.app.agent.ApprovalHub
import com.zoneu.app.agent.CharacterMemory
import com.zoneu.app.agent.CharacterPins
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.ChatMessage
import com.zoneu.app.agent.ChatRole
import com.zoneu.app.agent.ChatUiState
import com.zoneu.app.agent.ChatViewModel
import com.zoneu.app.agent.DshStreamer
import com.zoneu.app.agent.GroupCast
import com.zoneu.app.agent.HttpTavernChannel
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.agent.ModelChoice
import com.zoneu.app.agent.PersonaStore
import com.zoneu.app.agent.PrefsVarsStore
import com.zoneu.app.agent.RoleplayBinding
import com.zoneu.app.agent.RoleplayGroup
import com.zoneu.app.agent.SessionHandoff
import com.zoneu.app.agent.SessionRecap
import com.zoneu.app.agent.TavernMacros
import com.zoneu.app.agent.TavernTurns
import com.zoneu.app.agent.TavernVars
import com.zoneu.app.agent.Tokens
import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import com.zoneu.app.agent.UserQuestionHub
import com.zoneu.app.agent.WorldRuntime
import com.zoneu.app.data.session.ChatSession
import com.zoneu.app.data.session.SessionAdmin
import com.zoneu.app.data.session.SessionStore
import com.zoneu.app.data.session.zoneuSessionStore
import com.zoneu.app.device.AccessibilityStatus
import com.zoneu.app.device.BridgeHealth
import com.zoneu.app.device.DeviceAction
import com.zoneu.app.node.NodeRuntime
import com.zoneu.app.schedule.ScheduledTaskScheduler
import com.zoneu.app.schedule.needsExactAlarmReminder
import com.zoneu.app.ui.components.AppBanner
import com.zoneu.app.ui.components.ProviderModelIcon
import com.zoneu.app.ui.settings.ChevronRight
import com.zoneu.app.voice.RecognizedText
import com.zoneu.app.voice.VoiceInput
import com.zoneu.app.voice.VoiceReader
import com.zoneu.app.voice.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 原生聊天页（默认入口）。
 *
 * 视觉对齐 ChatGPT / DeepSeek 手机端：白底、极简顶栏、用户消息右侧浅色气泡、
 * 助手正文全宽无气泡、底部浮动圆角输入卡。工具调用 / 思考折叠保留为次要灰卡。
 */
@Composable
fun ChatScreen(
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenCharacters: () -> Unit = {},
  onOpenCharacterDetail: (String) -> Unit = {},
  store: SessionStore = LocalContext.current.zoneuSessionStore(),
) {
  val appContext = LocalContext.current.applicationContext
  val tavernTurns = rememberTavernTurns(appContext)
  // 前情提要在协程里跑；作用域取页面的，随页面销毁一起取消
  val recapScope = rememberCoroutineScope()
  val viewModel: ChatViewModel = viewModel {
    ChatViewModel(
      // 配置来自设置页（密钥走 Keystore 封装）。运行时只在需要时重载：
      // 聊天页切换模型会 remount dsh（同一个 Node 进程），不必重启 App。
      config = AgentConfigStore.current(appContext),
      streamer = DshStreamer(appContext),
      store = store,
      switcher = AgentModelSwitcher(appContext),
      // 回前台时重读一次配置：设置页里换了模型 / 填了新 key，顶栏不该还写着旧的。
      configSource = { AgentConfigStore.current(appContext) },
      roleplay =
        RoleplayBinding(
          characters = { CharacterStore.cards(appContext) },
          pins = { CharacterPins.pinned(appContext) },
          tavern = tavernTurns,
          recap = recapFor(store, tavernTurns, recapScope),
          // 进哪段会话，就换哪套「谁在说话」：酒馆用卡，日常用当前人格。
          // 载体没变时不重载 —— 同一张卡的多段会话互相切换不该付 1–2 秒。
          switcher = { characterId ->
            val current = PersonaStore.selectedCharacter(appContext)?.id
            if (current != characterId) {
              if (characterId == null) {
                PersonaStore.clearCharacter(appContext)
              } else {
                PersonaStore.selectCharacter(appContext, characterId)
              }
              NodeRuntime.reload(appContext)
            }
          },
        ),
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  SessionHandoffEffect(
    onSession = viewModel::openSession,
    onNormal = viewModel.roleplayActions::leave,
  )
  // 同一处再对齐一次外部状态：收藏 / 模型是在别的页面改的，回来时列表要按新状态重排。
  LifecycleResumeEffect(Unit) {
    viewModel.refreshConfiguration(AgentConfigStore.current(appContext))
    viewModel.resumeRefresh()
    onPauseOrDispose {}
  }
  ChatScreen(
    state = state,
    onInputChange = viewModel::onInputChange,
    onSend = viewModel::send,
    onStop = viewModel.stop,
    onNewSession = viewModel::newSession,
    onOpenSession = viewModel::openSession,
    onDeleteSession = viewModel::deleteSession,
    onNewRoleplaySession = viewModel.roleplayActions::new,
    onOpenRoleplay = viewModel.roleplayActions::openGroup,
    onLeaveRoleplay = viewModel.roleplayActions::leave,
    onOpenSettings = onOpenSettings,
    onOpenCharacters = onOpenCharacters,
    onOpenCharacterDetail = onOpenCharacterDetail,
    onSwitchModel = viewModel.modelSwitch::switch,
    onPrepareModelSwitch = viewModel.modelSwitch::prepare,
    onSeedGreeting = viewModel.seedGreeting,
    onImpersonate = viewModel.replies.impersonate,
    onStartGroup = viewModel.roleplayActions::newGroup,
    // 每次重组新建一个 BubbleActions 会让每个气泡都认为参数变了，整列消息跟着重组。
    actions = remember(viewModel) { bubbleActions(viewModel) },
    modifier = modifier,
  )
}

/**
 * 酒馆直连通道的绑定。
 *
 * 抽出来只是为了让 [ChatScreen] 留在 detekt 的行数阈值内 —— 这个函数体的身子本来就是
 * 一串依赖注入，没有分支。只有 OpenAI 兼容端点能这样发，否则返回 null（会话退回 dsh）。
 */
@Composable
private fun rememberTavernTurns(appContext: Context): TavernTurns =
  remember(appContext) {
    TavernTurns(
      cards = { CharacterStore.cards(appContext) },
      channel = {
        val current = AgentConfigStore.current(appContext)
        if (current.api == "openai-completions" && current.isConfigured) {
          HttpTavernChannel { AgentConfigStore.current(appContext) }
        } else {
          null
        }
      },
      memory = { id -> CharacterMemory.notes(appContext, id) },
      vars = PrefsVarsStore(appContext),
      world = WorldRuntime.direct(appContext),
    )
  }

/** 没有提要写入能力的替身（假 store / 单测）。写进去什么都不做。 */
private val NO_RECAP_WRITER =
  object : SessionAdmin {
    override suspend fun setSummary(sessionId: String, text: String, uptoMessageId: String) = Unit
  }

/** 前情提要的绑定（见 `SessionRecap`）：走酒馆通道的一次性补全，不另开 HTTP 客户端。 */
private fun recapFor(store: SessionStore, tavern: TavernTurns, scope: CoroutineScope) =
  SessionRecap(
    store = store,
    // 生产环境里 store 恒为 RoomSessionStore（同时实现了 SessionAdmin）；
    // 契约测试里的假 store 没有它，于是前情提要退化成「什么也不写」。
    admin = store as? SessionAdmin ?: NO_RECAP_WRITER,
    complete = { sessionId, system, user -> tavern.complete(sessionId, system, user) },
    start = { block -> scope.launch { block() } },
  )

/** ViewModel 那边的五个气泡动作绑成一包（包装层只是转发，不在这里写业务）。 */
private fun bubbleActions(viewModel: ChatViewModel) =
  BubbleActions(
    regenerate = viewModel.regenerate,
    deleteTurn = viewModel.deleteLastTurn,
    retryFrom = viewModel.retryFrom,
    swipe = viewModel.variantsSwitch.swipe,
    continueReply = viewModel.replies.continueReply,
    fork = viewModel.branching.fork,
    recapNow = viewModel.recapNow,
    editVar = viewModel.setSessionVar,
  )

/**
 * 气泡上的五个动作。
 *
 * 打成一包是因为它们**永远一起传**：五个独立参数要从 ViewModel 一路抄到气泡，
 * 每多一层就多五行样板（而 ChatScreen 那一串已经贴着 detekt 的 80 行上限）。
 * 真要加第六个动作时，也是在这一个类里加一行，不会动到任何调用点的参数列表。
 */
internal data class BubbleActions(
  val regenerate: () -> Unit = {},
  val deleteTurn: () -> Unit = {},
  val retryFrom: (String) -> Unit = {},
  val swipe: (Int) -> Unit = {},
  val continueReply: () -> Unit = {},
  val fork: (String) -> Unit = {},
  /** 手动写一条前情提要（见 `SessionRecap.summarizeNow`）。 */
  val recapNow: () -> Unit = {},
  /** 手改一个会话变量（卡写错了局的唯一出口）。 */
  val editVar: (String, String) -> Unit = { _, _ -> },
)

/**
 * 消费「去哪个会话」的交接意图。
 *
 * 用 LifecycleResumeEffect 而不是 LaunchedEffect：设置页/角色页是压栈进来又弹栈回去的，
 * **回到聊天页这一刻**才是会话真正该切的时候；LaunchedEffect 只在首次组合时跑一次。
 */
@Composable
private fun SessionHandoffEffect(onSession: (String) -> Unit, onNormal: () -> Unit) {
  LifecycleResumeEffect(Unit) {
    when (val target = SessionHandoff.take()) {
      is SessionHandoff.Target.Session -> onSession(target.id)
      SessionHandoff.Target.Normal -> onNormal()
      null -> Unit
    }
    onPauseOrDispose { }
  }
}

@Composable
internal fun ChatScreen(
  state: ChatUiState,
  onInputChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onNewSession: () -> Unit = {},
  onOpenSession: (String) -> Unit = {},
  onDeleteSession: (String) -> Unit = {},
  onNewRoleplaySession: (String) -> Unit = {},
  onOpenRoleplay: (String) -> Unit = {},
  onLeaveRoleplay: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
  onOpenCharacters: () -> Unit = {},
  onOpenCharacterDetail: (String) -> Unit = {},
  onSeedGreeting: (String) -> Unit = {},
  onSwitchModel: (ModelChoice) -> Unit = {},
  onPrepareModelSwitch: () -> List<ModelChoice> = { emptyList() },
  onImpersonate: () -> Unit = {},
  onStartGroup: (List<String>) -> Unit = {},
  actions: BubbleActions = BubbleActions(),
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  val greetings = rememberGreetings(state)

  HideKeyboardWhileStreaming(streaming = state.isStreaming, keyboard = keyboard, focus = focus)

  // 模型提问（`ask_user_question`）：面板浮在聊天之上。
  // 状态在 `UserQuestionHub` 这个单例里，不在 ViewModel —— 桥那边挂起的协程属于进程，
  // 而 UI 会随导航重建；放进 ViewModel 会让「切走再切回来」变成「答案丢了、插件还在等」。
  ChatOverlays()

  // 模型切换：sheet 打开时读一次候选；切换成功后自动收起，失败留在 sheet 里显示原因。
  val sheet = remember { ModelSheet() }

  // 群聊选择器：状态与对话框都在 GroupPickerHost 里（它要盖在抽屉之上，抽屉一关人还在）
  val groupPicker = remember { GroupPickerHost() }
  LaunchedEffect(state.switchingModel) { sheet.settle(state.switchingModel, state.switchError) }

  // 抽屉里也能打开模型切换：酒馆里换模型是常见动作（便宜的跑日常、好的跑角色），
  // 不该先关掉抽屉再去输入框找那个入口。
  val openModelSwitch: () -> Unit = {
    sheet.choices = onPrepareModelSwitch()
    sheet.open = true
    scope.launch { drawerState.close() }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    // 任务进行中不让侧滑切会话：事件流只认当前会话，切走会把半截回复写错地方。
    gesturesEnabled = state.canSwitchSession,
    drawerContent = {
      SessionDrawer(
        sessions = state.sessions,
        roleplayGroups = state.roleplayGroups,
        currentId = state.sessionId,
        activeCharacterId = state.activeCharacterId,
        switchingRole = state.switchingRole,
        enabled = state.canSwitchSession,
        configSummary = state.configSummary,
        onNewSession = closeThen(scope, drawerState, onNewSession),
        onOpenSession = closeThen(scope, drawerState) { id -> onOpenSession(id) },
        onDeleteSession = onDeleteSession,
        onNewRoleplaySession = closeThen(scope, drawerState) { id -> onNewRoleplaySession(id) },
        onOpenRoleplay = closeThen(scope, drawerState) { id -> onOpenRoleplay(id) },
        onOpenCharacters = closeThen(scope, drawerState, onOpenCharacters),
        onOpenSettings = closeThen(scope, drawerState, onOpenSettings),
        onOpenCharacterDetail = closeThen(scope, drawerState) { id -> onOpenCharacterDetail(id) },
        onOpenModelSwitch = openModelSwitch,
        onNewGroup = {
          scope.launch { drawerState.close() }
          groupPicker.open = true
        },
      )
    },
  ) {
    ChatBody(
      state = state,
      listState = listState,
      modifier = modifier,
      onInputChange = onInputChange,
      onSend = onSend,
      onStop = onStop,
      onNewSession = onNewSession,
      onLeaveRoleplay = onLeaveRoleplay,
      greetings = greetings,
      onSeedGreeting = onSeedGreeting,
      onImpersonate = onImpersonate,
      actions = actions,
      onOpenSessions = { scope.launch { drawerState.open() } },
      onOpenCharacterDetail = onOpenCharacterDetail,
      onModelSwitchClick = {
        sheet.choices = onPrepareModelSwitch()
        sheet.open = true
      },
    )
  }

  groupPicker.Render(groups = state.roleplayGroups, onStartGroup = onStartGroup)

  ModelSheetContent(sheet, state.switchingModel, state.switchError, onSwitchModel)
}

@Composable
private fun ModelSheetContent(sheet: ModelSheet, switching: Boolean, error: String?, onSelect: (ModelChoice) -> Unit) {
  if (sheet.open) {
    ModelSwitchSheet(
      choices = sheet.choices,
      switching = switching,
      error = error,
      onSelect = { choice ->
        sheet.pending = true
        onSelect(choice)
      },
      onDismiss = { sheet.reset() },
    )
  }
}

/**
 * 会话一开始跑就收起软键盘。
 *
 * 理由不是「看着清爽」：agent 接下来要在屏幕上操作（读屏 / 截图 / 点按），键盘既占掉小半屏，
 * 又会让 IME 窗口出现在控件树与截图里 —— 模型会把键盘本身当成页面内容的一部分，去点一个
 * 并不存在的按钮。挂在 isStreaming 而不是「点了发送」上：重载运行时、恢复中断的会话等路径
 * 也会进入流式状态，这样一处覆盖得到。
 */
@Composable
private fun HideKeyboardWhileStreaming(
  streaming: Boolean,
  keyboard: SoftwareKeyboardController?,
  focus: FocusManager,
) {
  LaunchedEffect(streaming) {
    if (!streaming) return@LaunchedEffect
    keyboard?.hide()
    focus.clearFocus()
    // 新的任务开始了，上一条还在念的话立刻停 —— 用户接下来要看的是新回复，
    // 旧回复的声音会和新的正文抢注意力。
    VoiceReader.stop()
  }
}

/** 朗读失败的提示只在屏幕上停一会儿：它是提示，不是需要用户处理的错误。
 *
 * 单独一个 composable 是为了让它没有布局职责 —— 效果与界面混在一起时，
 * 「这个分支到底影响布局还是只影响副作用」读不出来。
 */
@Composable
private fun VoiceErrorLinger(error: String?) {
  LaunchedEffect(error) {
    if (error != null) {
      delay(VOICE_ERROR_LINGER_MS)
      VoiceReader.clearError()
    }
  }
}

/**
 * 麦克风的点击入口：**没权限就申请，有权限就开录**。
 *
 * 权限弹窗只能从界面发起（ColorOS 会拦 adb 的 `pm grant`）。授完直接开录，
 * 不让用户再点一次麦克风 —— 他刚才点的就是「我要说话」。
 */
@Composable
private fun rememberDictationLauncher(): () -> Unit {
  val context = LocalContext.current
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) VoiceInput.start(context) else VoiceInput.clearError()
    }
  return remember(context, permission) {
    {
      if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
      ) {
        permission.launch(Manifest.permission.RECORD_AUDIO)
      } else {
        VoiceInput.toggle(context)
      }
    }
  }
}

/**
 * 识别结果回填输入框：**追加**而不是覆盖 —— 用户可能已经打了半句，或者连着说了两段。
 * 取走结果（[VoiceInput.clearResult]）再回填，避免旋转 / 重组时把同一段追加两遍。
 *
 * 回调过一层 `rememberUpdatedState`：回填时要用**最新**的输入内容，而
 * `LaunchedEffect` 只在结果变化时重启。
 */
@Composable
private fun DictationResultSink(onAppend: (String) -> Unit) {
  val result by VoiceInput.result.collectAsStateWithLifecycle()
  val append by rememberUpdatedState(onAppend)
  LaunchedEffect(result?.id) {
    val text = result?.text ?: return@LaunchedEffect
    VoiceInput.clearResult()
    append(text)
  }
}

/**
 * 语音输入的提示同样只停一会儿：它不需要用户处理（最常见的是「没听清」）。
 *
 * 与 [VoiceErrorLinger] 分开写，是因为两者的**清除入口不是同一个单例** ——
 * 合在一起就得在里面判断「这条错误属于谁」，那种代码下一个读的人一定会看错。
 */
@Composable
private fun DictationErrorLinger(state: VoiceInput.State) {
  LaunchedEffect(state) {
    if (state is VoiceInput.State.Failed) {
      delay(VOICE_ERROR_LINGER_MS)
      VoiceInput.clearError()
    }
  }
}

/**
 * 录音状态：电平条 + 秒数（正在听）、一句「正在准备模型 / 正在识别」。
 *
 * 电平是给用户的**反馈**，不是装饰：麦克风没被系统挡住、音量够大，这两件事
 * 只能靠它看出来 —— 否则用户对着一句话说完，等来的是「没听清」。
 */
@Composable
private fun DictationStatus(state: VoiceInput.State) {
  when (state) {
    is VoiceInput.State.Listening -> {
      LevelBar(state.level)
      Spacer(Modifier.width(6.dp))
      Text(
        text = formatElapsed(state.elapsedMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    VoiceInput.State.Preparing ->
      DictationHint("正在准备模型…")

    VoiceInput.State.Transcribing ->
      DictationHint("正在识别…")

    else -> Unit
  }
}

@Composable
private fun DictationHint(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** 电平条：0..1 映射到一小段宽度。最小值不为 0，否则安静时看着像坏了。 */
@Composable
private fun LevelBar(level: Float) {
  val width by animateFloatAsState(targetValue = level.coerceIn(LEVEL_MIN, 1f), label = "dictation-level")
  Box(
    modifier =
      Modifier.width(44.dp)
        .height(4.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth(width)
          .fillMaxHeight()
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary),
    )
  }
}

/**
 * 麦克风按钮。四种样子对应四个状态：待命、正在听（红）、准备模型 / 识别中（转圈）。
 *
 * **不禁用**：不可用的原因（没权限 / 没下模型）要让用户点得到才知道 ——
 * 一个灰掉且不解释的按钮，用户只会以为是坏的。点了之后由 [VoiceInput] 给出那句话。
 */
@Composable
private fun MicButton(state: VoiceInput.State, onClick: () -> Unit) {
  val listening = state is VoiceInput.State.Listening
  val busy = state is VoiceInput.State.Preparing || state is VoiceInput.State.Transcribing
  val background = if (listening) MaterialTheme.colorScheme.error else Color.Transparent
  val tint =
    when {
      listening -> MaterialTheme.colorScheme.onError
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  // 正在听的时候让图标呼吸：用户一眼能看出「它还在录」，而不是「我是不是没点上」。
  val alpha =
    if (listening) {
      val transition = rememberInfiniteTransition(label = "mic")
      val value by
        transition.animateFloat(
          initialValue = 0.55f,
          targetValue = 1f,
          animationSpec = infiniteRepeatable(animation = tween(durationMillis = 700), repeatMode = RepeatMode.Reverse),
          label = "mic-alpha",
        )
      value
    } else {
      1f
    }

  val label = if (listening) "停止录音" else "语音输入"
  // 视觉圆仍是 40dp，命中区扩到 48dp：小圆点好看，但按不准。
  Box(
    modifier =
      Modifier.size(48.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier.size(40.dp).clip(CircleShape).background(background),
      contentAlignment = Alignment.Center,
    ) {
      if (busy) {
        LoadingIndicator(modifier = Modifier.size(18.dp))
      } else {
        MicGlyph(tint = tint.copy(alpha = alpha))
      }
    }
  }
}

/** 录音时长。`0:07` 比 `7s` 更像录音机上的那个数字。 */
internal fun formatElapsed(elapsedMs: Long): String {
  val seconds = elapsedMs / 1000
  return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * 回复读完时自动朗读。
 *
 * 判据是「**这一条刚刚流式结束**」，不能是「最后一条是助手且不在流式中」——
 * 切会话、重启 App、重载运行时之后历史消息也长这样，那会让用户一进聊天页就被念一段旧回答。
 * 所以先记住「哪一条在流式」，等它停下来才读。
 */
@Composable
private fun AutoReadOnFinish(messages: List<ChatMessage>, sessionId: String?) {
  val context = LocalContext.current
  var pending by remember(sessionId) { mutableStateOf<String?>(null) }
  LaunchedEffect(messages) {
    val last = messages.lastOrNull() ?: return@LaunchedEffect
    if (last.role == ChatRole.Assistant && last.streaming) {
      pending = last.id
      return@LaunchedEffect
    }
    if (!isReadableAnswer(last, pending)) return@LaunchedEffect
    pending = null
    if (VoiceSettings.autoRead(context)) VoiceReader.speak(context, last.id, last.content)
  }
}

/** 这条助手消息是不是「刚刚读完、值得朗读」的那一条。 */
private fun isReadableAnswer(message: ChatMessage, pendingId: String?): Boolean =
  pendingId != null &&
    message.id == pendingId &&
    !message.streaming &&
    message.content.isNotBlank() &&
    message.error == null

/**
 * 空会话里可用的开场白（v3 的 `alternate_greetings`）。
 *
 * 只在一条消息都没有时读卡：选完开场白它就落成第一条助手消息，之后再点「换一条」会把已经
 * 发生的对话改掉。读过之后按「有没有消息」缓存，发第一句话时自然失效。
 */
@Composable
private fun rememberGreetings(state: ChatUiState): List<String> {
  val cardContext = LocalContext.current.applicationContext
  return remember(state.activeCharacterId, state.messages.isEmpty(), state.characterIds) {
    if (!state.messages.isEmpty()) {
      emptyList()
    } else {
      // 群聊优先用 group_only_greetings；单卡用 first_mes + alternate_greetings。
      // 展开 {{user}} / {{char}} 与宏：这里既当预览也当落库的正文，留着花括号会直接显示给用户。
      // 开场白里的宏用**空变量表**渲染：它没有会话上下文（会话还是空的），
      // 所以开场白里的 {{setvar}} 不会留下痕迹 —— 那种写操作要在正式的一轮里做才有效。
      val ids = state.characterIds.ifEmpty { listOfNotNull(state.activeCharacterId) }
      val cards = ids.distinct().mapNotNull { id -> CharacterStore.byId(cardContext, id) }
      val card = if (cards.size > 1) GroupCast.merge(cards) else cards.firstOrNull()
      card?.greetings.orEmpty().map { greeting ->
        CharacterStore.renderText(TavernMacros.render(greeting, TavernVars()), card!!)
      }
    }
  }
}

@Composable
private fun ChatBody(
  state: ChatUiState,
  listState: LazyListState,
  modifier: Modifier,
  onInputChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onNewSession: () -> Unit,
  onOpenSessions: () -> Unit,
  onModelSwitchClick: () -> Unit,
  greetings: List<String> = emptyList(),
  onSeedGreeting: (String) -> Unit = {},
  onLeaveRoleplay: () -> Unit = {},
  onImpersonate: () -> Unit = {},
  actions: BubbleActions = BubbleActions(),
  onOpenCharacterDetail: (String) -> Unit = {},
) {
  // 酒馆会话的标题就是角色名：顶栏要让人一眼看出「现在在跟谁说话」。
  val sessionTitle =
    state.activeCharacterName?.takeIf { it.isNotBlank() }
      ?: state.sessions.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() }
      ?: "zoneu"

  val voiceState by VoiceReader.state.collectAsStateWithLifecycle()
  val voiceError by VoiceReader.error.collectAsStateWithLifecycle()
  val dictation by VoiceInput.state.collectAsStateWithLifecycle()
  val onMicClick = rememberDictationLauncher()

  DictationResultSink { text -> onInputChange(RecognizedText.appendToInput(state.input, text)) }
  VoiceErrorLinger(voiceError)
  DictationErrorLinger(dictation)
  AutoReadOnFinish(messages = state.messages, sessionId = state.sessionId)

  // 跟随流式输出的底部。规则见 FollowOutput：用户一上手就交出滚动权，回到列表底部再交回来。
  var following by remember { mutableStateOf(true) }
  val lastUserMessageId = state.messages.lastOrNull { it.role == ChatRole.User }?.id
  FollowOutput(
    listState = listState,
    sessionId = state.sessionId,
    lastUserMessageId = lastUserMessageId,
    following = following,
    onFollowingChange = { following = it },
  )

  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    val modelSubtitle =
      listOf(state.providerName, state.model).filter { it.isNotBlank() }.joinToString(" · ")
    ChatTopBar(
      title = sessionTitle,
      subtitle = if (state.inRoleplay) "角色扮演 · $modelSubtitle" else modelSubtitle,
      onOpenSessions = onOpenSessions,
      onNewSession = onNewSession,
      onLeaveRoleplay = if (state.inRoleplay) onLeaveRoleplay else null,
    )
    PermissionPrompts()
    StatusBanners(state = state, voiceError = voiceError, dictation = dictation)

    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      MessageList(
        onOpenCharacterDetail = onOpenCharacterDetail,
        state = state,
        listState = listState,
        // 平板 / 横屏上正文铺满整屏一行会超过 100 个字符，读起来很累；限宽后居中。
        modifier = Modifier.widthIn(max = CHAT_CONTENT_MAX_WIDTH).fillMaxSize(),
        speakingId = voiceState?.messageId,
        greetings = greetings,
        onPickGreeting = onSeedGreeting,
        actions = actions,
        canContinue = state.tavernDirect && !state.isStreaming,
      )

      JumpToBottomButton(
        visible = !following,
        onClick = {
          following = true
          listState.requestScrollToBottom()
        },
      )
    }

    // 前情提要只出现在扮演会话里：日常对话没有「记忆」这回事，摆一行只会让人以为有。
    if (state.activeCharacterId != null) {
      SessionRecapBar(recap = state.sessionRecap, canWrite = state.messages.isNotEmpty(), onSummarize = actions.recapNow)
    }
    SessionVarsBar(state.sessionVars, onEdit = actions.editVar)

    ChatInputBar(
      value = state.input,
      isStreaming = state.isStreaming,
      canSend = state.canSend,
      modelPreset = LlmPreset.byId(state.providerId),
      providerName = state.providerName,
      dictation = dictation,
      onValueChange = onInputChange,
      onSend = onSend,
      onStop = onStop,
      onModelSwitchClick = onModelSwitchClick,
      onMicClick = onMicClick,
      onImpersonate = onImpersonate.takeIf { state.tavernDirect && !state.writingLine },
      writingLine = state.writingLine,
    )
  }
}

/** 用户上滑后自动跟随会暂停；给一个明确的「回到底部」出口（RikkaHub / Chatbox 同款）。 */
@Composable
private fun BoxScope.JumpToBottomButton(visible: Boolean, onClick: () -> Unit) {
  if (!visible) return
  SmallFloatingActionButton(
    onClick = onClick,
    modifier =
      Modifier.align(Alignment.BottomEnd)
        .padding(end = 16.dp, bottom = 16.dp)
        .semantics { contentDescription = "回到底部" },
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    ChevronDownGlyph(tint = MaterialTheme.colorScheme.onSurface)
  }
}

/**
 * 前情提要：自动总结写下的那段回忆（`SessionRecap`）。
 *
 * 没有提要及时摆一个「记一下这段」的入口，而不是整行藏起来 —— 自动那套要聊到 12 条才动，
 * 而「我想让模型记住这一场」往往就发生在刚聊完的时候。
 */
@Composable
private fun SessionRecapBar(recap: String?, canWrite: Boolean, onSummarize: () -> Unit) {
  if (recap == null && !canWrite) return
  var open by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = if (recap == null) "还没有前情提要" else "前情提要 · ${recap.lineSequence().first().take(28)}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    TextAction(text = if (recap == null) "记一下这段" else "看全文", onClick = { open = true })
  }
  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text("前情提要") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            recap ?: "还没有。聊过几句之后点下面的按钮，模型会把这一段压成几句话记住。",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "它每轮都会跟着「记忆」一起进提示词，所以别写太长。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = { TextButton(onClick = { open = false }) { Text("知道了") } },
      dismissButton = {
        TextButton(onClick = {
          open = false
          onSummarize()
        }) { Text("现在就总结") }
      },
    )
  }
}

/**
 * 会话变量：卡里 `{{setvar}}` 写下的东西（`好感 3`、`时间 夜` 这种）。
 *
 * 以前它们只在 prompt 里生效，界面上完全看不见 —— 卡作者设计了「好感度」，
 * 玩家却要自己去猜它现在是多少。点开「这一场的状态」可以改值：变量是卡自己的状态机，
 * 手改一次等于绕过卡的条件分支，所以对话框里写明了这一点 —— 但卡写错数字/自己写死循环时，
 * 这是唯一能救回这段会话的出口。
 *
 * 最多铺三条，其余折成「＋N」：变量名是卡作者起的，可能很长，横向铺开会把输入框挤走。
 */
@Composable
private fun SessionVarsBar(vars: Map<String, String>, onEdit: (String, String) -> Unit = { _, _ -> }) {
  if (vars.isEmpty()) return
  var expanded by remember { mutableStateOf(false) }
  var drafts by remember(vars) { mutableStateOf(vars) }
  val entries = vars.entries.toList()
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    entries.take(SHOWN_VARS).forEach { (key, value) ->
      Text(
        text = "$key $value",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // 点标签本身就打开对话框：只有 1 个变量时没有「＋N」可点，那样这段状态就永远改不了。
        modifier =
          Modifier.clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { expanded = true }
            .padding(horizontal = 8.dp, vertical = 3.dp),
      )
    }
    if (entries.size > SHOWN_VARS) {
      TextAction(text = "＋${entries.size - SHOWN_VARS}", onClick = { expanded = true })
    }
  }
  if (expanded) {
    AlertDialog(
      onDismissRequest = { expanded = false },
      title = { Text("这一场的状态") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          entries.forEach { (key, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                key,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(84.dp),
              )
              OutlinedTextField(
                value = drafts[key].orEmpty(),
                onValueChange = { next -> drafts = drafts + (key to next) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
              )
            }
          }
          Text(
            "手改会绕过卡自己的条件分支（比如「好感 ≥ 5 才留你」）。只在卡写错了、" +
              "或者你想跳到另一种局面时用。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          drafts.forEach { (key, value) -> if (value != vars[key]) onEdit(key, value) }
          expanded = false
        }) { Text("保存") }
      },
      dismissButton = { TextButton(onClick = { expanded = false }) { Text("取消") } },
    )
  }
}

/** 状态条上最多铺几个变量（其余折进「＋N」）。 */
private const val SHOWN_VARS = 3

/**
 * 模型面板的三个开关合成一个 holder。
 *
 * 不是为了省行数：它们是**一起变**的 —— 面板开着、点了某个模型（pending）、切换结束后要收起来。
 * 拆成三个 remember 时，任何一条路径漏掉 pending 的复位，面板就会「换完不关」。
 */
private class ModelSheet {
  var open by mutableStateOf(false)
  var pending by mutableStateOf(false)
  var choices by mutableStateOf<List<ModelChoice>>(emptyList())

  /** 切换结束后收起面板；切换出错时留在原地，让用户看到原因。 */
  fun settle(switching: Boolean, error: String?) {
    if (pending && !switching && error == null) open = false
    if (!switching) pending = false
  }

  fun reset() {
    open = false
    pending = false
  }
}

/** 消息列表：空态 + 每一条气泡。「重写 / 删掉这一轮」只挂在最后一条助手消息上。 */
@Composable
private fun MessageList(
  state: ChatUiState,
  listState: LazyListState,
  modifier: Modifier = Modifier.fillMaxSize(),
  speakingId: String?,
  greetings: List<String> = emptyList(),
  onPickGreeting: (String) -> Unit = {},
  actions: BubbleActions = BubbleActions(),
  canContinue: Boolean = false,
  onOpenCharacterDetail: (String) -> Unit = {},
) {
  // 挂在最后一条助手消息上而不是每一条：中间那几条重写没有意义，
  // 而删中间一轮会让后面的对话悬在半空。
  val tailId = state.messages.lastOrNull { it.role == ChatRole.Assistant }?.id
  LazyColumn(
    state = listState,
    modifier = modifier,
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    if (state.messages.isEmpty()) {
      item {
        // 读库还没完成时先给「正在读取」，别让用户对着空态问候以为历史丢了。
        if (!state.ready) {
          RestoringHint()
        } else {
          EmptyHint(
            characterName = state.activeCharacterName,
            greetings = greetings,
            onPickGreeting = onPickGreeting,
          )
        }
      }
    }
    items(items = state.messages, key = { it.id }) { message ->
      MessageBubble(
        onOpenCharacterDetail = onOpenCharacterDetail,
        message = message,
        speakingId = speakingId,
        tail = message.id == tailId && !state.isStreaming,
        actions = actions,
        canContinue = canContinue,
      )
    }
  }
}

/**
 * 流式输出的自动跟随。
 *
 * 旧实现每来一个 token 就 `scrollToItem(lastIndex)`，在长代码块上必现两个问题：
 * 1. 它把**最后一条的顶部**对到视口顶部 —— 一条比屏幕高的代码块会一直停在开头，
 *    新内容全在屏幕下面，看起来就是「焦点不跟着走」；
 * 2. 它不看用户意图，也不看手势 —— 用户往上拖的同时下一个 token 又把人拽回去，
 *    所以流式期间「完全划不动」。
 *
 * 现在的规则（与 RikkaHub / Chatbox 的做法一致）：
 * - `following` 是**用户意图**，不是几何：只在用户自己滚动结束时按落点重新判定；
 *   内容长高本身不改变它（否则第一次长高就会把自己关掉）。
 * - 用户滚动 / fling 期间绝不请求滚动（`isScrollInProgress`）。
 * - 跟随时把**最后一条的底部**贴到视口底部。`requestScrollToItem(lastIndex)` 只把它的
 *   顶部对到视口顶部，长消息会停在开头 —— 这正是上一版没修干净的地方。
 * - 用户拖回底部会恢复跟随；新用户消息 / 换会话直接跳到底部。
 *
 * 用 `requestScrollToItem` 而不是 `scrollToItem`：前者不挂起、不参与手势仲裁。
 */
@Composable
private fun FollowOutput(
  listState: LazyListState,
  sessionId: String?,
  lastUserMessageId: String?,
  following: Boolean,
  onFollowingChange: (Boolean) -> Unit,
) {
  val currentFollowing by rememberUpdatedState(following)
  val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
  var userScrolling by remember { mutableStateOf(false) }

  // 只在用户滚动「开始 → 结束」时采样位置。内容长高也会改 layoutInfo，但那时不该重判意图。
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
      if (scrolling) {
        userScrolling = true
      } else if (userScrolling) {
        userScrolling = false
        currentOnFollowingChange(listState.isAtBottom())
      }
    }
  }

  // 换会话 / 新的用户消息：明确意图，直接回到底部。助手消息 / 工具卡新增时不抢滚动，
  // 用户可能正在读历史。
  LaunchedEffect(sessionId) {
    currentOnFollowingChange(true)
    listState.requestScrollToBottom()
  }
  LaunchedEffect(lastUserMessageId) {
    if (lastUserMessageId != null) {
      currentOnFollowingChange(true)
      listState.requestScrollToBottom()
    }
  }

  // 内容长高：仍在跟随、且用户没在拖动时，把最后一条的底部贴到视口底部。
  LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo }.collect {
      if (listState.isScrollInProgress) {
        // 用户自己拖回底部时立刻恢复跟随；真正滚动等手势结束后的下一次长高。
        if (userScrolling && listState.isAtBottom()) currentOnFollowingChange(true)
        return@collect
      }
      if (!currentFollowing || listState.isAtBottom()) return@collect
      listState.requestScrollToBottom()
    }
  }
}

/**
 * 把最后一条的**底部**贴到视口底部。
 *
 * `requestScrollToItem(lastIndex)` 对齐的是 item 的顶部 —— 一条比视口高的消息会停在开头。
 * 这里按已测量的高度算偏移：`item.size - viewport + afterContentPadding`。
 * 最后一条还没被测量到时先跳到它，下一帧测量完会再贴一次底部。
 */
private fun LazyListState.requestScrollToBottom() {
  val info = layoutInfo
  if (info.totalItemsCount == 0) return
  val lastIndex = info.totalItemsCount - 1
  val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == lastIndex }
  if (last == null) {
    requestScrollToItem(lastIndex)
    return
  }
  val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
  val offset = (last.size - viewport + info.afterContentPadding).coerceAtLeast(0)
  requestScrollToItem(lastIndex, offset)
}

/** 最后一条是否已经完整落在视口内（含底部 contentPadding；留一点容差给小数像素）。 */
private fun LazyListState.isAtBottom(tolerancePx: Int = 8): Boolean {
  val info = layoutInfo
  if (info.totalItemsCount == 0) return true
  val last = info.visibleItemsInfo.lastOrNull() ?: return true
  return last.index >= info.totalItemsCount - 1 &&
    last.offset + last.size + info.afterContentPadding <= info.viewportEndOffset + tolerancePx
}

@Composable
private fun ChatTopBar(
  title: String,
  subtitle: String,
  onOpenSessions: () -> Unit,
  onNewSession: () -> Unit,
  onLeaveRoleplay: (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onOpenSessions,
      modifier = Modifier.semantics { contentDescription = "打开会话列表" },
    ) {
      MenuGlyph(tint = MaterialTheme.colorScheme.onSurface)
    }
    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (subtitle.isNotBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (onLeaveRoleplay != null) {
      Text(
        text = "退出扮演",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
          Modifier.defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = "退出角色扮演", onClick = onLeaveRoleplay)
            .padding(horizontal = 8.dp, vertical = 6.dp),
      )
    }
    IconButton(
      onClick = onNewSession,
      modifier = Modifier.semantics { contentDescription = "新建会话" },
    ) {
      PlusGlyph(tint = MaterialTheme.colorScheme.onSurface)
    }
  }
}

/**
 * 「无障碍没开」的提示：**状态与判定都封在这里**，ChatScreen 本体不必为此变长。
 *
 * 只在一个设备动作**真的因此失败**之后才出现（判据见 [AccessibilityStatus.shouldPrompt]）——
 * 纯聊天、只是问问题，不会打扰用户。
 */
@Composable
private fun AccessibilityPrompt() {
  val context = LocalContext.current
  val lastBridgeError by BridgeHealth.lastErrorCode.collectAsStateWithLifecycle()
  var enabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }
  LifecycleResumeEffect(Unit) {
    // 用户可能刚去系统设置里开过 —— 回到前台重查一次，开了就把提示收起来
    enabled = AccessibilityStatus.isEnabled(context)
    onPauseOrDispose { }
  }
  if (!AccessibilityStatus.shouldPrompt(lastBridgeError, enabled)) return
  // **只在用户想要的操作真的因此失败时弹**，并且说清是哪个操作 —— 「无障碍没开」这句话
  // 单独出现时，用户不知道跟自己刚才那句「往下滑一下」有什么关系。
  val action by BridgeHealth.lastErrorAction.collectAsStateWithLifecycle()
  AlertDialog(
    onDismissRequest = { BridgeHealth.dismiss() },
    title = { Text("「${AccessibilityStatus.actionLabel(action)}」需要开启无障碍") },
    text = {
      Text(
        "zoneu 只能通过系统的无障碍服务操作手机，刚才这个操作没有执行就是因为这个服务没开" +
          "（Android 不允许应用自己开启它）。\n\n" +
          "点「去开启」→ 在「已安装的服务」里找到 zoneu 打开；系统随后会弹「保持开启」" +
          "（3 秒倒计时），必须点它，不点会被系统关掉。",
      )
    },
    confirmButton = {
      TextButton(
        onClick = {
          BridgeHealth.dismiss()
          context.startActivity(AccessibilityStatus.settingsIntent())
        },
      ) {
        Text("去开启")
      }
    },
    dismissButton = { TextButton(onClick = { BridgeHealth.dismiss() }) { Text("稍后") } },
  )
}

/**
 * 「新建日程缺日历权限」：和 [AccessibilityPrompt] 同一套机制 —— **只在一次真实动作因此失败后**
 * 出现，不在启动时索要。授权完让 agent 再试一次即可（工具结果里已经这么说）。
 *
 * 文案必须承认代价：READ_CALENDAR 名义上是「找可写日历」，实际等于能读用户全部日程。
 */
@Composable
private fun CalendarPermissionPrompt() {
  val lastBridgeError by BridgeHealth.lastErrorCode.collectAsStateWithLifecycle()
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      BridgeHealth.dismiss()
    }
  if (lastBridgeError != DeviceAction.CODE_CALENDAR_PERMISSION) return
  AlertDialog(
    onDismissRequest = { BridgeHealth.dismiss() },
    title = { Text("「新建日程」需要日历权限") },
    text = {
      Text(
        "zoneu 会直接写进系统日历，不再让你在日历 App 里点保存；代价是需要日历的读写权限。\n\n" +
          "读权限只用来找「哪个日历能写」，但它同时意味着能读到你全部日程。" +
          "不想给的话点「稍后」，之后不会再问。",
      )
    },
    confirmButton = {
      TextButton(
        onClick = {
          launcher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
          )
        },
      ) {
        Text("去授权")
      }
    },
    dismissButton = { TextButton(onClick = { BridgeHealth.dismiss() }) { Text("稍后") } },
  )
}

/** 三条权限提示收在一处：ChatBody 已经贴着 detekt 的 80 行上限，别再往它里面加调用。 */
@Composable
private fun PermissionPrompts() {
  AccessibilityPrompt()
  CalendarPermissionPrompt()
  ExactAlarmPrompt()
}

/**
 * 「有定时任务，但没开「闹钟和提醒」」的主动提醒。
 *
 * 与 [AccessibilityPrompt] / [CalendarPermissionPrompt] 不同：这两条只在**真实失败**后出现，
 * 而缺少精确闹钟权限**不会失败** —— 任务照跑，只是可能晚 15 分钟以上（窗口闹钟不豁免 Doze）。
 * 等失败永远等不到，所以这里改成每次回到前台主动查一次：有启用的任务 + 权限没开就摆一条。
 *
 * 权限授予后 `ScheduledTaskBootReceiver` 会整体重排，把窗口闹钟升级成精确闹钟 ——
 * 用户点完「去开启」回来，下一次触发就已经是准点的。
 */
@Composable
private fun ExactAlarmPrompt() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var needed by remember { mutableStateOf(false) }
  LifecycleResumeEffect(Unit) {
    scope.launch { needed = needsExactAlarmReminder(context) }
    onPauseOrDispose { }
  }
  if (!needed) return
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "有定时任务，但「闹钟和提醒」权限未开启：任务可能晚 15 分钟以上。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.weight(1f),
    )
    TextButton(
      onClick = {
        runCatching { context.startActivity(ScheduledTaskScheduler.exactAlarmSettingsIntent(context)) }
      },
    ) {
      Text("去开启")
    }
  }
}

/** 页头下面那一摞黄条/红条：缺 key、存不了盘、朗读失败、语音输入失败。 */
@Composable
private fun StatusBanners(state: ChatUiState, voiceError: String?, dictation: VoiceInput.State) {
  if (!state.configured) {
    // 没填 key 是「还没配好」而不是出错：用警告色，别让首屏第一眼就是红的。
    BannerText(
      "未配置 API key：点左上角菜单 → 设置，填自己的 key（存进 Keystore）。",
      tone = AppBanner.Tone.Warning,
    )
  }
  state.storageError?.let { reason -> BannerText("本地存储不可用，消息无法保存：$reason") }
  voiceError?.let { reason -> BannerText("朗读失败：$reason") }
  (dictation as? VoiceInput.State.Failed)?.let { reason -> BannerText("语音输入：${reason.reason}") }
}

@Composable
private fun BannerText(text: String, tone: AppBanner.Tone = AppBanner.Tone.Error) {
  AppBanner(
    text = text,
    tone = tone,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
  )
}

/** 首屏读库：一句话 + 系统进度，比一个静态空态更诚实。 */
@Composable
private fun RestoringHint() {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    LoadingIndicator()
    Text(
      text = "正在读取会话…",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
}

/** 点一下：先做事，再收抽屉。抽出来是因为六个入口都要这一下，写在行内会把版面撑散。 */
private fun closeThen(scope: CoroutineScope, drawer: DrawerState, action: () -> Unit): () -> Unit =
  {
    action()
    scope.launch { drawer.close() }
  }

/** 带参数的版本（会话/角色 id），规则同上。 */
private fun closeThen(
  scope: CoroutineScope,
  drawer: DrawerState,
  action: (String) -> Unit,
): (String) -> Unit = { id ->
  action(id)
  scope.launch { drawer.close() }
}

/** 抽屉标题行。右侧的「+」随分区变义：日常里是新会话，酒馆里是去挑一张卡。 */
@Composable
private fun DrawerHeader(enabled: Boolean, showPlus: Boolean, onPlus: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "会话",
      style = MaterialTheme.typography.titleMediumEmphasized,
      modifier = Modifier.weight(1f).padding(start = 12.dp),
    )
    if (showPlus) {
      IconButton(
        onClick = onPlus,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "新建会话" },
      ) {
        PlusGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/** 侧栏的两个分区：日常（干活）与酒馆（角色扮演）。 */
private enum class DrawerTab(val label: String) {
  Normal("日常"),
  Roleplay("酒馆"),
}

@Composable
private fun DrawerTabs(tab: DrawerTab, onSelect: (DrawerTab) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    DrawerTab.entries.forEach { entry ->
      val selected = entry == tab
      Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f).selectable(selected = selected, role = Role.Tab) { onSelect(entry) },
      ) {
        Text(
          text = entry.label,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.labelLarge,
          color =
            if (selected) {
              MaterialTheme.colorScheme.onSecondaryContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.padding(vertical = 8.dp),
        )
      }
    }
  }
}

/**
 * 酒馆分区：一行一张角色卡，行下面挂它名下的会话。
 *
 * 与日常分区的差别不只是分组：这里的每一行都对应一套系统提示（卡的人设 + 世界设定），
 * 点进去会换掉「谁在说话」。所以每张卡都显示，没有会话的卡也能点 —— 那正是开第一段对话的入口。
 */
@Composable
private fun RoleplayList(
  modifier: Modifier = Modifier,
  groups: List<RoleplayGroup>,
  currentId: String?,
  activeCharacterId: String?,
  switchingRole: Boolean,
  enabled: Boolean,
  onOpenRoleplay: (String) -> Unit,
  onNewRoleplaySession: (String) -> Unit,
  onNewGroup: () -> Unit = {},
  onOpenSession: (String) -> Unit,
  onRequestDelete: (ChatSession) -> Unit,
  onOpenCharacters: () -> Unit,
  onOpenCharacterDetail: (String) -> Unit = {},
) {
  if (groups.isEmpty()) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text("酒馆里还没有角色", style = MaterialTheme.typography.bodyMedium)
      Text(
        text = "导入一张角色卡（PNG 或 JSON），它的开场白、人设和世界设定只在酒馆会话里生效，不影响日常。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
      )
      TextButton(onClick = onOpenCharacters, modifier = Modifier.padding(top = 4.dp)) { Text("去导入角色卡") }
    }
    return
  }
  LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    // 群聊入口放在列表最上面：它是「开一场新的」，不是某张卡名下的动作 ——
    // 塞进某张卡的行里，用户会以为是给那张卡加台词。
    item(key = "new-group") {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onNewGroup() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("＋ 群聊", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
          text = "挑两张卡，让它们同场",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (switchingRole) {
      item(key = "switching") {
        Text(
          text = "正在切换角色…",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
        )
      }
    }
    groups.forEach { group ->
      item(key = "card-${group.characterId}") {
        RoleplayHeader(
          group = group,
          active = group.characterId == activeCharacterId,
          enabled = enabled,
          onOpen = { onOpenRoleplay(group.characterId) },
          onNew = { onNewRoleplaySession(group.characterId) },
          onDetail = { onOpenCharacterDetail(group.characterId) },
        )
      }
      items(items = orderedSessions(group.sessions), key = { it.id }) { session ->
        SessionRow(
          session = session,
          selected = session.id == currentId,
          enabled = enabled,
          onOpen = { onOpenSession(session.id) },
          onDelete = { onRequestDelete(session) },
        )
      }
    }
  }
}

@Composable
private fun RoleplayHeader(
  group: RoleplayGroup,
  active: Boolean,
  enabled: Boolean,
  onOpen: () -> Unit,
  onNew: () -> Unit,
  onDetail: () -> Unit = {},
) {
  val subtitle =
    when {
      group.isEmpty -> "还没有对话"
      else -> "${group.sessions.size} 段对话 · ${group.latest?.title?.takeIf { it.isNotBlank() } ?: "新会话"}"
    }
  Surface(
    color = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(enabled = enabled, onClick = onOpen),
  ) {
    Row(
      modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          // 收藏的卡带一个星号：侧栏一排名字时，这是唯一能一眼看出「哪张是我常用的」的记号。
          text = if (group.pinned) "★ ${group.name}" else group.name,
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // 去详情页：人设、开场、世界设定都在那儿，和「开新会话」是两个动作。
      IconButton(
        onClick = onDetail,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "查看角色详情" },
      ) {
        ChevronRight()
      }
      IconButton(
        onClick = onNew,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "用这张卡开新会话" },
      ) {
        PlusGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun SessionDrawer(
  sessions: List<ChatSession>,
  roleplayGroups: List<RoleplayGroup>,
  currentId: String?,
  activeCharacterId: String?,
  switchingRole: Boolean,
  enabled: Boolean,
  configSummary: String,
  onNewSession: () -> Unit,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onNewRoleplaySession: (String) -> Unit,
  onNewGroup: () -> Unit = {},
  onOpenRoleplay: (String) -> Unit,
  onOpenCharacters: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenCharacterDetail: (String) -> Unit = {},
  onOpenModelSwitch: () -> Unit = {},
) {
  var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
  // 在酒馆会话里打开侧栏时直接落在酒馆分区：不然用户要自己找自己在哪。
  var tab by remember(activeCharacterId) {
    mutableStateOf(if (activeCharacterId != null) DrawerTab.Roleplay else DrawerTab.Normal)
  }
  val normalSessions = remember(sessions) { sessions.filter { !it.isRoleplay } }
  // 只在会话列表变化时重算：聊天每来一个 token 都重组这一层，filter + 分组 + 读时钟没必要跟着跑。
  val grouped = remember(normalSessions) { groupSessions(normalSessions, System.currentTimeMillis()) }

  ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp)) {
      // 酒馆分区不设「新建」：会话必须挂在一张卡上，新建就意味着先选卡 ——
      // 那件事由各角色行右侧的「+」完成，头部再放一个只会让人以为能凭空开会话。
      DrawerHeader(
        enabled = enabled,
        showPlus = tab == DrawerTab.Normal,
        onPlus = onNewSession,
      )
      DrawerTabs(tab = tab, onSelect = { tab = it })

      when (tab) {
        DrawerTab.Normal ->
          SessionList(
            modifier = Modifier.weight(1f),
            grouped = grouped,
            currentId = currentId,
            enabled = enabled,
            onOpenSession = onOpenSession,
            onRequestDelete = { pendingDelete = it },
          )

        DrawerTab.Roleplay ->
          RoleplayList(
            modifier = Modifier.weight(1f),
            groups = roleplayGroups,
            currentId = currentId,
            activeCharacterId = activeCharacterId,
            switchingRole = switchingRole,
            enabled = enabled,
            onOpenRoleplay = onOpenRoleplay,
            onNewRoleplaySession = onNewRoleplaySession,
            onNewGroup = onNewGroup,
            onOpenSession = onOpenSession,
            onRequestDelete = { pendingDelete = it },
            onOpenCharacters = onOpenCharacters,
            onOpenCharacterDetail = onOpenCharacterDetail,
          )
      }

      DrawerFooter(
        roleplayEntries = tab == DrawerTab.Roleplay,
        configSummary = configSummary,
        onOpenCharacters = onOpenCharacters,
        onOpenSettings = onOpenSettings,
        onOpenModelSwitch = onOpenModelSwitch,
        currentId = currentId,
      )
    }
  }

  val target = pendingDelete
  if (target != null) {
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("删除会话？") },
      text = {
        Text(
          "「${target.title ?: "新会话"}」的消息与工具调用会一起删除，不能撤销。本地库卸载后无法恢复，删之前请确认不再需要这段记录。",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteSession(target.id)
            pendingDelete = null
          },
        ) {
          Text("删除")
        }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
    )
  }
}

/** 抽屉底部：酒馆里多两个入口（角色卡 / 本段会话的模型），再加上设置与当前模型摘要。 */
@Composable
private fun DrawerFooter(
  roleplayEntries: Boolean,
  configSummary: String,
  onOpenCharacters: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenModelSwitch: () -> Unit,
  currentId: String?,
) {
  val worldNavigation = LocalWorldNavigation.current
  HorizontalDivider(Modifier.padding(vertical = 8.dp))
  if (roleplayEntries) {
    DrawerNavRow(label = "管理角色卡", onClick = onOpenCharacters) {
      PersonGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DrawerNavRow(label = "管理世界书", onClick = worldNavigation.openLibrary) {
      PersonGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (currentId != null) {
      DrawerNavRow(label = "本段会话的世界配置", onClick = { worldNavigation.openBindings("session", currentId) }) {
        PersonGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    // 酒馆里的模型入口：每段会话会记住自己用哪个模型（回来时自动装回去）。
    DrawerNavRow(label = "本段会话的模型", onClick = onOpenModelSwitch) {
      PersonGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  DrawerNavRow(label = "设置", onClick = onOpenSettings) {
    CogGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (configSummary.isNotBlank()) {
    Text(
      text = configSummary,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
    )
  }
}

@Composable
private fun DrawerNavRow(
  label: String,
  onClick: () -> Unit,
  leading: @Composable () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leading()
    Spacer(Modifier.width(10.dp))
    Text(text = label, style = MaterialTheme.typography.bodyMedium)
  }
}

/** 按「今天 / 更早」分组的会话列表。抽出来只是为了 SessionDrawer 别压着 detekt 的长方法阈值。 */
@Composable
private fun SessionList(
  modifier: Modifier = Modifier,
  grouped: List<Pair<String, List<ChatSession>>>,
  currentId: String?,
  enabled: Boolean,
  onOpenSession: (String) -> Unit,
  onRequestDelete: (ChatSession) -> Unit,
) {
  LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    if (grouped.isEmpty()) {
      item {
        Text(
          text = "还没有会话记录",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 12.dp, top = 12.dp),
        )
      }
    }
    grouped.forEach { (label, groupSessions) ->
      item(key = "group-$label") {
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
        )
      }
      items(items = groupSessions, key = { it.id }) { session ->
        SessionRow(
          session = session,
          selected = session.id == currentId,
          enabled = enabled,
          onOpen = { onOpenSession(session.id) },
          onDelete = { onRequestDelete(session) },
        )
      }
    }
  }
}

/**
 * 侧栏顺序：主线在前，它的支线紧跟在这条主线后面。
 *
 * 不按时间混排：支线往往比主线新，混排之后会在列表里把主线顶下去，看起来像两条不相干的历史。
 * 主线被删掉的支线仍然排在最后 —— 否则那些历史在界面上等于消失了。
 */
private fun orderedSessions(sessions: List<ChatSession>): List<ChatSession> {
  val branches = sessions.filter { it.parentId != null }.groupBy { it.parentId }
  val placed = mutableSetOf<String>()
  val out = mutableListOf<ChatSession>()
  sessions.filter { it.parentId == null }.forEach { root ->
    out += root
    branches[root.id].orEmpty().forEach { branch ->
      if (placed.add(branch.id)) out += branch
    }
  }
  sessions.filter { it.parentId != null && it.id !in placed }.forEach { out += it }
  return out
}

@Composable
private fun SessionRow(
  session: ChatSession,
  selected: Boolean,
  enabled: Boolean,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  val isBranch = session.parentId != null
  Surface(
    color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onOpen),
  ) {
    Row(
      // 支线缩进一格并加个转折符：侧栏要能一眼看出「这几条是从上面那段分出来的」。
      modifier = Modifier.padding(start = if (isBranch) 32.dp else 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text =
          if (isBranch) {
            "↳ ${session.title?.takeIf { it.isNotBlank() } ?: "新会话"}"
          } else {
            session.title?.takeIf { it.isNotBlank() } ?: "新会话"
          },
        style = if (isBranch) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Box(
        modifier =
          Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "删除会话", onClick = onDelete)
            .semantics { contentDescription = "删除会话" },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "删",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyHint(
  characterName: String? = null,
  greetings: List<String> = emptyList(),
  onPickGreeting: (String) -> Unit = {},
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Text(text = "M", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onPrimary)
    }
    Text(
      text = if (characterName != null) characterName else "你好，我是 zoneu",
      style = MaterialTheme.typography.titleMediumEmphasized,
      modifier = Modifier.padding(top = 18.dp),
    )
    Text(
      // 酒馆里不该出现「说出要手机做什么」：那是干活用的开场，跟扮演是两件事。
      text =
        if (characterName != null && greetings.isEmpty()) {
          "先说一句就行 —— 开场由你这句定调。"
        } else if (characterName != null) {
          "先说一句就行 —— 开场由这张卡自己写。"
        } else {
          "说出要手机做什么，例如「打开设置看看电池电量」。\n任务会实时操作手机界面，工具调用会逐张出现。"
        },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
    if (greetings.isNotEmpty()) {
      Text(
        text = if (greetings.size == 1) "或者从她写的开场白开始" else "选一条开场白开始（共 ${greetings.size} 条）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
      )
      greetings.forEachIndexed { index, text ->
        Text(
          text = text.trim().lines().take(3).joinToString("\n"),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 4.dp)
              .clip(RoundedCornerShape(14.dp))
              .clickable { onPickGreeting(text) }
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .padding(14.dp),
        )
      }
    }
  }
}

@Composable
private fun MessageBubble(
  message: ChatMessage,
  speakingId: String? = null,
  tail: Boolean = false,
  actions: BubbleActions = BubbleActions(),
  canContinue: Boolean = false,
  onOpenCharacterDetail: (String) -> Unit = {},
) {
  if (message.role == ChatRole.User) {
    UserBubble(message.content)
  } else {
    AssistantMessage(
      onOpenCharacterDetail = onOpenCharacterDetail,
      message = message,
      speaking = message.id == speakingId,
      tail = tail,
      actions = actions,
      canContinue = canContinue,
    )
  }
}

/**
 * 候选回答的「‹ 2/3 ›」。
 *
 * 只挂在最后一条助手消息上：上面那些轮次如果也允许换，换完之后**后面所有轮次**都得重建 ——
 * 而它们当时是照着另一份回答说的。等「换到更早一轮就截断重答」做出来再放开。
 */
@Composable
private fun VariantStepper(index: Int, total: Int, onStep: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    TextAction(text = "‹", onClick = { onStep(-1) })
    Text(
      text = "${index + 1}/$total",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextAction(text = "›", onClick = { onStep(1) })
  }
}

/** 用户消息：右侧浅色气泡，无头像，对齐 ChatGPT / DeepSeek。 */
@Composable
private fun UserBubble(content: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = RoundedCornerShape(22.dp),
      modifier =
        Modifier.widthIn(max = USER_BUBBLE_MAX_WIDTH)
          // 读屏要能听出这句是「你说的」，不然整段对话只剩一个声音。
          .semantics(mergeDescendants = true) { contentDescription = "你说：$content" },
    ) {
      // 和助手正文一样可以长按选中：用户消息也要能复制出去。
      SelectionContainer {
        Text(
          text = content,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
    }
  }
}

/** 「删这轮」「从此处重来」都是永久删除：先确认，再做。 */
private enum class ConfirmingTurn(val title: String, val body: String) {
  Delete("删除这一轮？", "这一轮回复和它的工具调用会一起删除，不能撤销。"),
  RetryFrom("从此处重来？", "这句话之后的所有消息都会被删除，然后重新回答，不能撤销。"),
}

/** 助手消息：全宽正文（无气泡），上面依次挂思考折叠与工具卡，末尾一行操作。 */
@Composable
private fun AssistantMessage(
  message: ChatMessage,
  speaking: Boolean = false,
  tail: Boolean = false,
  actions: BubbleActions = BubbleActions(),
  canContinue: Boolean = false,
  onOpenCharacterDetail: (String) -> Unit = {},
) {
  var confirming by remember(message.id) { mutableStateOf<ConfirmingTurn?>(null) }
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // 过程（工具 + 思考）收进一个可折叠块；正文永远在块外面。
    ProcessBlock(message)
    CharacterCardResultActions(message.toolCalls, onOpenCharacterDetail)
    WorldBookResultActions(message.toolCalls)
    AssistantBody(message)
    if ((message.content.isNotBlank() || message.error != null) && !message.streaming) {
      AssistantActions(
        message = message,
        speaking = speaking,
        tail = tail,
        actions = actions,
        canContinue = canContinue,
        onConfirm = { confirming = it },
      )
    }
  }
  confirming?.let { pending ->
    TurnConfirmDialog(
      pending = pending,
      messageId = message.id,
      actions = actions,
      onDismiss = { confirming = null },
    )
  }
}

/** 助手正文与错误：正文优先，已经写出来的字不因为最后出错而消失。 */
@Composable
private fun AssistantBody(message: ChatMessage) {
  val error = message.error
  when {
    message.content.isNotEmpty() ->
      MarkdownText(
        markdown = message.content + if (message.streaming) STREAMING_CURSOR else "",
        // 正文是阅读主体，和用户气泡同字号（16sp）；层级靠标题和行距区分，而不是让长回答更小。
        style = MaterialTheme.typography.bodyLarge,
      )

    error != null ->
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = error,
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
      }

    message.reasoning.isNullOrBlank() && message.toolCalls.isEmpty() ->
      if (message.thinking) ThinkingIndicator() else Text(text = "…", style = MaterialTheme.typography.bodyMedium)
  }

  if (error != null && message.content.isNotEmpty()) {
    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
  }
}

/** 正文下面那一行操作：FlowRow 负责窄屏换行，token 计数固定在右侧不参与换行。 */
@Composable
private fun AssistantActions(
  message: ChatMessage,
  speaking: Boolean,
  tail: Boolean,
  actions: BubbleActions,
  canContinue: Boolean,
  onConfirm: (ConfirmingTurn) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    FlowRow(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (message.content.isNotBlank()) {
        SpeakAction(messageId = message.id, markdown = message.content, speaking = speaking)
        CopyAction(text = message.content)
      } else {
        // 只有错误没有正文：唯一有意义的下一步是重来，而不是「重写」一段不存在的话。
        TextAction(text = "重试", onClick = { actions.retryFrom(message.id) })
      }
      if (tail) {
        if (message.variantTotal > 1) {
          VariantStepper(index = message.variantIndex, total = message.variantTotal, onStep = actions.swipe)
        }
        // 续写只在直连通道下出现：dsh 那边做不到「接着写」，给个按不动的按钮比不给更糟
        if (canContinue) {
          TextAction(text = "续写", onClick = actions.continueReply)
        }
        if (message.content.isNotBlank()) {
          TextAction(text = "重写", onClick = actions.regenerate)
          // 支线：复制到此为止的历史到新会话。放在「重写」旁边是因为两者回答的是同一个问题
          // （「这句不满意」），只是重写保留候选、支线保留整条走法。
          TextAction(text = "开新支线", onClick = { actions.fork(message.id) })
        }
        TextAction(text = "删这轮", onClick = { onConfirm(ConfirmingTurn.Delete) })
      } else {
        // 中间那几条不给「重写」：重写的是这一轮，而这一轮后面还接着别人说的话，
        // 删了它会让后面悬空。给的是「从此处重来」—— 连同后面的一起丢掉，回到这句话重新回答。
        TextAction(text = "从此处重来", onClick = { onConfirm(ConfirmingTurn.RetryFrom) })
      }
    }
    // token 计数贴在末尾：它是「这一条花了多少上下文」，不是这句话的一部分，
    // 所以用最轻的字重与最旁边的位置，不抢正文的注意力。maxLines=1 保证它不会被压成竖排。
    if (message.content.isNotBlank()) {
      Text(
        text = "≈${Tokens.format(Tokens.estimate(message.content))} tokens",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
  }
}

/** 删除类操作的二次确认：两处入口共用一套文案与危险色。 */
@Composable
private fun TurnConfirmDialog(
  pending: ConfirmingTurn,
  messageId: String,
  actions: BubbleActions,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(pending.title) },
    text = { Text(pending.body) },
    confirmButton = {
      TextButton(
        onClick = {
          onDismiss()
          when (pending) {
            ConfirmingTurn.Delete -> actions.deleteTurn()
            ConfirmingTurn.RetryFrom -> actions.retryFrom(messageId)
          }
        },
      ) {
        Text("删除", color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/**
 * 朗读助手正文。正在读这一条时按钮变成「停止」——
 * 否则用户看到的是「点了没反应」（其实是在读别的段落，或者已经读完了）。
 */
@Composable
private fun SpeakAction(messageId: String, markdown: String, speaking: Boolean) {
  val context = LocalContext.current
  val tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.defaultMinSize(minHeight = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClickLabel = if (speaking) "停止朗读" else "朗读") {
          VoiceReader.toggle(context, messageId, markdown)
        }
        .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    SpeakerGlyph(tint = tint)
    Spacer(Modifier.width(6.dp))
    Text(
      text = if (speaking) "停止" else "朗读",
      style = MaterialTheme.typography.labelSmall,
      color = tint,
    )
  }
}

/**
 * 纯文字操作（重写 / 删这轮）。
 *
 * 不给图标：这两个动作不常用，且「删」带破坏性 —— 图标在这么小的一行里认不出来，
 * 误触比多点一步更糟。
 */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
  // 文字小、命中区不能小：48dp 高是平台最小值，直接写进这一层，调用点就不用各记一遍。
  Box(
    modifier =
      Modifier.defaultMinSize(minHeight = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClickLabel = text, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 复制助手正文（用户点按触发；不打印、不落日志）。 */
@Composable
private fun CopyAction(text: String) {
  val context = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier.defaultMinSize(minHeight = 48.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(role = Role.Button, onClickLabel = "复制") {
          copyTextToClipboard(context, text)
          copied = true
        }
        .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    if (copied) {
      CheckGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      CopyGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(6.dp))
    Text(
      text = if (copied) "已复制" else "复制",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 收起时的摘要：第一行非空文本，去掉 Markdown 粗体标记（对齐 dsh ReasoningRow 的 firstLine）。 */
internal fun reasoningSummary(text: String): String =
  text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty().replace("**", "")

@Composable
private fun ThinkingIndicator() {
  // 用走动的省略号表示在跑，而不是把文字淡到 0.35 —— 那会让灰字掉到 1.5:1，
  // 看起来像坏了。文字保持全对比度，动画只加在标点上。
  val transition = rememberInfiniteTransition(label = "thinking")
  val phase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 3f,
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Restart),
      label = "thinking-phase",
    )
  Text(
    text = "思考中" + ".".repeat(phase.toInt().coerceIn(0, 3)),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * 底部输入：浮动圆角卡（ChatGPT / DeepSeek 样式），内嵌无边框输入框与圆形发送键；
 * 流式进行中圆键切换为停止。卡片下方一行小字显示当前模型配置。
 */
@Composable
private fun ChatInputBar(
  value: String,
  isStreaming: Boolean,
  canSend: Boolean,
  modelPreset: LlmPreset?,
  providerName: String,
  dictation: VoiceInput.State,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onModelSwitchClick: () -> Unit,
  onMicClick: () -> Unit,
  onImpersonate: (() -> Unit)? = null,
  writingLine: Boolean = false,
) {
  val textStyle = MaterialTheme.typography.bodyLarge
  Box(
    modifier = Modifier.fillMaxWidth().imePadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier = Modifier.widthIn(max = CHAT_CONTENT_MAX_WIDTH).fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(26.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shadowElevation = 4.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
      ) {
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
          textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          maxLines = 5,
          // 输入框最多 5 行，回车就该换行；发送只走右侧按钮，流式中也不会出现「按了没反应」。
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
          decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
              if (value.isEmpty()) {
                Text(
                  text = "和 zoneu 说点什么…",
                  style = textStyle,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              inner()
            }
          },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          if (modelPreset != null) {
            Box(
              modifier =
                Modifier.size(48.dp)
                  .clip(CircleShape)
                  .clickable(enabled = !isStreaming, onClick = onModelSwitchClick)
                  .semantics { contentDescription = "切换模型" },
              contentAlignment = Alignment.Center,
            ) {
              ProviderModelIcon(
                preset = modelPreset,
                name = providerName,
                size = 32,
                shape = RoundedCornerShape(10.dp),
              )
            }
          }
          if (onImpersonate != null || writingLine) {
            Spacer(Modifier.width(8.dp))
            ImpersonateAction(onClick = onImpersonate, writing = writingLine, busy = isStreaming)
          }
          Spacer(Modifier.weight(1f))
          DictationStatus(dictation)
          Spacer(Modifier.width(6.dp))
          MicButton(state = dictation, onClick = onMicClick)
          Spacer(Modifier.width(4.dp))
          SendStopButton(
            isStreaming = isStreaming,
            canSend = canSend,
            onSend = onSend,
            onStop = onStop,
          )
        }
      }
    }
  }
}

/**
 * 群聊选择器的开合与勾选状态。
 *
 * 合成一个 holder 的理由与 [ModelSheet] 相同：`open` 与 `picked` 必须一起复位 ——
 * 分成两个 remember 时，任何一条「只关了对话框」的路径都会把上次勾的人留在下一次。
 *
 * 不做「先建群再拉人」：群聊的上下文从第一条消息起就是多人的，事后拉人会让前面那些
 * 单人戏突然多出一个「一直在场却没说话」的角色，模型会去解释他为什么不说话。
 *
 * 上限 [GroupCast.MAX] 是硬的：勾满之后其余项置灰，而不是勾上再提示超限。
 */
private class GroupPickerHost {
  var open by mutableStateOf(false)
  var picked by mutableStateOf<List<String>>(emptyList())

  private fun close() {
    open = false
    picked = emptyList()
  }

  @Composable
  fun Render(groups: List<RoleplayGroup>, onStartGroup: (List<String>) -> Unit) {
    if (!open) return
    val choices = groups.filter { !it.orphan }
    AlertDialog(
      onDismissRequest = { close() },
      title = { Text("群聊：挑几个角色") },
      text = {
        Column {
          Text(
            text = "选 2–${GroupCast.MAX} 张。每轮只会有一个角色说话，用「名字：」开头。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(10.dp))
          LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(items = choices, key = { it.characterId }) { group ->
              val checked = group.characterId in picked
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = checked || picked.size < GroupCast.MAX) {
                      picked = if (checked) picked - group.characterId else picked + group.characterId
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = checked,
                  onCheckedChange = { picked = if (checked) picked - group.characterId else picked + group.characterId },
                  enabled = checked || picked.size < GroupCast.MAX,
                )
                Text(group.name, style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val ids = picked
            close()
            if (ids.size >= 2) onStartGroup(ids)
          },
          enabled = picked.size >= 2,
        ) { Text("开始（${picked.size}）") }
      },
      dismissButton = { TextButton(onClick = { close() }) { Text("取消") } },
    )
  }
}

/**
 * 「替我写」：只在能走直连通道的会话里出现。
 *
 * 正在写的时候显示「正在替你写…」而不是置灰按钮：按钮一灰，用户不知道是在等还是在坏。
 * 这一块单独成函数是因为输入卡那一段已经贴着 detekt 的 80 行上限。
 */
@Composable
private fun ImpersonateAction(onClick: (() -> Unit)?, writing: Boolean, busy: Boolean) {
  Text(
    text = if (writing) "正在替你写…" else "替我写",
    style = MaterialTheme.typography.labelSmall,
    color =
      if (writing) {
        MaterialTheme.colorScheme.onSurfaceVariant
      } else {
        MaterialTheme.colorScheme.primary
      },
    modifier =
      Modifier.clip(RoundedCornerShape(10.dp))
        .clickable(enabled = !writing && !busy && onClick != null) { onClick?.invoke() }
        .padding(horizontal = 10.dp, vertical = 6.dp),
  )
}

@Composable
private fun SendStopButton(
  isStreaming: Boolean,
  canSend: Boolean,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val enabled = isStreaming || canSend
  val background = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
  val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

  val label = if (isStreaming) "停止生成" else "发送"
  // 48dp 命中区包 40dp 视觉圆：这是聊天页最常按的键，不能比平台最小触控目标还小。
  Box(
    modifier =
      modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) {
          if (isStreaming) onStop() else onSend()
        }
        .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier.size(40.dp).clip(CircleShape).background(background),
      contentAlignment = Alignment.Center,
    ) {
      if (isStreaming) {
        StopGlyph(tint = tint)
      } else {
        ArrowUpGlyph(tint = tint)
      }
    }
  }
}

/** 会话抽屉按日期分组：今天 / 昨天 / 7 天内 / 更早（对齐 DeepSeek 的分组标题）。 */
internal fun sessionGroupLabel(updatedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
  val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
  val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
  val days = ChronoUnit.DAYS.between(date, today)
  return when {
    days <= 0L -> "今天"
    days == 1L -> "昨天"
    days < 7L -> "7 天内"
    else -> "更早"
  }
}

private val GROUP_ORDER = listOf("今天", "昨天", "7 天内", "更早")

internal fun groupSessions(sessions: List<ChatSession>, now: Long): List<Pair<String, List<ChatSession>>> =
  GROUP_ORDER.mapNotNull { label ->
    sessions.filter { sessionGroupLabel(it.updatedAt, now) == label }.takeIf { it.isNotEmpty() }?.let { label to it }
  }

internal fun copyTextToClipboard(context: Context, text: String) {
  val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText("zoneu", text))
}

/** 一行摘要：把参数里的换行压平，太长就留给展开态。 */
internal fun compactArguments(arguments: String): String {
  val flat = arguments.replace(WHITESPACE, " ").trim()
  return when {
    flat.isEmpty() -> "（无参数）"
    flat.length <= ARGUMENT_SUMMARY_CHARS -> flat
    else -> flat.take(ARGUMENT_SUMMARY_CHARS) + "…"
  }
}

/** 参数尽量按 JSON 展开；不是合法 JSON 就原样显示。 */
internal fun prettyJson(raw: String): String =
  runCatching { PRETTY_JSON.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }.getOrDefault(raw)

private val PRETTY_JSON = Json { prettyPrint = true }
private val WHITESPACE = Regex("\\s+")
private val USER_BUBBLE_MAX_WIDTH = 300.dp

/** 大屏上的阅读宽度上限：一行 40–50 个汉字，再宽就要靠眼球来回扫。 */
private val CHAT_CONTENT_MAX_WIDTH = 720.dp

private const val ARGUMENT_SUMMARY_CHARS = 72
private const val STREAMING_CURSOR = " ▌"

/** 朗读 / 语音输入的提示在屏幕上停留多久（毫秒）。 */
private const val VOICE_ERROR_LINGER_MS = 5_000L

/** 电平条的最小可见宽度比例：0 会让它在安静时看着像坏了。 */
private const val LEVEL_MIN = 0.06f

/**
 * 模型切换 sheet，对齐 RikkaHub 的样式：顶部胶囊搜索 → 供应商分组（可折叠）→ 圆角模型卡
 * （选中用 primaryContainer）→ 底部说明。没有大标题，搜索框就是入口。
 *
 * 选中后由 ChatViewModel 落盘并重载 dsh 运行时；重载期间关闭切换（避免并发 remount）。
 * 重载会换 dsh 会话命名空间：界面历史保留，但模型上下文从下一条消息重新开始。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSwitchSheet(
  choices: List<ModelChoice>,
  switching: Boolean,
  error: String?,
  onSelect: (ModelChoice) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var collapsedProviders by remember { mutableStateOf(emptySet<String>()) }
  val visible = remember(choices, query) {
    val needle = query.trim()
    if (needle.isEmpty()) {
      choices
    } else {
      choices.filter {
        it.model.contains(needle, ignoreCase = true) ||
          it.providerName.contains(needle, ignoreCase = true)
      }
    }
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
      ModelSearchField(query = query, onQueryChange = { query = it })
      Text(
        text = "切换会重载运行时；界面历史保留，模型上下文从下一条消息重新开始。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp),
      )
      if (switching) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          LoadingIndicator(modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(10.dp))
          Text("切换中…", style = MaterialTheme.typography.bodySmall)
        }
      }
      if (error != null) {
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
      }
      ModelSwitchList(
        modifier = Modifier.weight(1f),
        visible = visible,
        choicesEmpty = choices.isEmpty(),
        switching = switching,
        collapseEnabled = query.isBlank(),
        collapsedProviders = collapsedProviders,
        onToggleProvider = { providerId ->
          collapsedProviders =
            if (providerId in collapsedProviders) {
              collapsedProviders - providerId
            } else {
              collapsedProviders + providerId
            }
        },
        onSelect = onSelect,
      )
    }
  }
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
  ) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      singleLine = true,
      shape = CircleShape,
      placeholder = { Text("输入模型名称搜索") },
      leadingIcon = { SearchGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant) },
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
        ),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ModelSwitchList(
  modifier: Modifier = Modifier,
  visible: List<ModelChoice>,
  choicesEmpty: Boolean,
  switching: Boolean,
  collapseEnabled: Boolean,
  collapsedProviders: Set<String>,
  onToggleProvider: (String) -> Unit,
  onSelect: (ModelChoice) -> Unit,
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (visible.isEmpty()) {
      item {
        Text(
          text =
            if (choicesEmpty) {
              "还没有可用模型。去 设置 → 提供商 从端点添加，再回来切换。"
            } else {
              "没有匹配的模型"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(8.dp),
        )
      }
    } else {
      visible.groupBy { it.providerId }.forEach { (providerId, group) ->
        val collapsed = collapseEnabled && providerId in collapsedProviders
        item(key = "provider-" + providerId) {
          ProviderHeader(
            name = group.first().providerName,
            count = group.size,
            collapsed = collapsed,
            enabled = collapseEnabled,
            onClick = { onToggleProvider(providerId) },
          )
        }
        if (!collapsed) {
          items(group, key = { it.providerId + "/" + it.model }) { choice ->
            ModelChoiceCard(
              choice = choice,
              enabled = !switching,
              onClick = { onSelect(choice) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProviderHeader(
  name: String,
  count: Int,
  collapsed: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, onClick = onClick)
        .padding(top = 6.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ChevronDownGlyph(
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = name,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "$count 个模型",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelChoiceCard(
  choice: ModelChoice,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val preset = LlmPreset.byId(choice.providerId) ?: LlmPreset.Custom
  Card(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (choice.current) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
          },
        contentColor =
          if (choice.current) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ProviderModelIcon(
        preset = preset,
        name = choice.providerName,
        size = 40,
        shape = RoundedCornerShape(12.dp),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = choice.model,
          style = MaterialTheme.typography.titleSmallEmphasized,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = choice.providerName,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (choice.current) {
              MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (choice.current) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)) {
          Text(
            text = "当前",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

/**
 * 浮在聊天之上的几层：审批确认、模型提问的面板，与「发现新版本」的提醒。
 *
 * 单独一个函数只是因为 [ChatScreen] 本身已经顶到 detekt 的函数长度上限了 ——
 * 这几块都**不属于聊天的布局**，抽出来反而更清楚：它们都是「有就弹、没有就不存在」的覆盖层。
 *
 * 审批优先于提问：审批是「任务被卡住」的那一个，先把决定给出去；提问面板可以等。
 * 两层同时存在时只渲染审批，避免两个 ModalBottomSheet 叠在一起。
 *
 * 更新提醒每个**进程启动**查一次；查不到就什么也不弹（更新是锦上添花，不该因为没网就打扰用户）。
 * 设置页那个入口是手动的，不受这里的跳过列表影响。
 */
@Composable
private fun ChatOverlays() {
  val pendingApproval by ApprovalHub.pending.collectAsStateWithLifecycle()
  val pendingQuestion by UserQuestionHub.pending.collectAsStateWithLifecycle()
  val approval = pendingApproval
  if (approval != null) {
    ApprovalSheet(approval)
  } else {
    pendingQuestion?.let { UserQuestionSheet(it) }
  }
  UpdateReminder()
}

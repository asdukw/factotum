package com.tappet.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.agent.AgentModelSwitcher
import com.tappet.app.agent.ChatMessage
import com.tappet.app.agent.ChatRole
import com.tappet.app.agent.ChatUiState
import com.tappet.app.agent.ChatViewModel
import com.tappet.app.agent.DshStreamer
import com.tappet.app.agent.LlmPreset
import com.tappet.app.agent.ModelChoice
import com.tappet.app.agent.ToolCallState
import com.tappet.app.agent.ToolCallView
import com.tappet.app.agent.UserQuestionHub
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.tappetSessionStore
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.device.BridgeHealth
import com.tappet.app.ui.components.ProviderModelIcon
import androidx.core.content.ContextCompat
import com.tappet.app.voice.RecognizedText
import com.tappet.app.voice.VoiceInput
import com.tappet.app.voice.VoiceReader
import com.tappet.app.voice.VoiceSettings
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
  store: SessionStore = LocalContext.current.tappetSessionStore(),
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: ChatViewModel = viewModel {
    ChatViewModel(
      // 配置来自设置页（密钥走 Keystore 封装）。运行时只在需要时重载：
      // 聊天页切换模型会 remount dsh（同一个 Node 进程），不必重启 App。
      config = AgentConfigStore.current(appContext),
      streamer = DshStreamer(appContext),
      store = store,
      switcher = AgentModelSwitcher(appContext),
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  ChatScreen(
    state = state,
    onInputChange = viewModel::onInputChange,
    onSend = viewModel::send,
    onStop = viewModel::stop,
    onNewSession = viewModel::newSession,
    onOpenSession = viewModel::openSession,
    onDeleteSession = viewModel::deleteSession,
    onOpenSettings = onOpenSettings,
    onSwitchModel = viewModel.modelSwitch::switch,
    onPrepareModelSwitch = viewModel.modelSwitch::prepare,
    modifier = modifier,
  )
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
  onOpenSettings: () -> Unit = {},
  onSwitchModel: (ModelChoice) -> Unit = {},
  onPrepareModelSwitch: () -> List<ModelChoice> = { emptyList() },
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current

  // 会话一开始跑就收起软键盘。
  // 理由不是「看着清爽」：agent 接下来要在屏幕上操作（读屏 / 截图 / 点按），
  // 键盘既占掉小半屏，又会让 IME 窗口出现在控件树与截图里 —— 模型会把键盘本身
  // 当成页面内容的一部分，去点一个并不存在的按钮。
  // 挂在 isStreaming 而不是「点了发送」上：重载运行时、恢复中断的会话等路径也会进入
  // 流式状态，这样一处覆盖得到。
  LaunchedEffect(state.isStreaming) {
    if (state.isStreaming) {
      keyboard?.hide()
      focus.clearFocus()
      // 新的任务开始了，上一条还在念的话立刻停 —— 用户接下来要看的是新回复，
      // 旧回复的声音会和新的正文抢注意力。
      VoiceReader.stop()
    }
  }

  // 模型提问（`ask_user_question`）：面板浮在聊天之上。
  // 状态在 `UserQuestionHub` 这个单例里，不在 ViewModel —— 桥那边挂起的协程属于进程，
  // 而 UI 会随导航重建；放进 ViewModel 会让「切走再切回来」变成「答案丢了、插件还在等」。
  ChatOverlays()

  // 模型切换：sheet 打开时读一次候选；切换成功后自动收起，失败留在 sheet 里显示原因。
  var modelSheetOpen by remember { mutableStateOf(false) }
  var pendingSwitch by remember { mutableStateOf(false) }
  var modelChoices by remember { mutableStateOf(emptyList<ModelChoice>()) }
  LaunchedEffect(state.switchingModel) {
    if (pendingSwitch && !state.switchingModel && state.switchError == null) {
      modelSheetOpen = false
      pendingSwitch = false
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    // 任务进行中不让侧滑切会话：事件流只认当前会话，切走会把半截回复写错地方。
    gesturesEnabled = state.canSwitchSession,
    drawerContent = {
      SessionDrawer(
        sessions = state.sessions,
        currentId = state.sessionId,
        enabled = state.canSwitchSession,
        configSummary = state.configSummary,
        onNewSession = {
          onNewSession()
          scope.launch { drawerState.close() }
        },
        onOpenSession = { id ->
          onOpenSession(id)
          scope.launch { drawerState.close() }
        },
        onDeleteSession = onDeleteSession,
        onOpenSettings = {
          onOpenSettings()
          scope.launch { drawerState.close() }
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
      onOpenSessions = { scope.launch { drawerState.open() } },
      onModelSwitchClick = {
        modelChoices = onPrepareModelSwitch()
        modelSheetOpen = true
      },
    )
  }

  if (modelSheetOpen) {
    ModelSwitchSheet(
      choices = modelChoices,
      switching = state.switchingModel,
      error = state.switchError,
      onSelect = { choice ->
        pendingSwitch = true
        onSwitchModel(choice)
      },
      onDismiss = {
        modelSheetOpen = false
        pendingSwitch = false
      },
    )
  }
}

/**
 * 朗读失败的提示只在屏幕上停一会儿：它是提示，不是需要用户处理的错误。
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

  Box(
    modifier =
      Modifier.size(38.dp)
        .clip(CircleShape)
        .background(background)
        .clickable(onClick = onClick)
        .semantics { contentDescription = if (listening) "停止录音" else "语音输入" },
    contentAlignment = Alignment.Center,
  ) {
    if (busy) {
      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
      MicGlyph(tint = tint.copy(alpha = alpha))
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
) {
  val sessionTitle =
    state.sessions.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() } ?: "Tappet"

  val lastMessage = state.messages.lastOrNull()

  val voiceState by VoiceReader.state.collectAsStateWithLifecycle()
  val voiceError by VoiceReader.error.collectAsStateWithLifecycle()
  val dictation by VoiceInput.state.collectAsStateWithLifecycle()
  val dictationResult by VoiceInput.result.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // 权限弹窗只能从界面发起（ColorOS 会拦 adb 的 pm grant）。授完直接开录，
  // 不让用户再点一次麦克风 —— 他刚才点的就是「我要说话」。
  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) VoiceInput.start(context) else VoiceInput.clearError()
    }

  // 识别结果回填输入框：**追加**而不是覆盖 —— 用户可能已经打了半句，
  // 或者连着说了两段。清掉结果再回填，避免旋转 / 重组时重复追加同一段。
  LaunchedEffect(dictationResult?.id) {
    val text = dictationResult?.text ?: return@LaunchedEffect
    VoiceInput.clearResult()
    onInputChange(RecognizedText.appendToInput(state.input, text))
  }

  VoiceErrorLinger(voiceError)
  DictationErrorLinger(dictation)
  AutoReadOnFinish(messages = state.messages, sessionId = state.sessionId)

  // 新消息 / 流式增量都跟着滚到底部
  LaunchedEffect(
    state.messages.size,
    lastMessage?.content?.length,
    lastMessage?.reasoning?.length,
    lastMessage?.error,
    lastMessage?.toolCalls?.size,
  ) {
    if (state.messages.isNotEmpty()) {
      listState.scrollToItem(state.messages.lastIndex)
    }
  }

  Column(modifier.fillMaxSize().safeDrawingPadding()) {
    val modelSubtitle =
      listOf(state.providerName, state.model).filter { it.isNotBlank() }.joinToString(" · ")
    ChatTopBar(
      title = sessionTitle,
      subtitle = modelSubtitle,
      onOpenSessions = onOpenSessions,
      onNewSession = onNewSession,
    )
    AccessibilityPrompt()
    if (!state.configured) {
      // 别再指向 local.properties：构建期注入已经去掉，那里没有密钥可填了。
      BannerText("未配置 API key：点左上角菜单 → 设置，填自己的 key（存进 Keystore）。")
    }
    state.storageError?.let { reason -> BannerText("本地存储不可用，消息无法保存：$reason") }
    voiceError?.let { reason -> BannerText("朗读失败：$reason") }
    (dictation as? VoiceInput.State.Failed)?.let { reason -> BannerText("语音输入：${reason.reason}") }

    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      if (state.messages.isEmpty()) {
        item { EmptyHint() }
      }
      items(items = state.messages, key = { it.id }) { message ->
        MessageBubble(message, speakingId = voiceState?.messageId)
      }
    }

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
      onMicClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
          PackageManager.PERMISSION_GRANTED
        ) {
          micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
          VoiceInput.toggle(context)
        }
      },
    )
  }
}

@Composable
private fun ChatTopBar(
  title: String,
  subtitle: String,
  onOpenSessions: () -> Unit,
  onNewSession: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onOpenSessions) { MenuGlyph(tint = MaterialTheme.colorScheme.onSurface) }
    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
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
    IconButton(onClick = onNewSession) { PlusGlyph(tint = MaterialTheme.colorScheme.onSurface) }
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
        "Tappet 只能通过系统的无障碍服务操作手机，刚才这个操作没有执行就是因为这个服务没开" +
          "（Android 不允许应用自己开启它）。\n\n" +
          "点「去开启」→ 在「已安装的服务」里找到 Tappet 打开；系统随后会弹「保持开启」" +
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

@Composable
private fun BannerText(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
  )
}

@Composable
private fun SessionDrawer(
  sessions: List<ChatSession>,
  currentId: String?,
  enabled: Boolean,
  configSummary: String,
  onNewSession: () -> Unit,
  onOpenSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
  val grouped = groupSessions(sessions, System.currentTimeMillis())

  ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "会话",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        IconButton(onClick = onNewSession, enabled = enabled) {
          PlusGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      SessionList(
        // weight 只有 ColumnScope 里能用，所以从调用点传进去
        modifier = Modifier.weight(1f),
        grouped = grouped,
        currentId = currentId,
        enabled = enabled,
        onOpenSession = onOpenSession,
        onRequestDelete = { pendingDelete = it },
      )

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
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

@Composable
private fun SessionRow(
  session: ChatSession,
  selected: Boolean,
  enabled: Boolean,
  onOpen: () -> Unit,
  onDelete: () -> Unit,
) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen),
  ) {
    Row(
      modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = session.title?.takeIf { it.isNotBlank() } ?: "新会话",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = "删",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onDelete).padding(8.dp),
      )
    }
  }
}

@Composable
private fun EmptyHint() {
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Text(text = "M", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
    Text(
      text = "你好，我是 Tappet",
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier.padding(top = 18.dp),
    )
    Text(
      text = "说出要手机做什么，例如「打开设置看看电池电量」。\n任务会实时操作手机界面，工具调用会逐张出现。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
}

@Composable
private fun MessageBubble(message: ChatMessage, speakingId: String? = null) {
  if (message.role == ChatRole.User) {
    UserBubble(message.content)
  } else {
    AssistantMessage(message, speaking = message.id == speakingId)
  }
}

/** 用户消息：右侧浅色气泡，无头像，对齐 ChatGPT / DeepSeek。 */
@Composable
private fun UserBubble(content: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = RoundedCornerShape(22.dp),
      modifier = Modifier.widthIn(max = USER_BUBBLE_MAX_WIDTH),
    ) {
      Text(
        text = content,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }
  }
}

/** 助手消息：全宽正文（无气泡），上面依次挂思考折叠与工具卡，末尾一行操作。 */
@Composable
private fun AssistantMessage(message: ChatMessage, speaking: Boolean = false) {
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // 过程（工具 + 思考）收进一个可折叠块；正文永远在块外面。
    ProcessBlock(message)

    val error = message.error
    when {
      error != null ->
        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

      message.content.isNotEmpty() ->
        MarkdownText(markdown = message.content + if (message.streaming) STREAMING_CURSOR else "")

      message.reasoning.isNullOrBlank() && message.toolCalls.isEmpty() ->
        if (message.thinking) ThinkingIndicator() else Text(text = "…", style = MaterialTheme.typography.bodyMedium)
    }

    if (message.content.isNotBlank() && !message.streaming && error == null) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        SpeakAction(messageId = message.id, markdown = message.content, speaking = speaking)
        Spacer(Modifier.width(2.dp))
        CopyAction(text = message.content)
      }
    }
  }
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
      Modifier.clip(RoundedCornerShape(8.dp))
        .clickable { VoiceReader.toggle(context, messageId, markdown) }
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
      Modifier.clip(RoundedCornerShape(8.dp)).clickable {
        copyTextToClipboard(context, text)
        copied = true
      }.padding(horizontal = 8.dp, vertical = 6.dp),
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
  // 推理内容不展示正文，只做一个呼吸效果，避免界面看起来卡住。
  val infiniteTransition = rememberInfiniteTransition(label = "thinking")
  val alpha by
    infiniteTransition.animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 700), repeatMode = RepeatMode.Reverse),
      label = "thinking-alpha",
    )
  Text(
    text = "思考中…",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.alpha(alpha),
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
) {
  val textStyle = MaterialTheme.typography.bodyLarge
  Column(modifier = Modifier.fillMaxWidth().imePadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
    Surface(
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
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
          decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
              if (value.isEmpty()) {
                Text(
                  text = "和 Tappet 说点什么…",
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
                Modifier.size(40.dp)
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

  Box(
    modifier =
      modifier.size(38.dp).clip(CircleShape).background(background).clickable(enabled = enabled) {
        if (isStreaming) onStop() else onSend()
      },
    contentAlignment = Alignment.Center,
  ) {
    if (isStreaming) {
      StopGlyph(tint = tint)
    } else {
      ArrowUpGlyph(tint = tint)
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
  clipboard.setPrimaryClip(ClipData.newPlainText("tappet", text))
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
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
          style = MaterialTheme.typography.titleSmall,
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
 * 浮在聊天之上的两层：模型提问的面板，与「发现新版本」的提醒。
 *
 * 单独一个函数只是因为 [ChatScreen] 本身已经顶到 detekt 的函数长度上限了 ——
 * 这两块都**不属于聊天的布局**，抽出来反而更清楚：它们都是「有就弹、没有就不存在」的覆盖层。
 *
 * 更新提醒每个**进程启动**查一次；查不到就什么也不弹（更新是锦上添花，不该因为没网就打扰用户）。
 * 设置页那个入口是手动的，不受这里的跳过列表影响。
 */
@Composable
private fun ChatOverlays() {
  val pendingQuestion by UserQuestionHub.pending.collectAsStateWithLifecycle()
  pendingQuestion?.let { UserQuestionSheet(it) }
  UpdateReminder()
}

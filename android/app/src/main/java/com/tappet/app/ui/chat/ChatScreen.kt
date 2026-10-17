package com.tappet.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tappet.app.agent.ModelChoice
import com.tappet.app.agent.ToolCallState
import com.tappet.app.agent.ToolCallView
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.tappetSessionStore
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.device.BridgeHealth
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
    ChatTopBar(title = sessionTitle, onOpenSessions = onOpenSessions, onNewSession = onNewSession)
    AccessibilityPrompt()
    if (!state.configured) {
      // 别再指向 local.properties：构建期注入已经去掉，那里没有密钥可填了。
      BannerText("未配置 API key：点左上角菜单 → 设置，填自己的 key（存进 Keystore）。")
    }
    state.storageError?.let { reason -> BannerText("本地存储不可用，消息无法保存：$reason") }

    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      if (state.messages.isEmpty()) {
        item { EmptyHint() }
      }
      items(items = state.messages, key = { it.id }) { message -> MessageBubble(message) }
    }

    ChatInputBar(
      value = state.input,
      isStreaming = state.isStreaming,
      canSend = state.canSend,
      modelLabel =
        listOf(state.providerName, state.model)
          .filter { it.isNotBlank() }
          .joinToString(" · ")
          .ifBlank { state.configSummary },
      onValueChange = onInputChange,
      onSend = onSend,
      onStop = onStop,
      onModelSwitchClick = onModelSwitchClick,
    )
  }
}

@Composable
private fun ChatTopBar(title: String, onOpenSessions: () -> Unit, onNewSession: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onOpenSessions) { MenuGlyph(tint = MaterialTheme.colorScheme.onSurface) }
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
    )
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
  AccessibilityBanner(onOpenSettings = { context.startActivity(AccessibilityStatus.settingsIntent()) })
}

/**
 * 「无障碍没开」的提示卡。
 *
 * 出现的条件见 [AccessibilityStatus.shouldPrompt]：必须**先有一次设备动作失败**，
 * 所以纯聊天（模型没碰设备）不会弹这张卡。
 *
 * 文案里那句「保持开启」是 ColorOS / 一加的坑：不点它，系统会把服务关掉，用户会以为白开了
 * —— 实测过，所以写进提示里而不是只写在文档里。
 */
@Composable
private fun AccessibilityBanner(onOpenSettings: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "无障碍服务没开，读屏 / 点击 / 截图都用不了",
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = "应用无法自行开启它（Android 不允许），需要你手动开一次。",
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text =
          "点「去开启」→ 在「已下载的应用 / 已安装的服务」里找到 Tappet 打开；" +
            "系统随后会弹「保持开启」（3 秒倒计时），必须点它，不点会被系统关掉。",
        style = MaterialTheme.typography.bodySmall,
      )
      Button(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) { Text("去开启") }
    }
  }
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
      DrawerNavRow("设置（端点 / API key）", onClick = onOpenSettings)
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
private fun DrawerNavRow(label: String, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
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
private fun MessageBubble(message: ChatMessage) {
  if (message.role == ChatRole.User) UserBubble(message.content) else AssistantMessage(message)
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
private fun AssistantMessage(message: ChatMessage) {
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
      CopyAction(text = message.content)
    }
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
  modelLabel: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
  onModelSwitchClick: () -> Unit,
) {
  val textStyle = MaterialTheme.typography.bodyLarge
  Column(modifier = Modifier.fillMaxWidth().imePadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(26.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      shadowElevation = 4.dp,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          modifier = Modifier.weight(1f).padding(vertical = 8.dp),
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
        Spacer(Modifier.width(8.dp))
        SendStopButton(
          isStreaming = isStreaming,
          canSend = canSend,
          onSend = onSend,
          onStop = onStop,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
    }
    if (modelLabel.isNotBlank()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = !isStreaming, onClick = onModelSwitchClick)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = modelLabel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!isStreaming) {
          Spacer(Modifier.width(6.dp))
          Text(
            text = "切换",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
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

/**
 * 聊天页的模型切换 sheet：按提供商分组列出「可用模型」。
 *
 * 选中后由 ChatViewModel 落盘并重载 dsh 运行时；重载期间关闭切换（避免并发 remount）。
 * 运行时重载会换掉 dsh 会话命名空间：界面历史保留，但模型上下文从下一条消息重新开始。
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
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Text("切换模型", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(6.dp))
      Text(
        text = "切换会重载运行时（约 1–3 秒）；界面记录保留，新消息使用新的模型上下文。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (switching) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(10.dp))
          Text("切换中…", style = MaterialTheme.typography.bodySmall)
        }
      }
      if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Spacer(Modifier.height(8.dp))
      if (choices.isEmpty()) {
        Text(
          text = "还没有可用模型。去 设置 → 提供商 从端点添加，再回来切换。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
          choices.groupBy { it.providerId }.forEach { (_, group) ->
            item(key = "provider-" + group.first().providerId) {
              Text(
                text = group.first().providerName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
              )
            }
            items(group, key = { it.providerId + "/" + it.model }) { choice ->
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !switching) { onSelect(choice) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = choice.model,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                if (choice.current) {
                  Text(
                    text = "当前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

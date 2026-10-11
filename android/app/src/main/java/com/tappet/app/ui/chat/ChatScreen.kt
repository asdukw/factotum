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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.agent.ChatMessage
import com.tappet.app.agent.ChatRole
import com.tappet.app.agent.ChatUiState
import com.tappet.app.agent.ChatViewModel
import com.tappet.app.agent.DshStreamer
import com.tappet.app.agent.ToolCallState
import com.tappet.app.agent.ToolCallView
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.tappetSessionStore
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 原生聊天页（默认入口）。
 *
 * 视觉对齐 ChatGPT / DeepSeek 手机端：白底、极简顶栏、用户消息右侧浅色气泡、
 * 助手正文全宽无气泡、底部浮动圆角输入卡。工具调用 / 思考折叠保留为次要灰卡。
 * dsh 的 Web UI 仍作为「按需打开」的预览面板（入口在会话抽屉里）。
 */
@Composable
fun ChatScreen(
  onOpenProbe: () -> Unit,
  onOpenWeb: () -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
  store: SessionStore = LocalContext.current.tappetSessionStore(),
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: ChatViewModel = viewModel {
    ChatViewModel(
      // 配置来自设置页（密钥走 Keystore 封装），构建期注入只是开发机上的兜底默认值。
      // 这里读一次就定了：设置页改完要下次启动才生效，与内嵌 Node 的 env 时机一致。
      config = AgentConfigStore.current(appContext),
      streamer = DshStreamer(appContext),
      store = store,
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
    onOpenProbe = onOpenProbe,
    onOpenWeb = onOpenWeb,
    onOpenSettings = onOpenSettings,
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
  onOpenProbe: () -> Unit = {},
  onOpenWeb: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()

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

  val sessionTitle =
    state.sessions.firstOrNull { it.id == state.sessionId }?.title?.takeIf { it.isNotBlank() } ?: "Tappet"

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
        onOpenWeb = {
          onOpenWeb()
          scope.launch { drawerState.close() }
        },
        onOpenProbe = {
          onOpenProbe()
          scope.launch { drawerState.close() }
        },
        onOpenSettings = {
          onOpenSettings()
          scope.launch { drawerState.close() }
        },
      )
    },
  ) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
      ChatTopBar(
        title = sessionTitle,
        onOpenSessions = { scope.launch { drawerState.open() } },
        onNewSession = onNewSession,
      )

      if (!state.configured) {
        BannerText("未配置 API key：测试阶段请在 android/local.properties 里填 tappet.agent.apiKey 后重新安装。")
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
        configSummary = state.configSummary,
        onValueChange = onInputChange,
        onSend = onSend,
        onStop = onStop,
      )
    }
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
  onOpenWeb: () -> Unit,
  onOpenProbe: () -> Unit,
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

      LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (sessions.isEmpty()) {
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
              onDelete = { pendingDelete = session },
            )
          }
        }
      }

      HorizontalDivider(Modifier.padding(vertical = 8.dp))
      DrawerNavRow("Web 预览（HTML / Office / diff）", onClick = onOpenWeb)
      DrawerNavRow("设备探针", onClick = onOpenProbe)
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
          "「${target.title ?: "新会话"}」的消息与工具调用会一起删除，不能撤销。本地库卸载后无法恢复，删之前请确认不再需要这段记录。"
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteSession(target.id)
            pendingDelete = null
          }
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
    message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
      ReasoningRow(text = reasoning, running = message.thinking)
    }

    message.toolCalls.forEach { call -> ToolCallCard(call) }

    val error = message.error
    when {
      error != null ->
        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

      message.content.isNotEmpty() ->
        MarkdownText(markdown = message.content + if (message.streaming) STREAMING_CURSOR else "")

      message.thinking -> ThinkingIndicator()

      message.toolCalls.isEmpty() -> Text(text = "…", style = MaterialTheme.typography.bodyMedium)
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

/** 一张工具卡：默认一行（名字 + 参数摘要 + 状态），点开看完整参数与结果。 */
@Composable
private fun ToolCallCard(call: ToolCallView) {
  var expanded by remember(call.id) { mutableStateOf(false) }
  val accent =
    when (call.state) {
      ToolCallState.Running -> MaterialTheme.colorScheme.primary
      ToolCallState.Ok -> MaterialTheme.colorScheme.onSurfaceVariant
      ToolCallState.Failed -> MaterialTheme.colorScheme.error
    }
  val label =
    when (call.state) {
      ToolCallState.Running -> "执行中"
      ToolCallState.Ok -> "完成"
      ToolCallState.Failed -> "失败"
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Text(
          text = call.name,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(start = 8.dp),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = accent)
      }
      Text(
        text = compactArguments(call.arguments),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.dp, top = 2.dp),
      )

      if (expanded) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
          text = "参数",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = prettyJson(call.arguments),
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          modifier = Modifier.padding(top = 2.dp),
        )
        call.result?.let { result ->
          Text(
            text = "结果",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
          )
          Text(
            text = result.take(MAX_TOOL_RESULT_CHARS) + if (result.length > MAX_TOOL_RESULT_CHARS) "\n…（已截断）" else "",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }
    }
  }
}

/**
 * 「思考」折叠行，对齐 ChatGPT / DeepSeek 的低调样式：不是卡片，只是一行灰字 + 箭头；
 * 收起时显示推理首行摘要，展开后显示完整推理（次要字号）。
 */
@Composable
private fun ReasoningRow(text: String, running: Boolean) {
  var expanded by remember { mutableStateOf(false) }
  val summary = remember(text) { reasoningSummary(text) }

  val infiniteTransition = rememberInfiniteTransition(label = "reasoning")
  val alpha by
    infiniteTransition.animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(animation = tween(durationMillis = 700), repeatMode = RepeatMode.Reverse),
      label = "reasoning-alpha",
    )

  Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = if (running) "思考中…" else "已深度思考",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.alpha(if (running) alpha else 1f),
      )
      ChevronDownGlyph(
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
      )
    }

    if (expanded) {
      MarkdownText(
        markdown = text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
      )
    } else if (summary.isNotBlank()) {
      Text(
        text = summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp).alpha(if (running) alpha else 1f),
      )
    }
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
  configSummary: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
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
    if (configSummary.isNotBlank()) {
      Text(
        text = configSummary,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
      )
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

private fun copyTextToClipboard(context: Context, text: String) {
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

private const val MAX_TOOL_RESULT_CHARS = 1200
private const val ARGUMENT_SUMMARY_CHARS = 72
private const val STREAMING_CURSOR = " ▌"

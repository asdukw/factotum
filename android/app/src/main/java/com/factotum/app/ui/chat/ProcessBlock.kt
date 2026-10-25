package com.factotum.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.factotum.app.agent.ChatMessage
import com.factotum.app.agent.ToolCallState
import com.factotum.app.agent.ToolCallView
import kotlinx.coroutines.delay

/**
 * 一轮任务里的「过程」：工具调用 + 思考。
 *
 * **整块一个折叠**（收起时只留一行「再显示 N 步」），展开后每个工具、每段思考各自可折叠：
 * 工具点开是底部详情（原始参数与结果），思考就地展开。**正文永远在块外面** —— 这一层只装过程。
 *
 * 默认策略：流式进行中展开（让人看见在干什么），完成后自动收起；但用户手动动过之后就不再替他动。
 */
@Composable
internal fun ProcessBlock(message: ChatMessage) {
  val reasoning = message.reasoning?.takeIf { it.isNotBlank() }
  val steps = message.toolCalls.size + if (reasoning != null) 1 else 0
  if (steps == 0) return

  var expanded by rememberSaveable(message.id) { mutableStateOf(message.streaming) }
  var userToggled by rememberSaveable(message.id) { mutableStateOf(false) }
  LaunchedEffect(message.streaming) {
    if (!message.streaming && !userToggled) expanded = false
  }

  // 多轮工具调用会把过程撑得很长：展开区有高度上限，自己滚，并跟随最新一步。
  val stepsScroll = rememberScrollState()
  var followSteps by remember(message.id) { mutableStateOf(true) }
  ProcessStepAutoScroll(
    scroll = stepsScroll,
    expanded = expanded,
    stepCount = message.toolCalls.size,
    reasoningLength = reasoning?.length ?: 0,
    following = followSteps,
    onFollowingChange = { followSteps = it },
  )

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.animateContentSize()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
          Modifier.fillMaxWidth().clickable {
            expanded = !expanded
            userToggled = true
          }.padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        ChevronDownGlyph(
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else -90f),
        )
        Text(
          text =
            when {
              expanded -> "收起过程"
              message.streaming -> "正在执行…"
              else -> "再显示 $steps 步"
            },
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp),
        )
      }
      if (expanded) {
        HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        Column(
          Modifier
            .fillMaxWidth()
            .heightIn(max = processStepsMaxHeight)
            .verticalScroll(stepsScroll),
        ) {
          message.toolCalls.forEach { call -> ToolStepRow(call) }
          if (reasoning != null) {
            ThinkingStepRow(
              reasoning = reasoning,
              thinkingMs = message.thinkingMs,
              startedAt = message.thinkingStartedAt,
              running = message.thinking,
            )
          }
          Spacer(Modifier.height(4.dp))
        }
      }
    }
  }
}

/**
 * 过程卡的内部滚动：新步骤到达时贴到底部，用户自己上滑后暂停，滑回底部再恢复。
 * 与聊天列表的 FollowOutput 同一套意图判定（只在滚动结束时采样位置）。
 */
@Composable
private fun ProcessStepAutoScroll(
  scroll: ScrollState,
  expanded: Boolean,
  stepCount: Int,
  reasoningLength: Int,
  following: Boolean,
  onFollowingChange: (Boolean) -> Unit,
) {
  LaunchedEffect(scroll) {
    snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
      if (!scrolling) onFollowingChange(scroll.value >= scroll.maxValue - 8)
    }
  }
  // 展开 = 回到最新一步。
  LaunchedEffect(expanded) {
    if (expanded) onFollowingChange(true)
  }
  // 新工具 / 新推理：等这一帧布局更新 maxValue，再贴到底部。
  LaunchedEffect(expanded, stepCount, reasoningLength, following) {
    if (!expanded || !following) return@LaunchedEffect
    withFrameNanos {}
    scroll.scrollTo(scroll.maxValue)
  }
}

/**
 * 块里的一行工具：图标 + **中文显示名**（不是 raw tool name）+ 状态 + 右箭头；
 * 点开是底部详情（原始参数与结果都在那儿，可以复制）。
 */
@Composable
private fun ToolStepRow(call: ToolCallView) {
  var showDetail by remember(call.id) { mutableStateOf(false) }
  val accent =
    when (call.state) {
      ToolCallState.Running -> MaterialTheme.colorScheme.primary
      ToolCallState.Ok -> MaterialTheme.colorScheme.onSurfaceVariant
      ToolCallState.Failed -> MaterialTheme.colorScheme.error
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().clickable { showDetail = true }.padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    ToolGlyph(tint = accent, modifier = Modifier.size(18.dp))
    Text(
      text = toolDisplayName(call.name),
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(start = 10.dp),
    )
    when (call.state) {
      ToolCallState.Running -> Text("执行中", style = MaterialTheme.typography.labelSmall, color = accent)
      ToolCallState.Failed -> Text("失败", style = MaterialTheme.typography.labelSmall, color = accent)
      ToolCallState.Ok -> CheckGlyph(tint = accent, modifier = Modifier.size(14.dp))
    }
    ChevronDownGlyph(
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(16.dp).padding(start = 4.dp).rotate(-90f),
    )
  }
  if (showDetail) {
    ToolDetailSheet(call = call, onDismiss = { showDetail = false })
  }
}

/**
 * 块里的一行思考：灯泡 + 「思考了 3.8 秒」（时长只在内存里，重载后回落到「思考过程」）。
 * 收起时给一行推理摘要，展开后是完整推理（Markdown，次要字号）。
 */
@Composable
private fun ThinkingStepRow(
  reasoning: String,
  thinkingMs: Long?,
  startedAt: Long?,
  running: Boolean,
) {
  var expanded by remember { mutableStateOf(false) }
  val summary = remember(reasoning) { reasoningSummary(reasoning) }
  // 思考中每 100ms 重算一次已经过去多久；结算后停在 thinkingMs（RikkaHub 同一条做法）。
  val liveMs by
    produceState(0L, running, startedAt) {
      if (!running || startedAt == null) return@produceState
      while (true) {
        value = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        delay(100)
      }
    }
  val shownMs = shownThinkingMs(running = running, thinkingMs = thinkingMs, startedAt = startedAt, liveMs = liveMs)
  Column(Modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      BulbGlyph(tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      Text(
        text = thinkingLabel(running = running, shownMs = shownMs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f).padding(start = 10.dp),
      )
      ChevronDownGlyph(
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else -90f),
      )
    }
    when {
      expanded ->
        MarkdownText(
          markdown = reasoning,
          modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 10.dp),
          style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )

      summary.isNotBlank() ->
        Text(
          text = summary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 10.dp),
        )
    }
  }
}

/** 工具详情的底部面板：原始参数 / 结果 + 复制。原始工具名放小字，主标题用中文显示名。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDetailSheet(call: ToolCallView, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("工具调用", style = MaterialTheme.typography.titleLarge)
      Text("调用工具 ${toolDisplayName(call.name)}", style = MaterialTheme.typography.bodyMedium)
      Text(
        text =
          call.name + " · " +
            when (call.state) {
              ToolCallState.Running -> "执行中"
              ToolCallState.Ok -> "完成"
              ToolCallState.Failed -> "失败"
            },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      SheetCode(title = "参数", text = prettyJson(call.arguments))
      SheetCode(title = "调用结果", text = call.result ?: "（还没有结果）")
    }
  }
}

/** 详情面板里的一段等宽代码块 + 复制。 */
@Composable
private fun SheetCode(title: String, text: String) {
  val context = LocalContext.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(1500)
      copied = false
    }
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = {
          copyTextToClipboard(context, text)
          copied = true
        },
      ) {
        Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall)
      }
    }
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHighest,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = text.take(MAX_SHEET_CODE_CHARS) + if (text.length > MAX_SHEET_CODE_CHARS) "\n…（已截断）" else "",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(12.dp),
      )
    }
  }
}

/** 思考中优先用实时值；结算后用 thinkingMs。 */
private fun shownThinkingMs(running: Boolean, thinkingMs: Long?, startedAt: Long?, liveMs: Long): Long? =
  if (running) {
    if (startedAt != null) liveMs else null
  } else {
    thinkingMs
  }

private fun thinkingLabel(running: Boolean, shownMs: Long?): String =
  when {
    running && shownMs != null -> "思考中 ${formatThinkingDuration(shownMs)}"
    running -> "思考中…"
    shownMs != null -> "思考了 ${formatThinkingDuration(shownMs)}"
    else -> "思考过程"
  }

private const val MAX_SHEET_CODE_CHARS = 4000

/** 过程卡展开区的高度上限：超过就内部滚动，避免多轮工具调用把消息撑成几屏。 */
private val processStepsMaxHeight = 280.dp

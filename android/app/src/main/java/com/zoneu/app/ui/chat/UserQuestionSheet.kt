package com.zoneu.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.PendingUserQuestions
import com.zoneu.app.agent.UserQuestion
import com.zoneu.app.agent.UserQuestionAnswer
import com.zoneu.app.agent.UserQuestionHub
import com.zoneu.app.agent.UserQuestionOption

/**
 * `ask_user_question` 的面板：模型问、用户答。
 *
 * 语义照抄 dsh（`dsh-client-ui-user-questions` + `ctx.userQuestions`，见 `docs/user-questions.md`）：
 * - **一屏一题**、顶部页码；dsh 的 Web 版也是翻页器。
 * - **单选点了就走**（dsh 原话：点击单选选项会立即前进）。
 * - **多选要确认**再前进；已选标签在写自定义答案时**保留**（提交项同时带 `selected` + `custom`）。
 * - **单选的自定义答案互斥**：填了自定义文本，`selected` 就是空的。
 * - **跳过 = 空答案**（`{ id, selected: [] }`）—— 宿主那边一批必须每题一条。
 * - **关闭不是回答**：下滑关掉 → [UserQuestionHub.dismiss]，插件会让路给下一个 answerer。
 *
 * 这里**不做倒计时**：dsh 的倒计时属于 timed 等待（`attachWait`），而我们接的是普通
 * `ask()`（前台阻塞），没有窗口可倒。真要做得先把 timed 那条链路也接上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserQuestionSheet(pending: PendingUserQuestions) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val answers = remember { mutableStateMapOf<String, UserQuestionAnswer>() }
  var index by remember { mutableStateOf(0) }
  var custom by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf(setOf<String>()) }

  // 换一批问题 = 换一次会话状态。用 callId 做 key：同一批里翻页不会重置。
  LaunchedEffect(pending.callId) {
    answers.clear()
    index = 0
    custom = ""
    selected = emptySet()
  }

  val question = pending.questions.getOrNull(index) ?: return

  fun submit(answer: UserQuestionAnswer) {
    answers[question.id] = answer
    if (index < pending.questions.lastIndex) {
      index += 1
      custom = ""
      selected = emptySet()
      return
    }
    // 一批必须每题一条：按 pending 顺序补齐（用户从后往前答也不会漏）。
    val batch = pending.questions.mapNotNull { answers[it.id] }
    if (batch.size == pending.questions.size) UserQuestionHub.answer(batch)
  }

  ModalBottomSheet(onDismissRequest = { UserQuestionHub.dismiss() }, sheetState = sheetState) {
    // imePadding：写自定义回答时键盘会盖住输入框和「继续 / 提交」，这一步不能靠用户自己滚。
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
      SheetHeader(pending, index)
      Spacer(Modifier.height(10.dp))
      Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        QuestionText(question, showHeader = pending.questions.size > 1)
        QuestionOptions(
          question = question,
          selected = selected,
          onToggle = { label ->
            selected = if (label in selected) selected - label else selected + label
          },
          onPickImmediately = { label -> submit(UserQuestionAnswer(question.id, listOf(label))) },
        )
        CustomAnswerField(value = custom, onValueChange = { custom = it })
      }
      Spacer(Modifier.height(8.dp))
      QuestionActions(
        hasOptions = question.options.isNotEmpty(),
        canSubmit = custom.isNotBlank() || selected.isNotEmpty(),
        isLast = index == pending.questions.lastIndex,
        onSkip = { submit(UserQuestionAnswer(question.id, emptyList())) },
        onSubmit = { submit(answerFor(question, selected, custom)) },
      )
    }
  }
}

/** 单选时自定义答案与选项**互斥**（selected 留空）；多选时两者可以同时带。这是 dsh 的语义。 */
private fun answerFor(question: UserQuestion, selected: Set<String>, custom: String): UserQuestionAnswer {
  val keep = if (question.multiSelect) selected.toList() else emptyList()
  return UserQuestionAnswer(question.id, keep, custom.takeIf { it.isNotBlank() })
}

@Composable
private fun SheetHeader(pending: PendingUserQuestions, index: Int) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    val single = pending.questions.size == 1
    val title = if (single) pending.questions.first().header ?: "zoneu 想问你" else "zoneu 想问你"
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.weight(1f))
    if (!single) {
      Text(
        "${index + 1} / ${pending.questions.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun QuestionText(question: UserQuestion, showHeader: Boolean) {
  question.header?.takeIf { showHeader }?.let {
    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
  }
  Text(
    question.question,
    style = MaterialTheme.typography.titleMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
  )
  question.detail?.takeIf { it.isNotBlank() }?.let {
    Spacer(Modifier.height(8.dp))
    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun QuestionOptions(
  question: UserQuestion,
  selected: Set<String>,
  onToggle: (String) -> Unit,
  onPickImmediately: (String) -> Unit,
) {
  if (question.options.isEmpty()) return
  Spacer(Modifier.height(12.dp))
  question.options.forEach { option ->
    OptionRow(
      option = option,
      checked = option.label in selected,
      multiSelect = question.multiSelect,
      onClick = { if (question.multiSelect) onToggle(option.label) else onPickImmediately(option.label) },
    )
  }
}

@Composable
private fun OptionRow(option: UserQuestionOption, checked: Boolean, multiSelect: Boolean, onClick: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        // selectable 会把「单选/多选」和「已选中」一起交给读屏；裸 clickable 只会念出一个按钮。
        .selectable(
          selected = checked,
          role = if (multiSelect) Role.Checkbox else Role.RadioButton,
          onClick = onClick,
        )
        .defaultMinSize(minHeight = 48.dp)
        .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 控件本身只是视觉：语义已经在外层 selectable 上，再读一遍会重复两次。
    if (multiSelect) {
      Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    } else {
      RadioButton(selected = checked, onClick = null, modifier = Modifier.clearAndSetSemantics {})
    }
    Spacer(Modifier.width(8.dp))
    Column {
      Text(option.label, style = MaterialTheme.typography.bodyLarge)
      option.description?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun CustomAnswerField(value: String, onValueChange: (String) -> Unit) {
  Spacer(Modifier.height(12.dp))
  Text("或者直接说", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(6.dp))
  Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { contentDescription = "自定义回答" },
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      maxLines = 4,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      decorationBox = { inner ->
        if (value.isEmpty()) {
          Text(
            "输入你的回答…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        inner()
      },
    )
  }
}

@Composable
private fun QuestionActions(
  hasOptions: Boolean,
  canSubmit: Boolean,
  isLast: Boolean,
  onSkip: () -> Unit,
  onSubmit: () -> Unit,
) {
  // 没有可选项时（纯自由问答）也给「继续」—— 否则用户只能靠「跳过」往前走。
  val showSubmit = canSubmit || !hasOptions
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = onSkip) { Text("跳过") }
    Spacer(Modifier.weight(1f))
    if (showSubmit) {
      TextButton(enabled = canSubmit, onClick = onSubmit) { Text(if (isLast) "提交" else "继续") }
    }
  }
}

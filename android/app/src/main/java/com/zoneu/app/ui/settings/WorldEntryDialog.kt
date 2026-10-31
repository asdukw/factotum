package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val worldFieldLabels = mapOf(
  "keys" to "主关键词（每行一个）",
  "content" to "正文",
  "order" to "插入顺序",
  "depth" to "历史深度（0 表示末尾）",
  "secondary" to "次要关键词（每行一个）",
  "secondary_logic" to "次要逻辑 ANY / ALL / NOT_ANY / NOT_ALL",
  "exclude" to "排除词（每行一个）",
  "probability" to "概率（0–100）",
  "sticky" to "粘住轮数",
  "cooldown" to "冷却轮数",
  "position" to "位置 0 人设前 / 1 人设后 / 4 历史深度",
  "role" to "角色 0 system / 1 user / 2 assistant",
)
private val worldSwitchLabels = mapOf(
  "enabled" to "条目启用",
  "constant" to "常驻（仍受预算和排除规则影响）",
  "use_probability" to "按概率判定",
  "match_whole_words" to "英文整词匹配",
  "case_sensitive" to "区分大小写",
  "prevent_recursion" to "正文不触发递归",
  "exclude_recursion" to "不被递归触发",
)

@Composable
internal fun WorldEntryDialog(
  initial: WorldEntryForm,
  busy: Boolean,
  error: String?,
  onDismiss: () -> Unit,
  onSave: (WorldEntryForm) -> Unit,
) {
  var form by remember(initial.entry.id) { mutableStateOf(initial) }
  var invalid by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("世界书条目") },
    text = {
      Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        Text("只修改实际改变的字段。未支持能力仅保留；启用开关不会让正则或脚本生效。")
        form.values.forEach { (key, value) ->
          if ((form.original[key] as? JsonPrimitive)?.booleanOrNull != null) {
            WorldSwitch(worldSwitchLabels[key] ?: key, value == "true", !busy) { form = form.changed(key, it.toString()) }
          } else {
            WorldField(worldFieldLabels[key] ?: key, value) { form = form.changed(key, it) }
          }
        }
        (invalid ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(enabled = !busy, onClick = {
        runCatching { form.patch() }.onSuccess { onSave(form) }.onFailure { invalid = "数值或字段无效；草稿仍保留。" }
      }) { Text("保存条目") }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
  )
}

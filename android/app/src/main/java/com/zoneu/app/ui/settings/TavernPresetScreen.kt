package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.agent.TavernPreset

/**
 * 酒馆预设：两段可编辑的 prompt 骨架。
 *
 * 页面的顺序就是用的顺序 —— 先选哪套生效，再改它的文本，最后看渲染出来的样子。
 * 内置那套永远在列表里、永远改不坏：动它只能「另存」，这样「恢复默认」才有意义。
 */
@Composable
fun TavernPresetScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: TavernPresetViewModel = viewModel { TavernPresetViewModel(appContext) }
  val state by viewModel.state.collectAsState()

  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "酒馆预设", onBack = onBack)
      LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
      ) {
        item {
          Text(
            "这两段决定模型怎么演：系统模板给设定，后置指令收尾（放在对话历史之后，压住最后一句的语气）。" +
              "改完点保存，下一条消息就生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        items(state.presets, key = { it.id }) { preset ->
          PresetRow(
            preset = preset,
            selected = preset.id == state.selectedId,
            onSelect = { viewModel.select(preset.id) },
            onEdit = { viewModel.edit(preset.id) },
          )
        }
        presetActions(state, viewModel)
        presetEditor(state, viewModel)
        presetPreview(state)
      }
    }
  }
}

/** 列表下方的操作行：另存 / 恢复默认文本 / 删除（内置那套不给删）。 */
private fun LazyListScope.presetActions(state: TavernPresetViewModel.UiState, viewModel: TavernPresetViewModel) {
  item {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = { viewModel.saveAsCopy() }) { Text("另存为新预设") }
      TextButton(onClick = { viewModel.resetDraft() }) { Text("编辑区恢复默认") }
      if (!state.editingBuiltIn) {
        TextButton(onClick = { viewModel.delete() }) { Text("删除当前") }
      }
    }
  }
  item {
    Text(
      if (state.editingBuiltIn) "当前编辑的是内置预设：保存会另存成一套新的，内置那套保持原样。" else "当前编辑：自定义预设。",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 数字步进：预算这类值用 −/＋ 比键盘输入快，也不会输入越界值。 */
@Composable
private fun BudgetRow(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
    Text("$value", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("＋") }
  }
}

/** 两段正文的编辑区 + 变量说明 + 保存。 */
private fun LazyListScope.presetEditor(state: TavernPresetViewModel.UiState, viewModel: TavernPresetViewModel) {
  item {
    OutlinedTextField(
      value = state.system,
      onValueChange = { viewModel.updateSystem(it) },
      label = { Text("系统模板") },
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
    )
  }
  item {
    OutlinedTextField(
      value = state.postHistory,
      onValueChange = { viewModel.updatePostHistory(it) },
      label = { Text("后置指令") },
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
    )
  }
  item {
    // 递归层数：这条旋钮只在「设定里还会提到别的设定」的卡上有用，所以紧挨着两段正文放，
    // 并写清代价 —— 层数一高，一轮可能带进四五条互相牵连的设定。
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("世界书递归层数", style = MaterialTheme.typography.bodyMedium)
        Text(
          "命中的设定正文里提到别的设定，最多再追几层。0 = 不追。层数高会让一轮带进更多设定。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { viewModel.updateRecursionDepth(state.recursionDepth - 1) }) { Text("−") }
      Text("${state.recursionDepth}", style = MaterialTheme.typography.bodyMedium)
      TextButton(onClick = { viewModel.updateRecursionDepth(state.recursionDepth + 1) }) { Text("＋") }
    }
  }
  item {
    // 预算与扫描窗：文档里说「预设可调」，这里就必须真的能调。
    BudgetRow("扫描窗口（消息条数）", state.scanDepth, 0, 12) { viewModel.updateBudget("scan", it) }
    BudgetRow("一轮最多命中条目", state.worldEntries, 1, 20) { viewModel.updateBudget("entries", it) }
    BudgetRow("命中条目字符预算", state.worldChars, 200, 8_000, step = 200) { viewModel.updateBudget("world", it) }
    BudgetRow("常驻条目字符预算", state.constantChars, 0, 8_000, step = 200) { viewModel.updateBudget("constant", it) }
    BudgetRow("历史裁剪字符预算", state.historyChars, 500, 20_000, step = 500) { viewModel.updateBudget("history", it) }
  }
  item {
    Text(SLOT_HELP, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  item {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { viewModel.save() }) { Text(if (state.dirty) "保存（有未保存的改动）" else "保存") }
      if (state.dirty) {
        Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
      }
    }
  }
  state.message?.let { message ->
    item {
      Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

/** 渲染结果：用第一张卡，条件块与开场白都能看见。 */
private fun LazyListScope.presetPreview(state: TavernPresetViewModel.UiState) {
  item { Text("预览（用第一张卡渲染，含本轮世界书命中）", style = MaterialTheme.typography.titleSmallEmphasized) }
  item {
    Text(
      state.preview.ifBlank { "没有可用的角色卡，预览为空" },
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PresetRow(
  preset: TavernPreset,
  selected: Boolean,
  onSelect: () -> Unit,
  onEdit: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onSelect)
    Column(Modifier.weight(1f)) {
      Text(preset.name, style = MaterialTheme.typography.bodyMedium)
      Text(
        if (preset.builtIn) "内置 · 点「另存为新预设」来改" else "自定义",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TextButton(onClick = onEdit) { Text("编辑") }
  }
}

private const val SLOT_HELP =
  "可用变量：{{char}} {{user}} {{description}} {{personality}} {{scenario}} {{first_mes}} " +
    "{{world}} {{world_before}} {{world_after}} {{memory}}；" +
    "条件块 {{#变量}}…{{/变量}} 在变量为空时整段不出现（{{#first}} 只在第一轮出现，{{#lore}} 在有世界书命中时出现）。"

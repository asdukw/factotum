package com.zoneu.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookEdits
import com.zoneu.app.agent.WorldBookEntry
import com.zoneu.app.agent.WorldBookFormat
import com.zoneu.app.agent.WorldUsage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun WorldBookDetail(ui: WorldBookUi, id: String, onDeleted: () -> Unit) {
  var book by remember(id) { mutableStateOf<WorldBook?>(null) }
  var usage by remember(id) { mutableStateOf(WorldUsage()) }
  var editing by remember(id) { mutableStateOf(false) }
  var entry by remember(id) { mutableStateOf<WorldEntryForm?>(null) }
  var adding by remember(id) { mutableStateOf(false) }
  var deleting by remember(id) { mutableStateOf(false) }
  var reloading by remember(id) { mutableStateOf(false) }
  LoadWorldBook(ui, id) { latest, refs ->
    book = latest
    usage = refs
  }
  val current = book ?: return MissingWorldBook()
  val exporter = rememberWorldExporter(ui, current)

  fun save(next: WorldBook, done: () -> Unit = {}) {
    ui.run {
      book = ui.repository.update(next, current.revision)
      ui.refresh()
      usage = ui.repository.usage(id)
      done()
      ui.message = "已保存。已有会话继续使用原共享修订，须主动升级。"
    }
  }
  WorldBookSummary(
    ui,
    current,
    usage,
    onEdit = { editing = true },
    onExport = { exporter.launch("世界书.json") },
    save = { save(it) },
    onAdd = {
      adding = true
      entry = WorldEntryForm(WorldBookEntry())
    },
    onReload = { reloading = true },
    onDelete = { deleting = true },
  )
  WorldBookEntries(
    current,
    !ui.busy,
    save = { save(it) },
    edit = {
      adding = false
      entry = WorldEntryForm(it)
    },
    remove = {
      entry = WorldEntryForm(it)
      deleting = true
    },
  )
  if (editing) WorldHeaderDialog(current, { editing = false }, ui.message) { next -> save(next) { editing = false } }
  val form = entry
  if (form != null && !deleting) {
    WorldEntryDialog(form, ui.busy, ui.message, { entry = null }) { updated ->
      save(worldEntryEdit(current, updated, adding)) { entry = null }
    }
  }
  if (deleting) {
    WorldDeleteConfirmation(
      current,
      form,
      ui,
      onDismiss = {
        deleting = false
        entry = null
      },
      onDeleted = onDeleted,
      save = { next ->
        save(next) {
          deleting = false
          entry = null
        }
      },
    )
  }
  if (reloading) {
    WorldReloadConfirmation(ui, id, onDismiss = { reloading = false }) { latest, refs ->
      book = latest
      usage = refs
      editing = false
      entry = null
      reloading = false
    }
  }
}

@Composable
private fun MissingWorldBook() {
  Text("正在读取，或这本书已不存在。")
}

@Composable
private fun LoadWorldBook(ui: WorldBookUi, id: String, onRead: (WorldBook, WorldUsage) -> Unit) {
  LaunchedEffect(id) {
    androidx.compose.runtime.snapshotFlow { ui.busy }.first { !it }
    ui.run { onRead(ui.repository.read(id), ui.repository.usage(id)) }
  }
}

@Composable
private fun WorldReloadConfirmation(
  ui: WorldBookUi,
  id: String,
  onDismiss: () -> Unit,
  onRead: (WorldBook, WorldUsage) -> Unit,
) {
  WorldConfirm("放弃草稿并重新读取？", "工具或其他页面可能已修改共享书。只有明确确认才放弃当前编辑。", onDismiss) {
    ui.run { onRead(ui.repository.read(id), ui.repository.usage(id)) }
  }
}

@Composable
private fun rememberWorldExporter(ui: WorldBookUi, current: WorldBook): androidx.activity.result.ActivityResultLauncher<String> {
  val context = LocalContext.current
  return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    if (uri != null) {
      ui.run {
        writeWorldDocument(context, uri, WorldBookFormat.exportText(current))
        ui.message = "已导出所示修订"
      }
    }
  }
}

@Composable
private fun WorldBookSummary(
  ui: WorldBookUi,
  current: WorldBook,
  usage: WorldUsage,
  onEdit: () -> Unit,
  onExport: () -> Unit,
  save: (WorldBook) -> Unit,
  onAdd: () -> Unit,
  onReload: () -> Unit,
  onDelete: () -> Unit,
) {
  Text(current.name, style = MaterialTheme.typography.headlineSmallEmphasized)
  Text("修订 ${current.revision}", style = MaterialTheme.typography.labelSmall)
  Text("默认引用：${usage.characterIds.size} 个角色；固定引用：${usage.sessionIds.size} 段会话。修改影响未来会话，不自动升级旧会话。")
  Text(current.description)
  Row {
    TextButton(onClick = { onEdit() }, enabled = !ui.busy) { Text("编辑书头") }
    TextButton(onClick = { onExport() }, enabled = !ui.busy) { Text("导出 JSON") }
    TextButton(onClick = {
      ui.run {
        ui.repository.copy(current.id, current.revision)
        ui.message = "已复制；可返回书库查看副本"
        ui.refresh()
      }
    }, enabled = !ui.busy) {
      Text("复制")
    }
  }
  WorldSwitch("书级启用（只变更新修订）", current.enabled, !ui.busy) { save(current.copy(enabled = it)) }
  Row {
    TextButton(onClick = {
      onAdd()
    }, enabled = !ui.busy) { Text("添加条目") }
    TextButton(onClick = { onReload() }, enabled = !ui.busy) { Text("重新读取") }
    TextButton(onClick = { onDelete() }, enabled = !ui.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
  }
  current.archive?.notes.orEmpty().forEach { Text("兼容报告：$it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun WorldBookEntries(
  current: WorldBook,
  enabled: Boolean,
  save: (WorldBook) -> Unit,
  edit: (WorldBookEntry) -> Unit,
  remove: (WorldBookEntry) -> Unit,
) {
  current.entries.forEachIndexed { index, item ->
    SettingsGroup {
      Column(Modifier.padding(12.dp)) {
        Text("${index + 1} · ${item.rule.keys.joinToString("、").ifBlank { "无关键词" }}")
        Text(item.rule.content.take(160) + if (item.rule.content.length > 160) "…（预览）" else "")
        if (item.unsupported.isNotEmpty()) Text("仅保留：${item.unsupported.joinToString()}；启用开关不会赋予未支持能力。")
        WorldSwitch("条目启用", item.enabled, enabled) { checked ->
          save(current.copy(entries = current.entries.map { if (it.id == item.id) it.copy(enabled = checked) else it }))
        }
        Row {
          TextButton(onClick = { edit(item) }, enabled = enabled) { Text("编辑") }
          TextButton(onClick = { save(moveWorldEntry(current, item.id, index - 1)) }, enabled = enabled && index > 0) { Text("上移") }
          TextButton(
            onClick = {
              save(
                moveWorldEntry(
                  current,
                  item.id,
                  index + 1,
                ),
              )
            },
            enabled = enabled && index < current.entries.lastIndex,
          ) {
            Text("下移")
          }
          TextButton(onClick = { remove(item) }, enabled = enabled) { Text("移除") }
        }
      }
    }
  }
}

@Composable
private fun WorldDeleteConfirmation(
  current: WorldBook,
  form: WorldEntryForm?,
  ui: WorldBookUi,
  onDismiss: () -> Unit,
  onDeleted: () -> Unit,
  save: (WorldBook) -> Unit,
) {
  WorldConfirm(
    title = if (form == null) "删除共享世界书？" else "移除这个条目？",
    text = if (form == null) {
      "任何角色或会话引用（包括停用引用）都会阻止删除。删除后重复导入不会复活此资产。"
    } else {
      "生成新修订；旧会话仍保留原修订。导出新修订不会从原文恢复已移除条目。"
    },
    onDismiss = onDismiss,
  ) {
    if (form == null) {
      ui.run {
        ui.repository.delete(current.id, current.revision)
        ui.refresh()
        onDeleted()
      }
    } else {
      save(current.copy(entries = current.entries.filterNot { it.id == form.entry.id }))
    }
  }
}

internal fun moveWorldEntry(book: WorldBook, id: String, index: Int): WorldBook = WorldBookEdits.apply(
  book,
  entryOps = JsonArray(
    listOf(
      buildJsonObject {
        put("op", "move")
        put("entry_id", id)
        put("index", index)
      },
    ),
  ),
)

@Composable
internal fun WorldConfirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text("确认") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

@Composable
internal fun WorldSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Switch(checked, onChange, enabled = enabled)
  }
}

@Composable
internal fun WorldHeaderDialog(book: WorldBook, onDismiss: () -> Unit, error: String? = null, onSave: (WorldBook) -> Unit) {
  var draft by remember(book.id, book.revision) { mutableStateOf(WorldBookDraft(book)) }
  var invalid by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("共享书头") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      WorldField("名称", draft.name) { draft = draft.copy(name = it) }
      WorldField("说明", draft.description) { draft = draft.copy(description = it) }
      WorldField("分类", draft.category) { draft = draft.copy(category = it) }
      WorldField("标签（每行一个）", draft.tags) { draft = draft.copy(tags = it) }
      WorldSwitch("启用", draft.enabled) { draft = draft.copy(enabled = it) }
      WorldField("扫描深度（空 = 应用默认）", draft.scanDepth) { draft = draft.copy(scanDepth = it) }
      WorldField("本书字符预算（空 = 不限制）", draft.tokenBudget) { draft = draft.copy(tokenBudget = it) }
      WorldSwitch("允许递归扫描", draft.recursiveScanning) { draft = draft.copy(recursiveScanning = it) }
      (invalid ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = {
    TextButton(onClick = {
      runCatching { draft.build() }.onSuccess(onSave).onFailure { invalid = "字段无效，草稿仍保留" }
    }) { Text("保存新修订") }
  }, dismissButton = { TextButton(onClick = onDismiss) { Text("保留原书") } })
}

@Composable
internal fun WorldField(label: String, value: String, onChange: (String) -> Unit) {
  OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
}

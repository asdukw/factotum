package com.zoneu.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookFormat
import com.zoneu.app.agent.worldBookRepository

/** Portable inclusion is an explicit one-shot snapshot; normal card export remains unchanged. */
@Composable
internal fun SharedWorldCardExport(card: CharacterCard) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val ui = remember(card.id) { WorldBookUi(context.worldBookRepository(), scope) }
  var open by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<List<WorldBook>>(emptyList()) }
  var text by remember { mutableStateOf<String?>(null) }
  val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
    val frozen = text
    if (uri != null && frozen != null) {
      ui.run {
        writeWorldDocument(context, uri, frozen)
        text = null
        ui.notice("已导出便携卡片快照")
      }
    }
  }
  TextButton(enabled = !ui.busy, onClick = {
    ui.run {
      ui.refresh()
      selected = emptyList()
      open = true
    }
  }) {
    Text("选择共享修订并嵌入卡片导出")
  }
  ui.message?.let { Text(it) }
  if (open) {
    AlertDialog(onDismissRequest = { open = false }, title = { Text("冻结共享世界书") }, text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("仅本次导出复制所选修订；不会创建绑定或改变本地卡。资源只保留引用，不下载。")
        ui.books.forEach { book ->
          Row {
            Checkbox(
              book.id in selected.map { it.id },
              { checked -> selected = if (checked) selected + book else selected.filterNot { it.id == book.id } },
            )
            Text("${book.name} · ${book.revision}")
          }
        }
      }
    }, confirmButton = {
      TextButton(enabled = selected.isNotEmpty(), onClick = {
        ui.run {
          text = WorldBookFormat.portableCard(card, selected)
          open = false
          exporter.launch("角色卡-共享世界快照.json")
        }
      }) { Text("导出所选修订") }
    }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } })
  }
}

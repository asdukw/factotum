package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.compatibilityNotes

/** 报告只展示处理类型与字段路径，绝不展开原始指令或扩展正文。 */
@Composable
internal fun CardCompatibilityReport(card: CharacterCard) {
  var open by remember(card.id) { mutableStateOf(false) }
  TextButton(onClick = { open = true }) { Text("导入兼容报告") }
  if (open) {
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text("导入兼容报告") },
      text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
          card.compatibilityNotes().forEach { note ->
            Text(note.summary(), style = MaterialTheme.typography.bodyMedium)
          }
        }
      },
      confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
  }
}

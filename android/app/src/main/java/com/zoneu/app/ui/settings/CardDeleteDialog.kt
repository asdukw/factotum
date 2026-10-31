package com.zoneu.app.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 删卡确认。
 *
 * 删除会同时带走角色记忆与默认共享世界书绑定，且没有撤销；列表里的按钮又紧挨着打开行，
 * 所以两个入口共用同一段文案，避免一个入口说清楚了、另一个没说。
 */
@Composable
internal fun ConfirmDeleteCardDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("删除角色卡？") },
    text = {
      Text(
        "将永久删除「$name」及其角色记忆、默认共享世界书绑定。" +
          "已聊过的会话会保留，但会标成「已删除的角色」。此操作无法撤销。",
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

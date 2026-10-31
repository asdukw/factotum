package com.zoneu.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zoneu.app.agent.CardInput
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookException
import com.zoneu.app.agent.WorldBookFormat
import com.zoneu.app.agent.WorldBookRepository
import com.zoneu.app.agent.worldBookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorldBookScreen(onBack: () -> Unit, initialBookId: String? = null) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val ui = remember { WorldBookUi(context.worldBookRepository(), scope) }
  var selected by remember(initialBookId) { mutableStateOf(initialBookId) }
  LifecycleResumeEffect(Unit) {
    if (selected == null) ui.run { ui.refresh() }
    onPauseOrDispose { }
  }
  val id = selected
  val goBack: () -> Unit = { if (id == null) onBack() else selected = null }
  WorldPage(if (id == null) "世界书库" else "共享世界书", goBack) {
    ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (ui.busy) Text("正在处理…")
    if (id == null) {
      WorldLibrary(ui, context) { selected = it }
    } else {
      WorldBookDetail(ui, id, onDeleted = { selected = null })
    }
  }
}

@Composable
internal fun WorldPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
  Surface(Modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title, onBack)
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) { content() }
    }
  }
}

@Composable
private fun WorldLibrary(ui: WorldBookUi, context: Context, onOpen: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  var category by remember { mutableStateOf<String?>(null) }
  var creating by remember { mutableStateOf(false) }
  val newBook = remember(creating) { WorldBook() }
  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      ui.run {
        val text = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { String(CardInput.read(it), Charsets.UTF_8) }
            ?: throw WorldBookException("WORLD_IMPORT_FAILED")
        }
        val saved = ui.repository.create(WorldBookFormat.importText(text))
        ui.refresh()
        onOpen(saved.id)
      }
    }
  }
  Text("独立保存，可由多个角色共享。默认绑定只影响新会话，已有会话固定共享书修订；卡内设定仍随角色卡更新。")
  OutlinedTextField(query, { query = it }, label = { Text("搜索名称、标签或分类") }, modifier = Modifier.fillMaxWidth())
  Row {
    TextButton(onClick = { category = null }) { Text("全部") }
    ui.books.map { it.category }.filter { it.isNotBlank() }.distinct().sorted().forEach { value ->
      TextButton(onClick = { category = value }) { Text(value) }
    }
  }
  Row {
    Button(onClick = { creating = true }, enabled = !ui.busy) { Text("创建世界书") }
    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !ui.busy) {
      Text("导入 JSON")
    }
    TextButton(onClick = { ui.run { ui.refresh() } }, enabled = !ui.busy) { Text("刷新") }
  }
  ui.books.filter { book ->
    (category == null || book.category == category) &&
      (listOf(book.name, book.category) + book.tags).any { it.contains(query, ignoreCase = true) }
  }.sortedBy { it.name }.forEach { book ->
    SettingsGroup {
      SettingsRow(
        GlyphKind.Sparkles,
        book.name,
        "${book.entries.size} 条 · ${if (book.enabled) "启用" else "停用"} · ${book.category} ${book.tags.joinToString("、")}",
        onClick = { onOpen(book.id) },
      ) { ChevronRight() }
    }
  }
  if (ui.books.isEmpty()) Text("还没有共享世界书。角色卡里的旧设定不会自动搬进来。")
  if (creating) {
    WorldHeaderDialog(newBook, onDismiss = { creating = false }) { draft ->
      ui.run {
        val saved = ui.repository.create(draft)
        ui.refresh()
        creating = false
        onOpen(saved.id)
      }
    }
  }
}

internal suspend fun writeWorldDocument(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
  context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    ?: throw WorldBookException("WORLD_EXPORT_FAILED")
}

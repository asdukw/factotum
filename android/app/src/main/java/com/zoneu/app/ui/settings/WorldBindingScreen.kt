package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookBinding
import com.zoneu.app.agent.WorldManifest
import com.zoneu.app.agent.WorldOverride
import com.zoneu.app.agent.WorldRoleBindings
import com.zoneu.app.agent.WorldRuntime
import com.zoneu.app.agent.worldBookRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Character defaults and pinned chat configuration are deliberately separate editors. */
@Composable
fun WorldBindingScreen(scope: String, targetId: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val ui = remember { WorldBookUi(context.worldBookRepository(), coroutineScope) }
  var role by remember(targetId) { mutableStateOf(WorldRoleBindings(targetId)) }
  var manifest by remember(targetId) { mutableStateOf(WorldManifest(targetId)) }
  var selected by remember(targetId) { mutableStateOf<List<String>>(emptyList()) }
  var loaded by remember(targetId) { mutableStateOf(false) }
  var discard by remember { mutableStateOf(false) }

  fun reload() = ui.run {
    ui.refresh()
    if (scope == "character") {
      role = ui.repository.roleBindings(targetId)
      selected = role.bookIds
    } else {
      manifest = ui.repository.manifest(targetId) ?: WorldManifest(targetId)
    }
    loaded = true
  }
  LaunchedEffect(scope, targetId) { reload() }
  WorldPage(if (scope == "character") "角色默认世界书" else "会话世界配置", onBack) {
    ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!loaded) Text("正在读取…")
    WorldBindingDescription(scope)
    TextButton(onClick = { discard = true }, enabled = !ui.busy) { Text("重新读取（放弃草稿需确认）") }
    if (loaded && scope == "character") {
      WorldRoleSelection(ui, selected) { selected = it }
      TextButton(enabled = !ui.busy, onClick = {
        ui.run {
          role = ui.repository.setRoleBindings(role.copy(bookIds = selected), role.revision)
          ui.message = "已保存未来会话的默认绑定"
        }
      }) { Text("保存角色默认绑定") }
    }
    if (loaded && scope == "session") {
      WorldSessionBindings(ui, manifest) { manifest = it }
    }
  }
  if (discard) {
    WorldConfirm("放弃绑定草稿并重读？", "不会把旧草稿自动套用到新修订。", { discard = false }) {
      discard = false
      reload()
    }
  }
}

@Composable
private fun WorldRoleSelection(ui: WorldBookUi, selected: List<String>, onSelect: (List<String>) -> Unit) {
  ui.books.forEach { book ->
    Row {
      Checkbox(book.id in selected, { checked ->
        onSelect(if (checked) selected + book.id else selected - book.id)
      }, enabled = !ui.busy)
      Text(book.name, Modifier.padding(top = 12.dp))
    }
  }
}

@Composable
private fun WorldSessionBindings(ui: WorldBookUi, manifest: WorldManifest, onManifest: (WorldManifest) -> Unit) {
  val context = LocalContext.current
  val targetId = manifest.sessionId
  var testText by remember(targetId) { mutableStateOf("") }
  var explanation by remember(targetId) { mutableStateOf<JsonObject?>(null) }
  manifest.books.forEach { binding ->
    val head = ui.books.find { it.id == binding.bookId }
    WorldPinnedBook(binding, head, !ui.busy) { operation ->
      ui.run {
        val books = when (operation) {
          "remove" -> manifest.books.filterNot { it.bookId == binding.bookId }
          "upgrade" -> manifest.books.map { if (it.bookId == binding.bookId) it.copy(revision = checkNotNull(head).revision) else it }
          else -> manifest.books.map { if (it.bookId == binding.bookId) it.copy(override = WorldOverride.valueOf(operation)) else it }
        }
        onManifest(ui.repository.setManifest(manifest.copy(books = books), manifest.revision))
      }
    }
  }
  ui.books.filter { book -> manifest.books.none { it.bookId == book.id } }.forEach { book ->
    TextButton(enabled = !ui.busy, onClick = {
      ui.run {
        val added = WorldBookBinding(book.id, book.revision, listOf("session:$targetId"))
        onManifest(ui.repository.setManifest(manifest.copy(books = manifest.books + added), manifest.revision))
      }
    }) { Text("添加 ${book.name}（固定当前修订）") }
  }
  OutlinedTextField(testText, { testText = it }, label = { Text("只读激活测试文本") })
  TextButton(enabled = !ui.busy, onClick = { ui.run { explanation = WorldRuntime.explain(context, targetId, testText) } }) {
    Text("查看激活解释（不调用模型）")
  }
  explanation?.let { WorldExplanation(it) }
  WorldRequestRecovery(targetId, ui)
}

@Composable
private fun WorldPinnedBook(binding: WorldBookBinding, head: WorldBook?, enabled: Boolean, onChange: (String) -> Unit) {
  var pending by remember(binding) { mutableStateOf<String?>(null) }
  SettingsGroup {
    Column(Modifier.padding(12.dp)) {
      Text(head?.name ?: binding.bookId, style = MaterialTheme.typography.titleMediumEmphasized)
      Text("使用修订 ${binding.revision}")
      Text("来源：${binding.sources.joinToString()}；当前 ${binding.override}")
      Row {
        WorldOverride.entries.forEach { value ->
          TextButton(enabled = enabled, onClick = { pending = value.name }) {
            Text(
              when (value) {
                WorldOverride.INHERIT -> "继承"
                WorldOverride.ENABLE -> "启用"
                WorldOverride.DISABLE -> "停用"
              },
            )
          }
        }
      }
      if (head != null && head.revision != binding.revision) {
        TextButton(
          enabled = enabled,
          onClick = { pending = "upgrade" },
        ) { Text("有新版本：显式升级") }
      }
      TextButton(enabled = enabled, onClick = { pending = "remove" }) { Text("解绑本会话（移除所有来源）") }
    }
  }
  pending?.let { operation ->
    WorldConfirm("修改会话世界配置？", "只改变本会话；受影响书的粘性与冷却重新计时。书或条目禁用、未支持能力不能被本会话启用绕过。", { pending = null }) {
      pending = null
      onChange(operation)
    }
  }
}

@Composable
private fun WorldExplanation(value: JsonObject) {
  val decisions = value["decisions"] as? JsonArray ?: return
  Text("解释不展开宏、不抽概率、不写计时；概率条目只表示生成时待判定。")
  decisions.forEach { row ->
    val item = row as? JsonObject ?: return@forEach

    fun text(key: String) = (item[key] as? JsonPrimitive)?.content.orEmpty()
    Text(
      "${text("book_id")} / ${text("entry_id")} · ${text("reason")} · ${text("chars")} 字符 · 入选 ${text("selected")}",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun WorldBindingDescription(scope: String) {
  Text(
    if (scope == "character") {
      "仅影响未来新会话；已有会话不会自动获得这些书或升级。"
    } else {
      "共享书固定在下列修订。角色卡内设定仍随卡编辑更新，不是整个世界的历史快照。停用优先于所有继承来源。"
    },
  )
}

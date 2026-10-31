package com.zoneu.app.ui.chat

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.worldBookRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal data class WorldNavigation(
  val openLibrary: () -> Unit = {},
  val openBook: (String) -> Unit = {},
  val openBindings: (String, String) -> Unit = { _, _ -> },
)

internal val LocalWorldNavigation = staticCompositionLocalOf { WorldNavigation() }

/** Navigation is derived only from a matching successful tool receipt, never model prose. */
internal fun worldBookResultId(call: ToolCallView): String? {
  if (call.state != ToolCallState.Ok || call.name !in setOf("create_world_book", "update_world_book", "copy_world_book")) return null
  val value = runCatching { Json.parseToJsonElement(call.result.orEmpty()) as? JsonObject }.getOrNull() ?: return null
  val ok = (value["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
  val kind = (value["kind"] as? JsonPrimitive)?.content
  if (ok != true || kind != "zoneu.world-book.result.v1") return null
  val id = (value["book_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
  return id.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}").matches(it) }
}

@Composable
internal fun WorldBookResultActions(calls: List<ToolCallView>) {
  val context = LocalContext.current
  val navigation = LocalWorldNavigation.current
  val scope = rememberCoroutineScope()
  calls.mapNotNull(::worldBookResultId).distinct().forEach { id ->
    val book by produceState<WorldBook?>(null, id, calls) {
      value = runCatching { context.worldBookRepository().read(id) }.getOrNull()
    }
    TextButton(onClick = {
      scope.launch {
        if (runCatching { context.worldBookRepository().read(id) }.isSuccess) {
          navigation.openBook(id)
        } else {
          Toast.makeText(context, "世界书已删除、不存在或暂时无法读取", Toast.LENGTH_SHORT).show()
        }
      }
    }) { Text(book?.let { "查看世界书 · ${it.name}" } ?: "查看世界书（已删除或不存在）") }
  }
}

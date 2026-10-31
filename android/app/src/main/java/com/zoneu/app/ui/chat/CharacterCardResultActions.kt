package com.zoneu.app.ui.chat

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.zoneu.app.agent.CharacterCardOperations
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 只解析真实工具成功回执，不从模型正文或 Markdown 识别内部导航。 */
internal fun characterCardResultId(call: ToolCallView): String? {
  val supported = call.name in setOf("create_character_card", "import_character_card_url", "update_character_card")
  if (!supported || call.state != ToolCallState.Ok) return null
  val result = runCatching { Json.parseToJsonElement(call.result.orEmpty()) as? JsonObject }.getOrNull() ?: return null
  val kind = (result["kind"] as? JsonPrimitive)?.content
  val ok = (result["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
  if (kind != CharacterCardOperations.RESULT_KIND || ok != true) return null
  return (result["characterId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) }
}

@Composable
internal fun CharacterCardResultActions(calls: List<ToolCallView>, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  calls.mapNotNull(::characterCardResultId).distinct().forEach { id ->
    val card by produceState<com.zoneu.app.agent.CharacterCard?>(null, id, calls) {
      value = withContext(Dispatchers.IO) { runCatching { CharacterStore.byId(context, id) }.getOrNull() }
    }
    TextButton(onClick = {
      if (runCatching { CharacterStore.byId(context, id) }.getOrNull() == null) {
        Toast.makeText(context, "这张角色卡已删除、不存在或暂时无法读取", Toast.LENGTH_SHORT).show()
      } else {
        onOpen(id)
      }
    }) { Text(card?.let { "查看角色卡 · ${it.name}" } ?: "查看角色卡（已删除或不存在）") }
  }
}

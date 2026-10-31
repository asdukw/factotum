package com.zoneu.app.bridge

import android.content.Context
import com.zoneu.app.agent.WorldBookLibrary
import com.zoneu.app.agent.WorldRuntime
import com.zoneu.app.device.DeviceAction
import com.zoneu.app.device.DeviceActionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object WorldBookActions {
  fun execute(context: Context, action: DeviceAction): DeviceActionResult? = when (action) {
    is DeviceAction.WorldBookOperation -> runBlocking {
      WorldBookLibrary.forContext(context).handle(action.name, action.requestId, action.arguments, action.fingerprint)
    }

    is DeviceAction.WorldRuntimeAction -> runBlocking { WorldRuntime.handleInternal(context, action.name, action.arguments) }

    else -> null
  }
}

/** Transport completeness only; the management/runtime layers still validate exact arguments. */
internal object WorldBookActionParser {
  private val writes = setOf("create_world_book", "update_world_book", "copy_world_book", "delete_world_book", "update_world_book_bindings")
  private val reads = mapOf(
    "list_world_books" to emptySet(),
    "read_world_book" to setOf("book_id", "section"),
    "read_world_book_bindings" to setOf("scope", "target_id"),
    "explain_world_activation" to setOf("session_id"),
  )

  fun parse(action: String, root: JsonObject): DeviceAction? {
    val args = JsonObject(root - setOf("action", "request_id", "preview_fingerprint"))
    return when {
      action in setOf("bind_world_request", "resolve_world_request") -> parseRuntime(action, root)

      action in reads -> if (reads.getValue(action).all { !string(root, it).isNullOrBlank() }) {
        DeviceAction.WorldBookOperation(action, null, args)
      } else {
        null
      }

      action in writes || action in setOf("preview_world_book_action", "cancel_world_book_action") -> parseWrite(action, root, args)

      else -> null
    }
  }

  private fun parseRuntime(action: String, root: JsonObject): DeviceAction? {
    val valid = setOf("request_id", "dsh_session_id", "trigger_message_id").all { key ->
      string(root, key)?.let { it.isNotBlank() && it.length <= 512 } == true
    }
    val turn = (root["turn"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val validTurn = action != "resolve_world_request" || (turn != null && turn > 0)
    return if (valid && validTurn) DeviceAction.WorldRuntimeAction(action, JsonObject(root - "action")) else null
  }

  private fun parseWrite(action: String, root: JsonObject, args: JsonObject): DeviceAction? {
    val request = string(root, "request_id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
    val valid = when (action) {
      "cancel_world_book_action" -> !string(root, "parameters").isNullOrBlank()
      "preview_world_book_action" -> string(root, "operation")?.let { it in writes && complete(it, args) } == true
      else -> complete(action, args)
    }
    if (!valid) return null
    val fingerprint = if (action in writes) string(root, "preview_fingerprint")?.takeIf { it.isNotBlank() } else null
    if (action in writes && fingerprint == null) return null
    return DeviceAction.WorldBookOperation(action, request, args, fingerprint)
  }

  private fun complete(action: String, args: JsonObject): Boolean = when (action) {
    "create_world_book" -> args["fields"] is JsonObject
    "update_world_book_bindings" -> setOf("scope", "target_id", "expected_revision").all { !string(args, it).isNullOrBlank() }
    else -> setOf("book_id", "expected_revision").all { !string(args, it).isNullOrBlank() }
  }

  private fun string(value: JsonObject, key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

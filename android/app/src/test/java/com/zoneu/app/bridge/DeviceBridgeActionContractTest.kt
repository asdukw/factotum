package com.zoneu.app.bridge

import com.zoneu.app.device.DeviceAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥的 action 契约：`KNOWN_ACTIONS` 与 [DeviceBridgeServer.toDeviceAction] 的 `when` 必须一致。
 *
 * 为什么值得盯：插件与 App 版本错配时，桥只回一句「未知或参数不全」，模型分不清那是不可修的
 * 契约错误还是自己能补的参数缺失 —— 实测它会对着同一个错误连撞 6 次。所以两个集合一旦漂移，
 * 这里先红。
 */
class DeviceBridgeActionContractTest {
  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  /** 每个 known action 配一份最小合法参数；新增 action 时必须在这里补一行。 */
  private val minimalParams: Map<String, String> =
    mapOf(
      "read_ui_tree" to "{}",
      "tap_node" to """{"ref":1}""",
      "tap_point" to """{"x":1,"y":1}""",
      "long_press" to """{"ref":1}""",
      "type_text" to """{"text":"a"}""",
      "press" to """{"key":"back"}""",
      "press_back" to "{}",
      "launch_app" to """{"query":"clock"}""",
      "screenshot" to "{}",
      "capture_screenshot" to "{}",
      "tap_target" to """{"screenshot_id":"f1","x1":0,"y1":0,"x2":10,"y2":10}""",
      "long_press_target" to """{"screenshot_id":"f1","x1":0,"y1":0,"x2":10,"y2":10}""",
      "swipe" to """{"direction":"up"}""",
      "set_alarm" to """{"hour":7,"minute":30}""",
      "add_calendar_event" to """{"title":"t","begin":1,"end":2}""",
      "list_calendar_events" to """{"begin":1,"end":2}""",
      "update_calendar_event" to """{"event_id":1}""",
      "delete_calendar_event" to """{"event_id":1}""",
      "schedule_task" to """{"title":"t","prompt":"p","hour":8,"minute":0}""",
      "list_scheduled_tasks" to "{}",
      "update_scheduled_task" to """{"id":"t1"}""",
      "delete_scheduled_task" to """{"id":"t1"}""",
      "create_character_card" to """{"request_id":"test","fields":{"name":"角色","description":"人设"}}""",
      "import_character_card_url" to """{"request_id":"test","url":"https://example.com/card.json"}""",
      "list_character_cards" to """{}""",
      "read_character_card" to """{"character_id":"local-test","section":"basic"}""",
      "preview_character_card_update" to
        """{"request_id":"test","character_id":"local-test","expected_revision":"r1","fields":{"personality":"x"}}""",
      "update_character_card" to
        """{"request_id":"test","character_id":"local-test","expected_revision":"r1",
          "fields":{"personality":"x"},"preview_fingerprint":"fp"}""",
      "cancel_character_card" to """{"request_id":"test"}""",
      "list_world_books" to """{}""",
      "read_world_book" to """{"book_id":"book","section":"basic"}""",
      "create_world_book" to """{"request_id":"req","fields":{"name":"book"},"preview_fingerprint":"fp"}""",
      "update_world_book" to """{"request_id":"req","book_id":"book","expected_revision":"r1",
        "fields":{"enabled":false},"preview_fingerprint":"fp"}""",
      "copy_world_book" to """{"request_id":"req","book_id":"book","expected_revision":"r1","preview_fingerprint":"fp"}""",
      "delete_world_book" to """{"request_id":"req","book_id":"book","expected_revision":"r1","preview_fingerprint":"fp"}""",
      "read_world_book_bindings" to """{"scope":"character","target_id":"role"}""",
      "update_world_book_bindings" to """{"request_id":"req","scope":"character","target_id":"role",
        "expected_revision":"empty","book_ids":[],"preview_fingerprint":"fp"}""",
      "explain_world_activation" to """{"session_id":"chat"}""",
      "preview_world_book_action" to """{"request_id":"req","operation":"create_world_book","fields":{"name":"book"}}""",
      "cancel_world_book_action" to """{"request_id":"req","parameters":"{\"operation\":\"update_world_book\"}"}""",
      "bind_world_request" to """{"request_id":"req","dsh_session_id":"dsh","trigger_message_id":"message"}""",
      "resolve_world_request" to """{"request_id":"req","dsh_session_id":"dsh","trigger_message_id":"message","turn":1}""",
      "run_command" to """{"cmd":"true"}""",
    )

  @Test
  fun `known actions are exactly the ones the parser handles`() {
    assertTrue(minimalParams.keys == DeviceBridgeServer.KNOWN_ACTIONS)
  }

  @Test
  fun `every known action parses with minimal params`() {
    for ((action, params) in minimalParams) {
      assertNotNull(action, DeviceBridgeServer.parseAction(action, json(params)))
    }
  }

  @Test
  fun `model-visible tool names are all known to the bridge`() {
    // AGENTS.md §7.3 的 10 个工具；插件发的 action 名与工具名一一对应。
    val tools =
      listOf(
        "read_ui_tree",
        "tap_node",
        "long_press",
        "capture_screenshot",
        "tap_target",
        "long_press_target",
        "type_text",
        "press",
        "launch_app",
        "swipe",
        "list_calendar_events",
        "update_calendar_event",
        "delete_calendar_event",
        "schedule_task",
        "list_scheduled_tasks",
        "update_scheduled_task",
        "delete_scheduled_task",
      )
    for (tool in tools) assertTrue(tool, tool in DeviceBridgeServer.KNOWN_ACTIONS)
  }

  @Test
  fun `renamed-away action is reported as a non-retryable contract error`() {
    // read_screen / capture_frame 是 v0.11 改名前的名字；旧插件发它们时就是这个场景。
    for (stale in listOf("read_screen", "capture_frame")) {
      assertNull(DeviceBridgeServer.parseAction(stale, json("{}")))
      val message = DeviceBridgeServer.actionParseError(stale)
      assertTrue(stale, message.contains(stale))
      assertTrue(stale, message.contains("重试无效"))
      assertFalse(stale, message.contains("可以重试"))
    }
  }

  @Test
  fun `missing params stay retryable`() {
    for (action in DeviceBridgeServer.KNOWN_ACTIONS) {
      val message = DeviceBridgeServer.actionParseError(action)
      assertTrue(action, message.contains("可以重试"))
      assertFalse(action, message.contains("重试无效"))
    }
  }

  @Test
  fun `card action dispatch is exact and read calls do not require request identity`() {
    assertTrue(DeviceBridgeServer.parseAction("list_character_cards", json("{}")) is DeviceAction.ListCharacterCards)
    assertTrue(
      DeviceBridgeServer.parseAction("read_character_card", json("""{"character_id":"c","section":"basic"}"""))
        is DeviceAction.ReadCharacterCard,
    )
    assertTrue(
      DeviceBridgeServer.parseAction("cancel_character_card", json("""{"request_id":"call"}"""))
        is DeviceAction.CancelCharacterCard,
    )
    assertTrue(
      DeviceBridgeServer.parseAction("preview_character_card_update", json(minimalParams.getValue("preview_character_card_update")))
        is DeviceAction.PreviewCharacterCardUpdate,
    )
    assertTrue(
      DeviceBridgeServer.parseAction("update_character_card", json(minimalParams.getValue("update_character_card")))
        is DeviceAction.UpdateCharacterCard,
    )
    assertNull(DeviceBridgeServer.parseAction("update_character_cards", json("""{"request_id":"call"}""")))
    assertNull(DeviceBridgeServer.parseAction("preview_character_card_update", json("{}")))
    assertNull(DeviceBridgeServer.parseAction("update_character_card", json("""{"request_id":"call"}""")))
    assertNull(DeviceBridgeServer.parseAction("cancel_character_card", json("""{"request_id":123}""")))
    val action = DeviceBridgeServer.parseAction("update_character_card", json(minimalParams.getValue("update_character_card")))
      as DeviceAction.UpdateCharacterCard
    assertTrue(action.arguments.keys == setOf("character_id", "expected_revision", "fields"))
  }

  @Test
  fun `world management names are exact complete and host only`() {
    for (name in listOf(
      "list_world_books",
      "read_world_book",
      "create_world_book",
      "update_world_book",
      "copy_world_book",
      "delete_world_book",
      "read_world_book_bindings",
      "update_world_book_bindings",
      "explain_world_activation",
      "preview_world_book_action",
      "cancel_world_book_action",
    )) {
      assertTrue(name, DeviceBridgeServer.parseAction(name, json(minimalParams.getValue(name))) is DeviceAction.WorldBookOperation)
    }
    for (name in listOf("bind_world_request", "resolve_world_request")) {
      val action = DeviceBridgeServer.parseAction(name, json(minimalParams.getValue(name))) as DeviceAction.WorldRuntimeAction
      assertTrue("request_id" in action.arguments)
    }
    assertNull(DeviceBridgeServer.parseAction("update_world_books", json("{}")))
    assertNull(
      DeviceBridgeServer.parseAction(
        "update_world_book",
        json("""{"request_id":"req","book_id":"book","expected_revision":"r1"}"""),
      ),
    )
    assertNull(
      DeviceBridgeServer.parseAction(
        "preview_world_book_action",
        json("""{"request_id":"req","operation":"delete_world_book"}"""),
      ),
    )
    assertNull(DeviceBridgeServer.parseAction("preview_world_book_action", json("""{"request_id":"req","operation":"arbitrary"}""")))
    assertNull(DeviceBridgeServer.parseAction("read_world_book", json("{}")))
    assertNull(DeviceBridgeServer.parseAction("read_world_book", json("""{"book_id":1,"section":"basic"}""")))
    assertNull(DeviceBridgeServer.parseAction("bind_world_request", json("""{"dsh_session_id":"dsh","trigger_message_id":"message"}""")))
    assertNull(
      DeviceBridgeServer.parseAction(
        "resolve_world_request",
        json("""{"request_id":"req","dsh_session_id":"dsh","trigger_message_id":"message"}"""),
      ),
    )
    assertNull(
      DeviceBridgeServer.parseAction(
        "resolve_world_request",
        json("""{"request_id":"req","dsh_session_id":"dsh","trigger_message_id":"message","turn":"1"}"""),
      ),
    )
  }

  @Test
  fun `bridge rejects oversized negative and truncated bodies before executing`() {
    val token = DeviceBridgeServer.authToken()

    fun request(length: Int, body: String = ""): String {
      val separator = 13.toChar().toString() + 10.toChar()
      val wire = listOf("POST /action HTTP/1.1", "x-android-bridge-token: $token", "Content-Length: $length", "", body)
        .joinToString(separator)
      return DeviceBridgeServer.dispatchForTest(java.io.BufferedInputStream(wire.byteInputStream()))
    }
    assertTrue(request(-1).contains("长度无效"))
    assertTrue(request(DeviceBridgeServer.MAX_REQUEST_BYTES + 1).contains("超过上限"))
    assertTrue(request(50, "{}").contains("传输不完整"))
  }
}

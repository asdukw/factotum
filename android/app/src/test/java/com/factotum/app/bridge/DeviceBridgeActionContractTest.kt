package com.factotum.app.bridge

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
}

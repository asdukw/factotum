package com.factotum.app.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DshProtocol 的纯函数解析：会话事件 → UI 领域事件。 */
class DshProtocolTest {
  private fun frame(json: String): DshEvent? = DshProtocol.parse(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun `assistant message concatenates text blocks and keeps reasoning`() {
    val event =
      frame(
        """
        {"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"s1","event":{
          "type":"assistant/message","seq":4,"time":1,
          "data":{"turn":0,"step":0,"message":{"id":"m1","role":"assistant","content":[
            {"type":"reasoning","text":"想想"},
            {"type":"text","text":"你好"},
            {"type":"text","text":"！"}
          ]},"stream":[]}}}}
        """,
      )
    assertEquals(DshEvent.AssistantMessage("s1", "你好！", "想想"), event)
  }

  @Test
  fun `assistant message without reasoning leaves it null`() {
    val event =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"assistant/message","data":{"turn":0,"step":1,"message":{"id":"m2","role":"assistant",
            "content":[{"type":"text","text":"好的"}]},"stream":[]}}}}
        """,
      )
    assertEquals(DshEvent.AssistantMessage("s1", "好的", null), event)
  }

  @Test
  fun `assistant message joins multiple reasoning blocks`() {
    val event =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"assistant/message","data":{"turn":0,"step":2,"message":{"id":"m3","role":"assistant",
            "content":[{"type":"reasoning","text":"第一段"},{"type":"reasoning","text":"第二段"}]},"stream":[]}}}}
        """,
      )
    assertEquals(DshEvent.AssistantMessage("s1", "", "第一段\n第二段"), event)
  }

  @Test
  fun `tool call carries callId, name and raw arguments`() {
    val call =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"tool/call","data":{"turn":0,"step":0,"callId":"c1","name":"read_ui_tree","arguments":"{\"mode\":\"tree\"}"}}}}
        """,
      )
    assertEquals(DshEvent.ToolCall("s1", "c1", "read_ui_tree", """{"mode":"tree"}"""), call)
  }

  @Test
  fun `tool result keeps callId, error flag and text blocks`() {
    val result =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"tool/result","data":{"turn":0,"step":0,"message":{"id":"r1","role":"tool",
            "toolCallId":"c1","isError":true,"content":[{"type":"text","text":"点击失败"},
            {"type":"image","attachment":{}}]}}}}}
        """,
      )
    assertEquals(DshEvent.ToolResult("s1", "c1", isError = true, text = "点击失败"), result)
  }

  @Test
  fun `tool result without message content degrades to null text`() {
    val result =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"tool/result","data":{"turn":0,"step":0,"message":{"id":"r1","role":"tool","isError":false}}}}}
        """,
      )
    assertEquals(DshEvent.ToolResult("s1", null, isError = false, text = null), result)
  }

  @Test
  fun `turn end error and success`() {
    val error =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"turn/end","data":{"turn":0,"reason":{"kind":"error","error":{"message":"boom","code":"X"}}}}}}
        """,
      )
    assertEquals(true, (error as DshEvent.TurnEnded).error?.contains("boom"))

    val stop =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"turn/end","data":{"turn":0,"reason":{"kind":"stop"}}}}}
        """,
      )
    assertEquals(DshEvent.TurnEnded("s1", null), stop)
  }

  @Test
  fun `turn end error keeps provider message and code`() {
    val event =
      frame(
        """
        {"method":"session.event","params":{"sessionId":"s1","event":{
          "type":"turn/end","data":{"turn":1,"reason":{"kind":"error",
            "error":{"message":"429: Go usage limit exceeded","code":"QUOTA"}}}}}}
        """,
      )
    assertEquals(DshEvent.TurnEnded("s1", "429: Go usage limit exceeded", "QUOTA"), event)
  }

  @Test
  fun `turn end error text explains known codes and keeps unknown ones raw`() {
    assertEquals(
      "模型额度已用尽：429: Go usage limit exceeded",
      DshProtocol.turnErrorText("QUOTA", "429: Go usage limit exceeded"),
    )
    assertEquals("boom", DshProtocol.turnErrorText("X", "boom"))
    assertEquals("模型调用失败（未返回原因）", DshProtocol.turnErrorText(null, null))
  }

  @Test
  fun `session status maps running and idle`() {
    assertEquals(
      DshEvent.Status("s1", running = true),
      frame("""{"method":"session.status","params":{"sessionId":"s1","status":"running"}}"""),
    )
    assertEquals(
      DshEvent.Status("s1", running = false),
      frame("""{"method":"session.status","params":{"sessionId":"s1","status":"idle"}}"""),
    )
  }

  @Test
  fun `unrelated frames are ignored`() {
    assertNull(frame("""{"method":"session.status","params":{"status":"running"}}"""))
    assertNull(frame("""{"method":"session.event","params":{"sessionId":"s1","event":{"type":"turn/start"}}}"""))
    assertNull(frame("""{"method":"initialize","params":{}}"""))
    assertNull(frame("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
  }

  @Test
  fun `stream frame carries text delta`() {
    val event =
      frame(
        """
        {"jsonrpc":"2.0","method":"session.stream","params":{"sessionId":"s1","attemptId":"a1",
          "revision":1,"index":3,"chunk":{"type":"text-delta","index":0,"text":"你好"}}}
        """,
      )
    assertEquals(DshEvent.StreamDelta("s1", text = "你好"), event)
  }

  @Test
  fun `stream frame carries reasoning delta`() {
    val event =
      frame(
        """
        {"jsonrpc":"2.0","method":"session.stream","params":{"sessionId":"s1",
          "chunk":{"type":"reasoning-delta","text":"想"}}}
        """,
      )
    assertEquals(DshEvent.StreamDelta("s1", reasoning = "想"), event)
  }

  /** 只转发正文与推理；别的 chunk（工具参数增量、block 边界）对 UI 没有意义。 */
  @Test
  fun `stream frame ignores chunk types the ui does not need`() {
    val event =
      frame(
        """
        {"jsonrpc":"2.0","method":"session.stream","params":{"sessionId":"s1",
          "chunk":{"type":"block-start"}}}
        """,
      )
    assertNull(event)
  }
}

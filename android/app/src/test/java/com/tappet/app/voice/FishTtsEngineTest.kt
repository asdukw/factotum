package com.tappet.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fish Audio 的请求体与错误文案 —— 都是纯函数。
 *
 * 这两处写错了只有在真机上、用了真 key 才看得出来（合成成功但计费到付费模型 / 失败时只
 * 显示一句「朗读中断」），所以能在 JVM 里钉住的就钉住。
 */
class FishTtsEngineTest {
  @Test
  fun `请求体带合成参数`() {
    val body = Json.parseToJsonElement(fishRequestBody("你好", 1.5f)).jsonObject

    assertEquals("你好", body["text"]?.jsonPrimitive?.contentOrNull)
    assertEquals("mp3", body["format"]?.jsonPrimitive?.contentOrNull)
    assertEquals(128, body["mp3_bitrate"]?.jsonPrimitive?.intOrNull)
    assertEquals(1.5f, body["prosody"]?.jsonObject?.get("speed")?.jsonPrimitive?.floatOrNull ?: -1f, 0.0001f)
  }

  @Test
  fun `model 不进 body`() {
    // 免费模型走 HTTP header；写进 body 会被服务端忽略，然后静默按付费模型计费。
    val body = Json.parseToJsonElement(fishRequestBody("hi", 1f)).jsonObject
    assertNull(body["model"])
  }

  @Test
  fun `选了音色时请求体带 reference_id`() {
    val body =
      Json.parseToJsonElement(fishRequestBody("hi", 1f, "4ee64e5052834c25b9785b693d3489ab")).jsonObject
    assertEquals("4ee64e5052834c25b9785b693d3489ab", body["reference_id"]?.jsonPrimitive?.contentOrNull)
  }

  @Test
  fun `默认音色不带 reference_id`() {
    // 带空 reference_id 会被服务端当成无效引用，宁可整条字段都不出现。
    val body = Json.parseToJsonElement(fishRequestBody("hi", 1f, FishVoices.DEFAULT_ID)).jsonObject
    assertNull(body["reference_id"])
  }

  @Test
  fun `已知状态码翻成人话`() {
    assertEquals("Fish Audio API key 无效或已过期", fishErrorText(401, null))
    assertEquals("Fish Audio 余额不足", fishErrorText(402, "{}"))
    assertEquals("Fish Audio 限流（并发达上限），请稍后重试", fishErrorText(429, null))
    assertEquals("Fish Audio 服务暂时不可用", fishErrorText(503, null))
  }

  @Test
  fun `未知状态码带出服务端 message`() {
    assertEquals("Fish Audio 返回 HTTP 500：boom", fishErrorText(500, """{"status":500,"message":"boom"}"""))
    assertEquals("Fish Audio 返回 HTTP 500", fishErrorText(500, "not json"))
  }
}

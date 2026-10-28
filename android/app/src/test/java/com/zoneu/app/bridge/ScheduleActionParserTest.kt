package com.zoneu.app.bridge

import com.zoneu.app.device.DeviceAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleActionParserTest {
  private fun create(fields: String = "{}"): DeviceAction.CreateScheduledTask? {
    val base = Json.parseToJsonElement("""{"title":"fixture","prompt":"fixture","hour":9,"minute":0}""").jsonObject
    val patch = Json.parseToJsonElement(fields).jsonObject
    return parseScheduledTaskCreate(JsonObject(base + patch))
  }

  @Test
  fun `legacy task defaults daily and all explicit supported rules parse`() {
    assertEquals("daily", create()?.rule?.type)
    assertEquals(3, create("""{"schedule_type":"weekly", "weekday":3}""")?.rule?.weekday)
    assertEquals(31, create("""{"schedule_type":"monthly", "day_of_month":31}""")?.rule?.dayOfMonth)
    assertEquals(2, create("""{"schedule_type":"yearly", "month":2, "day_of_month":29}""")?.rule?.month)
    assertEquals("2027-01-05", create("""{"schedule_type":"once", "date":"2027-01-05"}""")?.rule?.date)
  }

  @Test
  fun `malformed supplied recurrence fields never silently fall back to daily`() {
    val invalid = listOf(
      """{"schedule_type":"cron"}""",
      """{"schedule_type":null}""",
      """{"schedule_type":42}""",
      """{"schedule_type":"weekly"}""",
      """{"schedule_type":"weekly", "weekday":"3"}""",
      """{"schedule_type":"weekly", "weekday":3.5}""",
      """{"schedule_type":"monthly", "day_of_month":0}""",
      """{"schedule_type":"once", "date":"2027-02-29"}""",
      """{"schedule_type":"once", "date":null}""",
      """{"schedule_type":"daily", "weekday":1}""",
      """{"schedule_type":"yearly", "month":4, "day_of_month":31}""",
      """{"enabled":"false"}""",
    )
    invalid.forEach { assertNull(it, create(it)) }
  }

  @Test
  fun `partial time and rule updates retain omission without accepting null or wrong types`() {
    fun update(json: String) = parseScheduledTaskUpdate("id", Json.parseToJsonElement(json).jsonObject)
    val partial = update("""{"hour":10}""")
    assertNotNull(partial)
    assertEquals(10, partial?.hour)
    assertNull(partial?.minute)
    assertNull(partial?.rule?.type)
    assertEquals("monthly", update("""{"schedule_type":"monthly","day_of_month":15}""")?.rule?.type)
    for (json in listOf("""{"hour":null}""", """{"weekday":"3"}""", """{"schedule_type":"nonsense"}""")) {
      assertNull(json, update(json))
    }
  }
}

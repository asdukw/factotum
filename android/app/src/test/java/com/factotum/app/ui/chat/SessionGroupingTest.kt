package com.factotum.app.ui.chat

import com.factotum.app.data.db.SessionKind
import com.factotum.app.data.session.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SessionGroupingTest {
  private val zone = ZoneId.of("Asia/Shanghai")
  private val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

  private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

  @Test
  fun `today maps to 今天`() {
    assertEquals("今天", sessionGroupLabel(at(2026, 10, 6, 1), now, zone))
  }

  @Test
  fun `future timestamp still maps to 今天`() {
    assertEquals("今天", sessionGroupLabel(at(2026, 10, 6, 23), now, zone))
  }

  @Test
  fun `yesterday maps to 昨天`() {
    assertEquals("昨天", sessionGroupLabel(at(2026, 10, 5, 23), now, zone))
  }

  @Test
  fun `within a week maps to 7 天内`() {
    assertEquals("7 天内", sessionGroupLabel(at(2026, 10, 3), now, zone))
  }

  @Test
  fun `seven days ago maps to 更早`() {
    assertEquals("更早", sessionGroupLabel(at(2026, 9, 29), now, zone))
  }

  @Test
  fun `groups keep order and drop empty labels`() {
    val sessions =
      listOf(
        session("a", at(2026, 10, 6, 8)),
        session("b", at(2026, 10, 1)),
        session("c", at(2026, 10, 5, 22)),
        session("d", at(2026, 9, 1)),
      )

    val grouped = groupSessions(sessions, now)

    assertEquals(listOf("今天", "昨天", "7 天内", "更早"), grouped.map { it.first })
    assertEquals(listOf("a"), grouped[0].second.map { it.id })
    assertEquals(listOf("c"), grouped[1].second.map { it.id })
    assertEquals(listOf("b"), grouped[2].second.map { it.id })
    assertEquals(listOf("d"), grouped[3].second.map { it.id })
  }

  private fun session(id: String, updatedAt: Long) =
    ChatSession(id = id, title = id, kind = SessionKind.Chat, createdAt = updatedAt, updatedAt = updatedAt)
}

package com.zoneu.app.ui.settings

import com.zoneu.app.data.db.ScheduledTaskRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledTaskRunRowsTest {
  private val zone = ZoneId.of("Asia/Shanghai")

  private fun at(value: String) = Instant.parse(value).toEpochMilli()

  private fun run(
    finishedAt: Long? = at("2026-10-31T02:02:08Z"),
    result: String? = null,
  ) = ScheduledTaskRunEntity(
    id = "run",
    taskId = "task",
    scheduledAt = at("2026-10-31T02:00:00Z"),
    startedAt = at("2026-10-31T02:00:03Z"),
    finishedAt = finishedAt,
    result = result,
  )

  private fun row(run: ScheduledTaskRunEntity, running: Boolean = false) =
    scheduledTaskRunRows(listOf(run), zone) { running }.single()

  @Test
  fun `successful run shows plan start duration and result`() {
    val row = row(run())
    assertEquals("2026-10-31 10:00:00", row.scheduled)
    assertEquals("2026-10-31 10:00:03", row.started)
    assertEquals("2 分 5 秒", row.duration)
    assertEquals("成功", row.result)
    assertFalse(row.isError)
  }

  @Test
  fun `failure code is translated`() {
    val row = row(run(result = "TURN_FAILED"))
    assertEquals("失败：模型调用失败", row.result)
    assertTrue(row.isError)
  }

  @Test
  fun `open record of a running task says in progress`() {
    val row = row(run(finishedAt = null), running = true)
    assertEquals("执行中", row.result)
    assertNull(row.duration)
    assertFalse(row.isError)
  }

  @Test
  fun `open record from a killed process is unfinished`() {
    val row = row(run(finishedAt = null), running = false)
    assertEquals("未完成（进程可能被中断）", row.result)
    assertNull(row.duration)
    assertTrue(row.isError)
  }

  @Test
  fun `duration uses seconds minutes and hours`() {
    val started = at("2026-10-31T02:00:00Z")

    fun durationOf(finished: String): String? =
      scheduledTaskRunRows(
        listOf(
          ScheduledTaskRunEntity(
            id = "run",
            taskId = "task",
            scheduledAt = started,
            startedAt = started,
            finishedAt = at(finished),
          ),
        ),
        zone,
      ).single().duration
    assertEquals("45 秒", durationOf("2026-10-31T02:00:45Z"))
    assertEquals("1 分 0 秒", durationOf("2026-10-31T02:01:00Z"))
    assertEquals("1 时 0 分", durationOf("2026-10-31T03:00:00Z"))
  }
}

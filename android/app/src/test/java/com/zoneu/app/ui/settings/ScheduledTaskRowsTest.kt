package com.zoneu.app.ui.settings

import com.zoneu.app.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledTaskRowsTest {
  private val zone = ZoneId.of("Asia/Shanghai")

  private fun at(value: String) = Instant.parse(value).toEpochMilli()

  private fun task(
    hour: Int = 8,
    enabled: Boolean = true,
    scheduleType: String = "daily",
    date: String? = null,
    nextRunAt: Long? = null,
    lastRunAt: Long? = null,
    lastError: String? = null,
  ) = ScheduledTaskEntity(
    id = "id",
    title = "看天气",
    prompt = "看看今天天气",
    hour = hour,
    minute = 0,
    enabled = enabled,
    scheduleType = scheduleType,
    date = date,
    nextRunAt = nextRunAt,
    lastRunAt = lastRunAt,
    lastError = lastError,
  )

  private fun row(
    task: ScheduledTaskEntity,
    now: String = "2026-10-31T02:00:00Z",
    running: Boolean = false,
  ) = scheduledTaskRows(listOf(task), at(now), zone) { running }.single()

  @Test
  fun `daily task shows rule and armed next trigger`() {
    val row = row(task(nextRunAt = at("2026-11-01T00:00:00Z")))
    assertEquals("看天气", row.title)
    assertEquals("每天 08:00", row.schedule)
    assertEquals("下次 2026-11-01 08:00", row.timing)
    assertEquals("还没运行过", row.result)
    assertEquals(true, row.enabled)
    assertEquals(false, row.running)
  }

  @Test
  fun `enabled task without an armed alarm does not promise a trigger`() {
    // next_run_at 为 null = 闹钟没排上；不能按规则编一个不会响的「下次」。
    assertEquals("未排期，暂不会触发", row(task()).timing)
  }

  @Test
  fun `past armed trigger is shown as overdue instead of upcoming`() {
    // now = 2026-10-31 10:00 上海，排期时刻已经过去。
    assertEquals(
      "已到点，尚未触发（可能延迟）",
      row(task(nextRunAt = at("2026-10-31T00:00:00Z"))).timing,
    )
  }

  @Test
  fun `executed once task reports it will not run again`() {
    val ran = at("2026-10-31T00:00:00Z")
    val row =
      row(
        task(
          enabled = false,
          scheduleType = "once",
          date = "2026-10-31",
          lastRunAt = ran,
        ),
      )
    assertEquals("2026-10-31 08:00（仅一次）", row.schedule)
    assertEquals("单次任务已执行", row.timing)
    assertEquals("上次 2026-10-31 08:00 · 成功", row.result)
    assertFalse(row.enabled)
  }

  @Test
  fun `failure codes are translated and never leak model text`() {
    val row = row(task(lastRunAt = at("2026-10-31T00:00:00Z"), lastError = "TURN_FAILED"))
    assertEquals("上次 2026-10-31 08:00 · 失败：模型调用失败", row.result)
    assertTrue(row.isError)
  }

  @Test
  fun `running once task does not look executed or disabled`() {
    val row =
      row(
        task(
          enabled = false,
          scheduleType = "once",
          date = "2026-10-31",
          lastRunAt = at("2026-10-31T00:00:00Z"),
          lastError = "RUNNING",
        ),
        running = true,
      )
    assertEquals("正在执行", row.timing)
    assertEquals("正在执行", row.result)
    assertEquals(true, row.running)
    assertFalse(row.isError)
  }

  @Test
  fun `stale running marker from a killed process is not shown as live`() {
    val row =
      row(
        task(
          enabled = false,
          scheduleType = "once",
          date = "2026-10-31",
          lastRunAt = at("2026-10-31T00:00:00Z"),
          lastError = "RUNNING",
        ),
        running = false,
      )
    assertEquals("单次任务未完成，不会再触发", row.timing)
    assertEquals("上次运行未完成（进程可能被中断）", row.result)
    assertEquals(false, row.running)
    assertTrue(row.isError)
  }

  @Test
  fun `schedule missed after an older success is not attached to that run time`() {
    val row = row(task(lastRunAt = at("2026-10-30T00:00:00Z"), lastError = "SCHEDULE_MISSED"))
    assertEquals("已错过（未补跑）", row.result)
    assertTrue(row.isError)
  }

  @Test
  fun `missed once task has no fake run time`() {
    val row = row(task(enabled = false, scheduleType = "once", date = "2026-10-31", lastError = "SCHEDULE_MISSED"))
    assertEquals("已停用，不会再触发", row.timing)
    assertEquals("已错过（未补跑）", row.result)
    assertTrue(row.isError)
  }
}

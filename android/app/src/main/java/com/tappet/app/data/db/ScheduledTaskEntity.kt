package com.tappet.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条定时任务：到点让 agent 跑一句指令。
 *
 * 时间规则**只有「每天 HH:mm」**一种，是刻意的：手机上真正靠得住的只有「每天一次」这种
 * 粗粒度周期（见 `docs/scheduled-tasks.md`）。写 cron 表达式会给人一个跑不住的承诺。
 *
 * **不存结果正文**：跑完只留一句「成功 / 失败 + 原因码」与时间戳。任务产出会进会话库，
 * 而这里是一张给列表页看的表，多存一份正文等于多一处会泄漏屏幕内容的地方。
 */
@Entity(tableName = "scheduled_task")
data class ScheduledTaskEntity(
  @PrimaryKey val id: String,
  /** 列表里显示的一句标题（用户填或从 prompt 截前若干字）。 */
  @ColumnInfo(name = "title") val title: String,
  /** 交给 agent 的完整指令。 */
  @ColumnInfo(name = "prompt") val prompt: String,
  @ColumnInfo(name = "hour") val hour: Int,
  @ColumnInfo(name = "minute") val minute: Int,
  @ColumnInfo(name = "enabled") val enabled: Boolean,
  /** 上次真正跑的时间（毫秒）；没跑过就是 null。 */
  @ColumnInfo(name = "last_run_at") val lastRunAt: Long? = null,
  /** 上次的结果：成功为 null，失败是原因码（不是正文）。 */
  @ColumnInfo(name = "last_error") val lastError: String? = null,
)

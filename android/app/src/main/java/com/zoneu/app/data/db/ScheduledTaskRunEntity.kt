package com.zoneu.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 每条任务保留的执行记录条数；插入新记录后按开始时间裁掉更旧的。 */
internal const val SCHEDULE_RUN_HISTORY_LIMIT = 50

/**
 * 一次真正的执行尝试（领到任务之后才写）。
 *
 * 只存时间与结果码，不存任务 prompt、模型输出或屏幕内容 —— 执行记录页会进截图，
 * 任务产物仍然只出现在会话 / 工具结果里。
 *
 * 进程在任务执行中被强杀时，这一行会停在 `finished_at = null`；读取时用进程内的
 * 运行集合区分「正在跑」与「上次没跑完」，不在启动时改写历史。
 */
@Entity(
  tableName = "scheduled_task_run",
  foreignKeys =
    [
      ForeignKey(
        entity = ScheduledTaskEntity::class,
        parentColumns = ["id"],
        childColumns = ["task_id"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index(value = ["task_id", "started_at"])],
)
data class ScheduledTaskRunEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "task_id") val taskId: String,
  /** 闹钟计划触发的时刻；执行迟到时仍能看出本来该在什么时候跑。 */
  @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
  @ColumnInfo(name = "started_at") val startedAt: Long,
  @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
  /** null = 成功；否则是结果码（TURN_FAILED 等），与任务行的 `last_error` 同一套。 */
  @ColumnInfo(name = "result") val result: String? = null,
)

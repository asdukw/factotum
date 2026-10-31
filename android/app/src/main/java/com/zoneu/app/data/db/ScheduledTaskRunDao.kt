package com.zoneu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 定时任务的执行记录。写入只由 [com.zoneu.app.schedule.ScheduledTaskRunner] 发起。 */
@Dao
interface ScheduledTaskRunDao {
  @Query("SELECT * FROM scheduled_task_run WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
  fun observeForTask(taskId: String, limit: Int): Flow<List<ScheduledTaskRunEntity>>

  @Query("SELECT * FROM scheduled_task_run WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
  suspend fun forTask(taskId: String, limit: Int): List<ScheduledTaskRunEntity>

  @Insert
  suspend fun insert(run: ScheduledTaskRunEntity)

  /**
   * 只关闭还没结束的记录：进程被杀后残留的那一行不会被后来一次成功的 `finish` 覆盖。
   */
  @Query("UPDATE scheduled_task_run SET finished_at = :finishedAt, result = :result WHERE id = :id AND finished_at IS NULL")
  suspend fun finish(id: String, finishedAt: Long, result: String?): Int

  @Query(
    "DELETE FROM scheduled_task_run WHERE task_id = :taskId AND id NOT IN " +
      "(SELECT id FROM scheduled_task_run WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :keep)",
  )
  suspend fun prune(taskId: String, keep: Int): Int
}

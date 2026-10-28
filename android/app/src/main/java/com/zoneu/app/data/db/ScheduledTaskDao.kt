package com.zoneu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 定时任务表的读写入口。与会话表同库同事务域（`zoneu.db`，SQLCipher 加密）。 */
@Dao
interface ScheduledTaskDao {
  @Query("SELECT * FROM scheduled_task ORDER BY hour, minute")
  fun observeAll(): Flow<List<ScheduledTaskEntity>>

  @Query("SELECT * FROM scheduled_task ORDER BY hour, minute")
  suspend fun all(): List<ScheduledTaskEntity>

  @Query("SELECT * FROM scheduled_task WHERE id = :id")
  suspend fun byId(id: String): ScheduledTaskEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(task: ScheduledTaskEntity)

  @Query("DELETE FROM scheduled_task WHERE id = :id")
  suspend fun delete(id: String)

  @Query("UPDATE scheduled_task SET last_run_at = :at, last_error = :error WHERE id = :id")
  suspend fun recordRun(id: String, at: Long, error: String?)

  @Query("UPDATE scheduled_task SET next_run_at = :at WHERE id = :id AND revision = :revision AND enabled = 1")
  suspend fun arm(id: String, revision: String, at: Long): Int

  @Query(
    "UPDATE scheduled_task SET next_run_at = NULL, last_run_at = :started, last_error = 'RUNNING', " +
      "enabled = CASE WHEN schedule_type = 'once' THEN 0 ELSE enabled END " +
      "WHERE id = :id AND revision = :revision AND enabled = 1 AND next_run_at = :planned AND :started >= :planned",
  )
  suspend fun claim(id: String, revision: String, planned: Long, started: Long): Int

  @Query("UPDATE scheduled_task SET last_error = :error WHERE id = :id AND revision = :revision AND last_run_at = :started")
  suspend fun finish(id: String, revision: String, started: Long, error: String?): Int

  @Query("UPDATE scheduled_task SET enabled = 0, next_run_at = NULL, last_error = :error WHERE id = :id AND revision = :revision")
  suspend fun expire(id: String, revision: String, error: String): Int
}

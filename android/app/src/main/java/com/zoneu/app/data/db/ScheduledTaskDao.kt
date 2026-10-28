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
}

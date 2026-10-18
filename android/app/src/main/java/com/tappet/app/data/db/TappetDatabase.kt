package com.tappet.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [
    SessionEntity::class,
    MessageEntity::class,
    ToolCallEntity::class,
    ScheduledTaskEntity::class,
  ],
  // v2：加定时任务表（见 TappetDatabaseProvider 的 MIGRATION_1_2 —— 不做破坏性迁移，
  // 那会把用户已有的会话清空）。
  version = 2,
  exportSchema = true,
)
abstract class TappetDatabase : RoomDatabase() {
  abstract fun store(): SessionStoreDao

  abstract fun scheduledTasks(): ScheduledTaskDao
}

package com.zoneu.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [
    SessionEntity::class,
    MessageEntity::class,
    ToolCallEntity::class,
    ScheduledTaskEntity::class,
    WorldBookHeadEntity::class,
    WorldBookRevisionEntity::class,
    WorldBookEntryIdentityEntity::class,
    WorldRoleBindingsEntity::class,
    WorldRoleReferenceEntity::class,
    WorldManifestEntity::class,
    WorldSessionReferenceEntity::class,
    WorldRuntimeStateEntity::class,
    WorldRequestPlanEntity::class,
    WorldOperationReceiptEntity::class,
    WorldOperationCancellationEntity::class,
  ],
  // v2：加定时任务表（见 ZoneuDatabaseProvider 的 MIGRATION_1_2 —— 不做破坏性迁移，
  // 那会把用户已有的会话清空）。
  // v3：会话加 character_id（酒馆会话绑定角色卡），见 ZoneuDatabaseProvider 的 MIGRATION_2_3。
  version = 8,
  exportSchema = true,
)
abstract class ZoneuDatabase : RoomDatabase() {
  abstract fun store(): SessionStoreDao

  abstract fun scheduledTasks(): ScheduledTaskDao

  abstract fun worldBooks(): WorldBookDao
}

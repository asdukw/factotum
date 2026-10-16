package com.tappet.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [SessionEntity::class, MessageEntity::class, ToolCallEntity::class],
  version = 1,
  exportSchema = true,
)
abstract class TappetDatabase : RoomDatabase() {
  abstract fun store(): SessionStoreDao
}

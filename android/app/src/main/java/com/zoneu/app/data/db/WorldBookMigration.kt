package com.zoneu.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: no card files, legacy sessions or old tables are rewritten. */
object WorldBookMigration : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    statements.forEach(db::execSQL)
  }

  private val statements = listOf(
    "CREATE TABLE IF NOT EXISTS `world_book` (`id` TEXT NOT NULL, `revision` TEXT NOT NULL, " +
      "`deleted` INTEGER NOT NULL, `fingerprint` TEXT, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_world_book_fingerprint` ON `world_book` (`fingerprint`)",
    "CREATE TABLE IF NOT EXISTS `world_revision` (`bookId` TEXT NOT NULL, `revision` TEXT NOT NULL, " +
      "`payload` TEXT NOT NULL, PRIMARY KEY(`bookId`, `revision`), " +
      "FOREIGN KEY(`bookId`) REFERENCES `world_book`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE TABLE IF NOT EXISTS `world_entry_identity` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, PRIMARY KEY(`id`), " +
      "FOREIGN KEY(`bookId`) REFERENCES `world_book`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE INDEX IF NOT EXISTS `index_world_entry_identity_bookId` ON `world_entry_identity` (`bookId`)",
    "CREATE TABLE IF NOT EXISTS `world_role_bindings` (`characterId` TEXT NOT NULL, `revision` TEXT NOT NULL, " +
      "`payload` TEXT NOT NULL, PRIMARY KEY(`characterId`))",
    "CREATE TABLE IF NOT EXISTS `world_role_reference` (`characterId` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
      "PRIMARY KEY(`characterId`, `bookId`), " +
      "FOREIGN KEY(`characterId`) REFERENCES `world_role_bindings`(`characterId`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
      "FOREIGN KEY(`bookId`) REFERENCES `world_book`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE INDEX IF NOT EXISTS `index_world_role_reference_bookId` ON `world_role_reference` (`bookId`)",
    "CREATE TABLE IF NOT EXISTS `world_manifest` (`sessionId` TEXT NOT NULL, `revision` TEXT NOT NULL, " +
      "`payload` TEXT NOT NULL, PRIMARY KEY(`sessionId`), " +
      "FOREIGN KEY(`sessionId`) REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE IF NOT EXISTS `world_session_reference` (`sessionId` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
      "`revision` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `bookId`), " +
      "FOREIGN KEY(`sessionId`) REFERENCES `world_manifest`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
      "FOREIGN KEY(`bookId`, `revision`) REFERENCES `world_revision`(`bookId`, `revision`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE INDEX IF NOT EXISTS `index_world_session_reference_bookId_revision` ON `world_session_reference` (`bookId`, `revision`)",
    "CREATE TABLE IF NOT EXISTS `world_runtime_state` (`sessionId` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`sessionId`), " +
      "FOREIGN KEY(`sessionId`) REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE IF NOT EXISTS `world_request_plan` (`requestId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
      "`status` TEXT NOT NULL, `payload` TEXT NOT NULL, `parameterHash` TEXT NOT NULL, `evidenceHash` TEXT, PRIMARY KEY(`requestId`), " +
      "FOREIGN KEY(`sessionId`) REFERENCES `session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_world_request_plan_sessionId` ON `world_request_plan` (`sessionId`)",
    "CREATE TABLE IF NOT EXISTS `world_operation_receipt` (`requestId` TEXT NOT NULL, `operation` TEXT NOT NULL, " +
      "`parameterHash` TEXT NOT NULL, `actualHash` TEXT NOT NULL, " +
      "`targetId` TEXT NOT NULL, `revision` TEXT NOT NULL, PRIMARY KEY(`requestId`))",
    "CREATE TABLE IF NOT EXISTS `world_operation_cancellation` (`requestId` TEXT NOT NULL, " +
      "`parameterHash` TEXT NOT NULL, PRIMARY KEY(`requestId`))",
  )
}

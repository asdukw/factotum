package com.tappet.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tappet.app.data.crypto.PassphraseVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 打开 `tappet.db` —— Room 建表，SQLCipher 负责落盘加密。
 *
 * 口令由 [PassphraseVault.App] 从 Android Keystore 解出，明文只在内存里过一遍。
 * 落盘口令由 PassphraseVault.App 管理（Android Keystore 封装）。
 */
object TappetDatabaseProvider {
  /** 库文件名是设备上数据的身份：改它等于换一个库，已有数据会变成解不开的孤儿文件。 */
  const val DATABASE_NAME = "tappet.db"

  @Volatile private var libraryLoaded = false

  /** SQLCipher 原生库不会自动加载；漏掉会报 nativeOpen 的 UnsatisfiedLinkError（曾因删探针代码丢掉过）。 */
  @Synchronized
  private fun loadLibrary() {
    if (!libraryLoaded) {
      System.loadLibrary("sqlcipher")
      libraryLoaded = true
    }
  }

  /**
   * v1 → v2：加定时任务表。
   *
   * **不用破坏性迁移**：那会把用户已有的会话与消息清空。这里只加一张表。
   */
  private val MIGRATION_1_2 =
    object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "CREATE TABLE IF NOT EXISTS `scheduled_task` (" +
            "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `prompt` TEXT NOT NULL, " +
            "`hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, " +
            "`enabled` INTEGER NOT NULL, `last_run_at` INTEGER, `last_error` TEXT, " +
            "PRIMARY KEY(`id`))",
        )
      }
    }

  /**
   * v2 → v3：会话表加 `character_id`。
   *
   * 只加列、不回填：老会话一律是日常会话（`kind = Chat`），character_id 天然为 null，
   * 这正是它们该有的样子。索引名要跟 Room 生成的一致（`index_session_character_id`），
   * 否则 Room 的表结构校验会认为 schema 不匹配而拒绝启动。
   */
  private val MIGRATION_2_3 =
    object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session` ADD COLUMN `character_id` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_character_id` ON `session` (`character_id`)")
      }
    }

  /**
   * v3 → v4：消息表加「候选回答」三列。
   *
   * - `anchor_id`：这份回答在回答哪条用户消息（空 = 没有替代品，比如开场白与工具步骤）；
   * - `variant_index`：同一 anchor 下的第几份；
   * - `variant_selected`：组里当前显示哪一份。
   *
   * 只加列、全部给默认值：老数据的 anchor_id 为 null、variant_index 为 0、selected 为 1，
   * 于是查询（只取 selected）读到的历史与升级前一模一样。
   */
  private val MIGRATION_3_4 =
    object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `message` ADD COLUMN `anchor_id` TEXT")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `variant_index` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `message` ADD COLUMN `variant_selected` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_anchor_id` ON `message` (`anchor_id`)")
      }
    }

  /**
   * 4 → 5：会话加 `character_ids`（群聊在场的所有卡）。
   *
   * 只加一列、允许为空：老会话读出来 characterIds 是空的，代码按 `[characterId]` 处理，
   * 行为与升级前完全一致 —— 不需要在这条迁移里回填任何东西。
   */
  private val MIGRATION_4_5 =
    object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session` ADD COLUMN `character_ids` TEXT")
      }
    }

  fun open(context: Context): TappetDatabase {
    loadLibrary()
    val appContext = context.applicationContext
    val passphrase = PassphraseVault.App.passphrase(appContext)
    return Room.databaseBuilder(appContext, TappetDatabase::class.java, DATABASE_NAME)
      .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
      .addCallback(
        object : RoomDatabase.Callback() {
          override fun onOpen(db: SupportSQLiteDatabase) {
            // 级联删除（删会话带走消息、删消息带走工具调用）依赖这个开关
            db.execSQL("PRAGMA foreign_keys = ON")
          }
        },
      )
      .build()
  }
}

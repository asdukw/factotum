package com.tappet.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
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

  fun open(context: Context): TappetDatabase {
    loadLibrary()
    val appContext = context.applicationContext
    val passphrase = PassphraseVault.App.passphrase(appContext)
    return Room.databaseBuilder(appContext, TappetDatabase::class.java, DATABASE_NAME)
      .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))
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

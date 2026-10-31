package com.zoneu.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Never opens the application database. Both files contain synthetic rows only. */
@RunWith(AndroidJUnit4::class)
class WorldBookMigrationAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun migration7To8PreservesOldRowsAndDoesNotPopulateLegacyManifests() = migrate(7)

  @Test
  fun fullMigration1To8PreservesLegacyChatAndCreatesAllTables() = migrate(1)

  private fun migrate(version: Int) =
    runBlocking {
      val name = "world-migration-${UUID.randomUUID()}.db"
      try {
        val helper =
          FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
              .builder(context)
              .name(name)
              .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                  override fun onCreate(db: SupportSQLiteDatabase) {
                    createOldSchema(db, version)
                    seed(db, version)
                  }

                  override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                  ) = error("Unexpected upgrade")
                },
              ).build(),
          )
        helper.writableDatabase
        helper.close()
        val database =
          Room
            .databaseBuilder(context, ZoneuDatabase::class.java, name)
            .addMigrations(*ZoneuDatabaseProvider.migrations)
            .build()
        try {
          val session = database.store().session("synthetic-session")!!
          assertEquals("Synthetic legacy chat", session.title)
          assertEquals(1, database.store().messages(session.id).size)
          assertEquals(1, database.store().toolCalls(session.id).size)
          if (version == 7) {
            val task = database.scheduledTasks().byId("synthetic-task")!!
            assertEquals("weekly", task.scheduleType)
            assertEquals("synthetic-revision", task.revision)
            assertEquals(1, database.store().variantsOf("synthetic-anchor").size)
          }
          assertEquals(
            "synthetic reply",
            database
              .store()
              .messages(session.id)
              .single()
              .content,
          )
          assertEquals(
            "synthetic_tool",
            database
              .store()
              .toolCalls(session.id)
              .single()
              .name,
          )
          if (version == 1) {
            assertNull(session.characterId)
            assertNull(session.characterIds)
            assertNull(session.parentId)
            val message = database.store().messages(session.id).single()
            assertEquals(0, message.variantIndex)
            assertTrue(message.variantSelected)
          }
          assertNewTablesEmpty(database)
          assertNull(database.worldBooks().manifest(session.id))
          assertNull(database.worldBooks().runtimeState(session.id))
          assertTrue(database.worldBooks().heads().isEmpty())
          database.openHelper.writableDatabase
            .query("PRAGMA foreign_key_check")
            .use { assertEquals(0, it.count) }
        } finally {
          database.close()
        }
      } finally {
        context.deleteDatabase(name)
      }
    }

  private fun assertNewTablesEmpty(database: ZoneuDatabase) {
    val db = database.openHelper.writableDatabase
    val newTables =
      listOf(
        "world_book",
        "world_revision",
        "world_entry_identity",
        "world_role_bindings",
        "world_role_reference",
        "world_manifest",
        "world_session_reference",
        "world_runtime_state",
        "world_request_plan",
        "world_operation_receipt",
        "world_operation_cancellation",
      )
    newTables.forEach { table ->
      db.query("SELECT COUNT(*) FROM `$table`").use {
        assertTrue(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
    }
  }

  /** v1 has the v2 session/message/tool schema; v1→v2 only adds scheduled_task. */
  private fun createOldSchema(
    db: SupportSQLiteDatabase,
    version: Int,
  ) {
    val file = if (version == 1) "worldbooks/schema-2.json" else "worldbooks/schema-7.json"
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val schema = assets.open(file).bufferedReader().use { JSONObject(it.readText()) }
    val entities = schema.getJSONObject("database").getJSONArray("entities")
    for (index in 0 until entities.length()) {
      val entity = entities.getJSONObject(index)
      val table = entity.getString("tableName")
      if (version == 1 && table == "scheduled_task") continue
      db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
      val indices = entity.optJSONArray("indices")
      if (indices != null) {
        for (item in 0 until indices.length()) {
          db.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
        }
      }
    }
  }

  private fun seed(
    db: SupportSQLiteDatabase,
    version: Int,
  ) {
    db.execSQL(
      "INSERT INTO session(id,title,kind,created_at,updated_at,excluded) " +
        "VALUES ('synthetic-session','Synthetic legacy chat','Chat',1,2,0)",
    )
    db.execSQL(
      "INSERT INTO message(id,session_id,seq,role,content,status,created_at" +
        (if (version == 7) ",variant_index,variant_selected,anchor_id" else "") +
        ") VALUES ('synthetic-message','synthetic-session',1,'Assistant','synthetic reply','Complete',2" +
        (if (version == 7) ",2,1,'synthetic-anchor'" else "") + ")",
    )
    db.execSQL(
      "INSERT INTO tool_call(id,session_id,message_id,name,arguments,status,started_at) " +
        "VALUES ('synthetic-call','synthetic-session','synthetic-message','synthetic_tool','{}','Ok',2)",
    )
    if (version == 7) {
      db.execSQL(
        "INSERT INTO scheduled_task(id,title,prompt,hour,minute,enabled,schedule_type,weekday,revision,next_run_at) " +
          "VALUES ('synthetic-task','synthetic schedule','synthetic prompt',8,5,1,'weekly',2,'synthetic-revision',42)",
      )
    }
  }
}

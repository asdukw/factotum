package com.zoneu.app.schedule

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zoneu.app.data.db.ScheduledTaskEntity
import com.zoneu.app.data.db.ZoneuDatabase
import com.zoneu.app.data.db.ZoneuDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 合成任务与独立库，不打开或清理用户正式数据库，不调用真实 agent。 */
@RunWith(AndroidJUnit4::class)
class ScheduledTaskAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun migrationPreservesLegacyDailyTaskAndAddsNullableRuleFields() {
    val name = "schedule-migration-${UUID.randomUUID()}.db"
    val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
      .callback(object : SupportSQLiteOpenHelper.Callback(6) {
        override fun onCreate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "CREATE TABLE scheduled_task (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, " +
              "hour INTEGER NOT NULL, minute INTEGER NOT NULL, enabled INTEGER NOT NULL, last_run_at INTEGER, last_error TEXT)",
          )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
      }).build()
    val helper = FrameworkSQLiteOpenHelperFactory().create(config)
    try {
      val db = helper.writableDatabase
      db.execSQL("INSERT INTO scheduled_task VALUES ('fixture','title','prompt',8,30,1,NULL,NULL)")
      ZoneuDatabaseProvider.MIGRATION_6_7.migrate(db)
      db.query("SELECT * FROM scheduled_task WHERE id = 'fixture'").use {
        assertTrue(it.moveToFirst())
        assertEquals("daily", it.getString(it.getColumnIndexOrThrow("schedule_type")))
        assertEquals(8, it.getInt(it.getColumnIndexOrThrow("hour")))
        assertEquals(30, it.getInt(it.getColumnIndexOrThrow("minute")))
        assertEquals("prompt", it.getString(it.getColumnIndexOrThrow("prompt")))
        for (column in listOf("weekday", "day_of_month", "month", "date", "next_run_at")) {
          assertTrue(it.isNull(it.getColumnIndexOrThrow(column)))
        }
      }
    } finally {
      helper.close()
      context.deleteDatabase(name)
    }
  }

  @Test
  fun onceIsClaimedOnlyOnceAndDisabledEvenWhenExecutionFails() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, ZoneuDatabase::class.java).build()
    try {
      val dao = db.scheduledTasks()
      val task = ScheduledTaskEntity(
        "fixture",
        "title",
        "prompt",
        9,
        0,
        true,
        scheduleType = "once",
        date = "2027-01-05",
        revision = "v1",
        nextRunAt = 1000,
      )
      dao.upsert(task)
      assertEquals(1, dao.claim(task.id, "v1", 1000, 2000))
      assertEquals(0, dao.claim(task.id, "v1", 1000, 2001))
      assertFalse(requireNotNull(dao.byId(task.id)).enabled)
      assertNull(dao.byId(task.id)?.nextRunAt)
      assertEquals(1, dao.finish(task.id, "v1", 2000, "TURN_FAILED"))
      assertEquals("TURN_FAILED", dao.byId(task.id)?.lastError)
    } finally {
      db.close()
    }
  }

  @Test
  fun oldBroadcastOrRunCannotMutateEditedOrDeletedTask() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, ZoneuDatabase::class.java).build()
    try {
      val dao = db.scheduledTasks()
      val task = ScheduledTaskEntity("fixture", "title", "prompt", 9, 0, true, revision = "new", nextRunAt = 2000)
      dao.upsert(task)
      assertEquals(0, dao.claim(task.id, "old", 1000, 3000))
      assertEquals(0, dao.claim(task.id, "new", 1000, 3000))
      assertEquals(0, dao.claim(task.id, "new", 2000, 1999))
      assertEquals(1, dao.claim(task.id, "new", 2000, 3000))
      dao.upsert(task.copy(revision = "edited", enabled = false, nextRunAt = null))
      assertEquals(0, dao.finish(task.id, "new", 3000, null))
      assertEquals(0, dao.arm(task.id, "new", 4000))
      assertFalse(requireNotNull(dao.byId(task.id)).enabled)
      dao.delete(task.id)
      assertEquals(0, dao.finish(task.id, "edited", 3000, null))
      assertNull(dao.byId(task.id))
    } finally {
      db.close()
    }
  }
}

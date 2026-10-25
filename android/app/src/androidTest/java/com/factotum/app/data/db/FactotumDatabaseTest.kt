package com.factotum.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.factotum.app.FactotumApplication
import com.factotum.app.data.session.RoomSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * 真机上的 factotum.db：Room 建表 + SQLCipher 加密。
 *
 * 单测跑不了这一层 —— SQLCipher 的 libsqlcipher.so 只存在于 Android。
 * 这里刻意复用应用自己的库实例（而不是另开一个），验证的就是线上那条打开路径。
 */
@RunWith(AndroidJUnit4::class)
class FactotumDatabaseTest {
  private val app: FactotumApplication = ApplicationProvider.getApplicationContext()
  private val dao = app.database.store()
  private val store = RoomSessionStore(app.database)
  private var sessionId: String? = null

  @After
  fun tearDown() {
    runBlocking { sessionId?.let { dao.deleteSession(it) } }
  }

  @Test
  fun databaseFileIsNotPlaintextSqlite() {
    val file = app.getDatabasePath(FactotumDatabaseProvider.DATABASE_NAME)
    assertTrue("库文件不存在：${file.path}", file.isFile)

    val head = ByteArray(16)
    FileInputStream(file).use { assertEquals(16, it.read(head)) }
    assertFalse(
      "库文件头是明文 SQLite 签名，说明没走 SQLCipher",
      String(head, Charsets.ISO_8859_1).startsWith("SQLite format 3"),
    )
  }

  @Test
  fun plainSqliteCannotReadTheEncryptedFile() {
    val file: File = app.getDatabasePath(FactotumDatabaseProvider.DATABASE_NAME)
    val outcome =
      runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
          db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
      }
    assertTrue("明文 SQLite 竟然读到了加密库", outcome.isFailure)
  }

  @Test
  fun sessionAndMessagesRoundTrip() = runBlocking {
    val session = store.createSession(title = "测试会话")
    sessionId = session.id

    store.appendMessage(session.id, MessageRole.User, "你好", MessageStatus.Complete)
    val draft = store.appendMessage(session.id, MessageRole.Assistant, "半截", MessageStatus.Streaming)
    store.updateMessage(draft.id, "完整回复", "推理内容", MessageStatus.Complete, null, 1_700_000_000_000L)

    val rows = store.messages(session.id)
    assertEquals(2, rows.size)
    assertEquals(listOf(1L, 2L), rows.map { it.seq })
    assertEquals(MessageRole.User, rows[0].role)
    assertEquals("你好", rows[0].content)
    assertEquals(MessageRole.Assistant, rows[1].role)
    assertEquals("完整回复", rows[1].content)
    assertEquals("推理内容", rows[1].reasoning)
    assertEquals(MessageStatus.Complete, rows[1].status)
    assertEquals(1_700_000_000_000L, rows[1].finishedAt)
  }

  @Test
  fun deletingASessionCascadesMessagesAndToolCalls() = runBlocking {
    val session = store.createSession(title = null)
    store.appendMessage(session.id, MessageRole.User, "查一下天气", MessageStatus.Complete)
    val draft = store.appendMessage(session.id, MessageRole.Assistant, "", MessageStatus.Complete)

    dao.insertToolCall(
      ToolCallEntity(
        id = "call-1",
        messageId = draft.id,
        sessionId = session.id,
        name = "weather",
        arguments = """{"city":"北京"}""",
        result = """{"temp":21}""",
        status = ToolCallStatus.Ok,
        startedAt = 1L,
        finishedAt = 2L,
      ),
    )
    assertEquals(1, dao.toolCalls(session.id).size)

    dao.deleteSession(session.id)

    assertEquals(0, store.messages(session.id).size)
    assertEquals(0, dao.toolCalls(session.id).size)
    assertEquals(0, dao.countByStatus(session.id, MessageRole.User, MessageStatus.Complete))
  }

  @Test
  fun streamingMessagesAreReclassifiedOnStartup() = runBlocking {
    val session = store.createSession(title = null)
    sessionId = session.id
    store.appendMessage(session.id, MessageRole.Assistant, "半截回复", MessageStatus.Streaming)

    val changed = store.markStreamingAsInterrupted()
    assertTrue("至少要改判自己插的那一条", changed >= 1)

    val row = store.messages(session.id).single()
    assertEquals(MessageStatus.Interrupted, row.status)
  }

  @Test
  fun idsAreUuidV7() = runBlocking {
    val session = store.createSession(title = null)
    sessionId = session.id

    assertEquals(36, session.id.length)
    assertEquals('7', session.id[14])
    assertTrue("变体位应为 10xx", session.id[19] in listOf('8', '9', 'a', 'b'))
  }
}

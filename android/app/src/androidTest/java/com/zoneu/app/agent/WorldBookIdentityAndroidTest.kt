package com.zoneu.app.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zoneu.app.data.db.ZoneuDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/** Identity/tombstone tests use new, disposable Room files, never ZoneuApplication.database. */
@RunWith(AndroidJUnit4::class)
class WorldBookIdentityAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "synthetic-world-identity-${UUID.randomUUID()}.db"
  private lateinit var database: ZoneuDatabase
  private lateinit var repository: WorldBookRepository

  @Before
  fun open() {
    database = Room.databaseBuilder(context, ZoneuDatabase::class.java, name).build()
    repository = WorldBookRepository(database)
  }

  @After
  fun close() {
    database.close()
    context.deleteDatabase(name)
  }

  @Test
  fun repeatedImportReturnsTheEditedHeadWithoutOverwritingOrWritingAnotherReceipt() =
    runBlocking {
      val original = imported()
      val saved = repository.create(original, "synthetic-import", "import-parameters")
      val edited = repository.update(saved.copy(name = "User edit"), saved.revision)
      database.withTransaction {
        val before = changes()
        val duplicate = repository.create(imported(), "repeat-import", "repeat-parameters")
        assertEquals(edited, duplicate)
        assertEquals(before, changes())
        assertNull(repository.receipt("repeat-import", "repeat-parameters"))
      }
      assertEquals(original.archive!!.fingerprint, database.worldBooks().head(saved.id)!!.fingerprint)
      assertEquals(saved, repository.read(saved.id, saved.revision))
    }

  @Test
  fun deletedOriginalFingerprintAndBookIdRemainReservedAcrossReopen() =
    runBlocking {
      val saved = repository.create(imported())
      repository.delete(saved.id, saved.revision, "synthetic-delete", "delete-parameters")
      val deletedRevision = repository.receipt("synthetic-delete", "delete-parameters")!!.revision
      assertNotEquals(saved.revision, deletedRevision)
      database.close()
      open()
      fails("WORLD_DUPLICATE_DELETED") { repository.create(imported()) }
      fails("WORLD_ID_CONFLICT") { repository.create(WorldBook(id = saved.id, name = "Not a resurrection")) }
      fails("WORLD_NOT_FOUND") { repository.read(saved.id, saved.revision) }
      repository.delete(saved.id, saved.revision, "synthetic-delete", "delete-parameters")
      assertTrue(database.worldBooks().head(saved.id)!!.deleted)
      assertTrue(repository.list().isEmpty())
    }

  @Test
  fun explicitCopyGetsFreshBookAndEntryIdsAndReplayDoesNotCreateAnotherCopy() =
    runBlocking {
      val saved = repository.create(imported())
      val copy = repository.copy(saved.id, saved.revision, "synthetic-copy", "copy-parameters")
      assertNotEquals(saved.id, copy.id)
      assertNotEquals(saved.revision, copy.revision)
      assertNotEquals(saved.entries.single().id, copy.entries.single().id)
      assertEquals(saved.archive, copy.archive)
      assertNull(database.worldBooks().head(copy.id)!!.fingerprint)
      val edited = repository.update(copy.copy(description = "Later edit"), copy.revision)
      assertEquals(edited, repository.copy(saved.id, saved.revision, "synthetic-copy", "copy-parameters"))
      assertEquals(2, repository.list().size)
      assertEquals(saved.id, repository.create(imported()).id)
    }

  @Test
  fun removedEntryIdentityCannotReappearOrMoveToAnotherBook() =
    runBlocking {
      val saved = repository.create(WorldBook(name = "Synthetic", entries = listOf(entry())))
      val oldEntry = saved.entries.single()
      val emptied = repository.update(saved.copy(entries = emptyList()), saved.revision)
      fails("WORLD_ENTRY_CONFLICT") {
        repository.update(emptied.copy(entries = listOf(oldEntry)), emptied.revision)
      }
      fails("WORLD_ENTRY_CONFLICT") { repository.create(WorldBook(entries = listOf(oldEntry))) }
      assertEquals(1, repository.list().size)
      assertEquals(emptied, repository.read(saved.id))
      assertEquals(saved.id, database.worldBooks().entryIdentity(oldEntry.id)!!.bookId)
    }

  @Test
  fun duplicateEntryIdsFailAtomicallyAndSameBodyIsNotAnIdentity() =
    runBlocking {
      val row = entry()
      val draft = WorldBook(entries = listOf(row, row))
      fails("WORLD_ENTRY_CONFLICT") { repository.create(draft, "duplicate-entry", "duplicate-parameters") }
      assertNull(database.worldBooks().head(draft.id))
      assertNull(repository.receipt("duplicate-entry", "duplicate-parameters"))
      val separate = repository.create(draft.copy(entries = listOf(row, row.copy(id = UUID.randomUUID().toString()))))
      assertEquals(2, separate.entries.size)
      val other = repository.create(WorldBook(entries = listOf(entry())))
      assertNotEquals(separate.id, other.id)
      assertEquals(2, repository.list().size)
    }

  @Test
  fun exactNoopReadsAndUsageNeverWriteOrCreateReceipts() =
    runBlocking {
      val saved = repository.create(WorldBook(entries = listOf(entry())))
      database.withTransaction {
        val before = changes()
        assertEquals(saved, repository.update(saved, saved.revision, "no-change", "same-parameters"))
        assertEquals(saved, repository.read(saved.id))
        assertEquals(listOf(saved), repository.list())
        assertFalse(repository.usage(saved.id).referenced)
        assertNull(repository.receipt("no-change", "same-parameters"))
        assertEquals(before, changes())
      }
    }

  @Test
  fun createReplayIgnoresGeneratedIdsButCannotAcceptADifferentAssetIntent() =
    runBlocking {
      val draft = WorldBook(name = "Synthetic create", entries = listOf(entry()))
      val first = repository.create(draft, "create-retry")
      val edited = repository.update(first.copy(description = "Later user edit"), first.revision)
      val retried = draft.copy(
        id = UUID.randomUUID().toString(),
        entries = draft.entries.map { it.copy(id = UUID.randomUUID().toString()) },
      )
      assertEquals(edited, repository.create(retried, "create-retry"))
      fails("WORLD_REQUEST_CONFLICT") { repository.create(retried.copy(name = "Different create intent"), "create-retry") }
      assertEquals(1, repository.list().size)
    }

  private fun entry() = WorldBookEntry(rule = WorldEntry(keys = listOf("synthetic"), content = "Synthetic lore"))

  private fun imported(): WorldBook {
    val root = Json.parseToJsonElement("""{"entries":[{"content":"Synthetic lore"}],"extension":{"preserved":true}}""")
    return WorldBook(
      name = "Synthetic imported book",
      entries = listOf(entry()),
      archive =
        WorldBookArchive(
          root = root,
          fingerprint =
            MessageDigest
              .getInstance("SHA-256")
              .digest(root.toString().toByteArray())
              .joinToString("") { "%02x".format(it) },
        ),
    )
  }

  private fun changes(): Long =
    database.openHelper.writableDatabase.query("SELECT total_changes()").use {
      check(it.moveToFirst())
      it.getLong(0)
    }

  private suspend fun fails(
    code: String,
    block: suspend () -> Unit,
  ) {
    val failure = runCatching { block() }.exceptionOrNull()
    assertTrue("Expected $code, got ${failure?.javaClass?.simpleName}", failure is WorldBookException)
    assertEquals(code, (failure as WorldBookException).code)
  }
}

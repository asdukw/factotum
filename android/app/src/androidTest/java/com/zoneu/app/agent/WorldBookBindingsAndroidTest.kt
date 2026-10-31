package com.zoneu.app.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zoneu.app.data.db.SessionEntity
import com.zoneu.app.data.db.SessionKind
import com.zoneu.app.data.db.ZoneuDatabase
import com.zoneu.app.data.session.RoomSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Session snapshots use isolated synthetic Room databases, never user data or card files. */
@RunWith(AndroidJUnit4::class)
class WorldBookBindingsAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "synthetic-world-binding-${UUID.randomUUID()}.db"
  private lateinit var database: ZoneuDatabase
  private lateinit var repository: WorldBookRepository
  private lateinit var sessions: RoomSessionStore

  @Before
  fun open() {
    database = Room.databaseBuilder(context, ZoneuDatabase::class.java, name).build()
    repository = WorldBookRepository(database)
    sessions = RoomSessionStore(database)
  }

  @After
  fun close() {
    database.close()
    context.deleteDatabase(name)
  }

  @Test
  fun legacySessionReadsAndBranchesNeverAdoptNewRoleDefaults() =
    runBlocking {
      val oldId = "synthetic-old-session"
      database.store().insertSession(SessionEntity(oldId, "Synthetic legacy", SessionKind.Roleplay, 1, 1, characterId = "role"))
      val book = repository.create(WorldBook())
      repository.setRoleBindings(WorldRoleBindings("role", bookIds = listOf(book.id)), "empty")
      assertNull(repository.manifest(oldId))
      assertEquals(WorldRuntimeState(), repository.runtimeState(oldId))
      val branch = sessions.createSession(null, SessionKind.Roleplay, "role", worldParentId = oldId)
      assertTrue(repository.manifest(branch.id)!!.books.isEmpty())
      assertNull(repository.manifest(oldId))
      val future = sessions.createSession(null, SessionKind.Roleplay, "role")
      assertEquals(
        book.id,
        repository
          .manifest(future.id)!!
          .books
          .single()
          .bookId,
      )
      val explicit = repository.setManifest(WorldManifest(oldId, books = listOf(WorldBookBinding(book.id, book.revision))), "empty")
      assertEquals(book.id, explicit.books.single().bookId)
      assertNotEquals("empty", explicit.revision)
    }

  @Test
  fun failedSessionInitializationRollsBackTheSessionAndCastTogether() =
    runBlocking {
      fails("WORLD_SESSION_NOT_FOUND") {
        sessions.createSession(null, SessionKind.Roleplay, "a", characterIds = listOf("a", "b"), worldParentId = "missing-parent")
      }
      database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM session").use {
        check(it.moveToFirst())
        assertEquals(0, it.getInt(0))
      }
      assertTrue(database.worldBooks().heads().isEmpty())
      assertNull(database.store().latestSession(SessionKind.Roleplay))
    }

  @Test
  fun manifestPinsRequireExistingBookAndRevisionAndCannotBeWrittenForDailySessions() =
    runBlocking {
      val book = repository.create(WorldBook())
      val session = sessions.createSession(null, SessionKind.Roleplay, "role")
      val initial = repository.manifest(session.id)!!
      fails("WORLD_NOT_FOUND") {
        repository.setManifest(initial.copy(books = listOf(WorldBookBinding(book.id, UUID.randomUUID().toString()))), initial.revision)
      }
      fails("WORLD_NOT_FOUND") {
        val missingBook = WorldBookBinding(UUID.randomUUID().toString(), book.revision)
        repository.setManifest(initial.copy(books = listOf(missingBook)), initial.revision)
      }
      assertEquals(initial, repository.manifest(session.id))
      fails("WORLD_SESSION_NOT_FOUND") { repository.setManifest(WorldManifest("missing"), "empty") }
      val daily = sessions.createSession(null)
      fails("WORLD_NOT_ROLEPLAY") { repository.setManifest(WorldManifest(daily.id), "empty") }
      assertNull(repository.manifest(daily.id))
    }

  @Test
  fun manifestReplayPrecedesCasAndReturnsTheCurrentObjectWithoutRevertingIt() =
    runBlocking {
      val book = repository.create(WorldBook())
      val session = sessions.createSession(null, SessionKind.Roleplay, "role")
      val empty = repository.manifest(session.id)!!
      val firstDraft = empty.copy(books = listOf(WorldBookBinding(book.id, book.revision, listOf("direct"))))
      val first = repository.setManifest(firstDraft, empty.revision, "first-binding", "binding-parameters")
      val second =
        repository.setManifest(
          first.copy(books = first.books.map { it.copy(override = WorldOverride.DISABLE) }),
          first.revision,
        )
      val reloaded = firstDraft.copy(books = firstDraft.books.map { it.copy(epoch = UUID.randomUUID().toString()) })
      assertEquals(second, repository.setManifest(reloaded, empty.revision, "first-binding", "binding-parameters"))
      fails("WORLD_REVISION_CONFLICT") { repository.setManifest(firstDraft, empty.revision) }
      assertNotEquals(first.books.single().epoch, second.books.single().epoch)
      assertEquals(listOf(session.id), repository.usage(book.id).sessionIds)
    }

  @Test
  fun roleBindingReplayIsCasSafeAndDisabledBooksStillPreventDeletion() =
    runBlocking {
      val book = repository.create(WorldBook(enabled = false))
      val draft = WorldRoleBindings("role", bookIds = listOf(book.id, book.id))
      val first = repository.setRoleBindings(draft, "empty", "bind-role", "role-parameters")
      assertEquals(listOf(book.id), first.bookIds)
      assertEquals(listOf("role"), repository.usage(book.id).characterIds)
      fails("WORLD_REFERENCED") { repository.delete(book.id, book.revision) }
      val removed = repository.setRoleBindings(first.copy(bookIds = emptyList()), first.revision)
      assertEquals(removed, repository.setRoleBindings(draft, "empty", "bind-role", "role-parameters"))
      assertTrue(repository.usage(book.id).characterIds.isEmpty())
      repository.delete(book.id, book.revision)
    }

  @Test
  fun preparedPlanRejectsAStaleManifestAndSuccessfulSettlementNeedsEvidence() =
    runBlocking {
      val session = sessions.createSession(null, SessionKind.Roleplay, "role")
      val manifest = repository.manifest(session.id)!!
      // 具名参数：这个构造器随着 requestKind / advanceTurn 的加入挪过位置，
      // 位置参数一旦错位，编译器会把它报成 Map.Entry.copy 之类完全不相干的错。
      val plan =
        WorldRequestPlan(
          requestId = "request",
          sessionId = session.id,
          bindingRevision = manifest.revision,
          generation = "send",
          turn = 1,
          seed = 4,
          injections = emptyList(),
          decisions = emptyList(),
          activated = emptyList(),
        )
      val book = repository.create(WorldBook())
      val updated = repository.setManifest(manifest.copy(books = listOf(WorldBookBinding(book.id, book.revision))), manifest.revision)
      fails("WORLD_REVISION_CONFLICT") { repository.savePlan(plan) }
      assertNull(repository.plan(plan.requestId))
      assertEquals(WorldRuntimeState(), repository.runtimeState(session.id))
      val saved = repository.savePlan(plan.copy(bindingRevision = updated.revision))
      fails("WORLD_EVIDENCE_REQUIRED") { repository.settlePlan(saved.requestId, true, "  ") }
      assertEquals("prepared", repository.plan(saved.requestId)!!.status)
      assertEquals(WorldRuntimeState(), repository.runtimeState(session.id))
      val settled = repository.settlePlan(saved.requestId, true, "synthetic-message-id")
      assertEquals("settled", settled.status)
      assertEquals(1, repository.runtimeState(session.id).turn)
    }

  @Test
  fun bindingNoopsAndMissingReadsDoNotWriteReceiptsOrRows() =
    runBlocking {
      val session = sessions.createSession(null, SessionKind.Roleplay, "role")
      val manifest = repository.manifest(session.id)!!
      database.withTransaction {
        val before = changes()
        assertEquals(WorldRoleBindings("unbound"), repository.roleBindings("unbound"))
        assertNull(repository.manifest("missing"))
        assertEquals(WorldRuntimeState(), repository.runtimeState("missing"))
        assertNull(repository.plan("missing"))
        val role = repository.setRoleBindings(WorldRoleBindings("unbound"), "empty", "noop-role", "noop-role-parameters")
        assertEquals(WorldRoleBindings("unbound"), role)
        assertEquals(manifest, repository.setManifest(manifest, manifest.revision, "noop-manifest", "noop-manifest-parameters"))
        assertNull(repository.receipt("noop-role", "noop-role-parameters"))
        assertNull(repository.receipt("noop-manifest", "noop-manifest-parameters"))
        assertEquals(before, changes())
      }
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

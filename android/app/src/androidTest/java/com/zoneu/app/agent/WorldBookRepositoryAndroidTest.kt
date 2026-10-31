package com.zoneu.app.agent

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zoneu.app.data.db.SessionEntity
import com.zoneu.app.data.db.SessionKind
import com.zoneu.app.data.db.WorldBookRevisionEntity
import com.zoneu.app.data.db.ZoneuDatabase
import com.zoneu.app.data.session.RoomSessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Room transactions, isolated synthetic data. Never opens the application's database. */
@RunWith(AndroidJUnit4::class)
class WorldBookRepositoryAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var database: ZoneuDatabase
  private lateinit var repository: WorldBookRepository
  private lateinit var sessions: RoomSessionStore
  private val databaseName = "world-repository-${UUID.randomUUID()}.db"

  @Before
  fun open() = reopen()

  @After
  fun close() {
    database.close()
    context.deleteDatabase(databaseName)
  }

  @Test
  fun immutableRevisionsCasReplayAndReopenDoNotOverwriteLaterEdits() =
    runBlocking {
      val original = repository.create(book())
      val draft = original.copy(name = "First edit")
      val first = repository.update(draft, original.revision, "first-request", "first-parameters")
      assertNotEquals(original.revision, first.revision)
      assertEquals(original, repository.read(original.id, original.revision))
      fails("WORLD_REVISION_CONFLICT") { repository.update(draft.copy(name = "stale"), original.revision) }
      val second = repository.update(first.copy(name = "Second edit"), first.revision)
      database.close()
      reopen()
      assertEquals(second, repository.update(draft, original.revision, "first-request", "first-parameters"))
      fails("WORLD_REQUEST_CONFLICT") {
        repository.update(draft.copy(name = "different actual write"), original.revision, "first-request", "first-parameters")
      }
      assertEquals(first.revision, repository.receipt("first-request", "first-parameters")!!.revision)
      assertEquals(second, repository.read(original.id))
      assertFalse(repository.cancelOperation("first-request", "first-parameters"))
    }

  @Test
  fun actualParametersAreCheckedWhenTheOptionalParameterStringIsEmpty() =
    runBlocking {
      val current = repository.create(book())
      val saved = repository.update(current.copy(description = "one"), current.revision, "same-request")
      fails("WORLD_REQUEST_CONFLICT") {
        repository.update(current.copy(description = "two"), current.revision, "same-request")
      }
      assertEquals(saved, repository.read(current.id))
    }

  @Test
  fun cancellationBeforeMutationIsDurableAndAnExistingReceiptAlwaysWins() =
    runBlocking {
      val current = repository.create(book())
      assertTrue(repository.cancelOperation("cancelled-request", "fixed-parameters"))
      database.close()
      reopen()
      fails("WORLD_REQUEST_CANCELLED") {
        repository.update(current.copy(name = "must not write"), current.revision, "cancelled-request", "fixed-parameters")
      }
      fails("WORLD_REQUEST_CONFLICT") { repository.receipt("cancelled-request", "different-parameters") }
      fails("WORLD_REQUEST_CANCELLED") { repository.receipt("cancelled-request", "fixed-parameters") }
      assertEquals(current, repository.read(current.id))
    }

  @Test
  fun transactionFailureRollsBackContentAndReceiptTogether() =
    runBlocking {
      val original = repository.create(book())
      val attempt =
        runCatching {
          database.withTransaction {
            repository.update(original.copy(name = "rolled back"), original.revision, "rollback-request", "rollback-parameters")
            error("synthetic transaction failure")
          }
        }
      assertTrue(attempt.isFailure)
      assertEquals(original, repository.read(original.id))
      assertNull(repository.receipt("rollback-request", "rollback-parameters"))
      val row = database.worldBooks().revision(original.id, original.revision)!!
      val duplicate = runCatching { database.worldBooks().insertRevision(WorldBookRevisionEntity(row.bookId, row.revision, "{}")) }
      assertTrue("The revision primary key must reject replacement", duplicate.isFailure)
    }

  @Test
  fun competingUpdatesHaveExactlyOneWinner() =
    runBlocking {
      val original = repository.create(book())
      val results =
        coroutineScope {
          listOf("one", "two")
            .map { name ->
              async { runCatching { repository.update(original.copy(name = name), original.revision) } }
            }.awaitAll()
        }
      assertEquals(1, results.count { it.isSuccess })
      assertEquals("WORLD_REVISION_CONFLICT", (results.single { it.isFailure }.exceptionOrNull() as WorldBookException).code)
    }

  @Test
  fun roleAndDisabledSessionReferencesBlockDeletionAndSessionDeletionCascades() =
    runBlocking {
      val current = repository.create(book())
      val bindings = repository.setRoleBindings(WorldRoleBindings("character-a", bookIds = listOf(current.id)), "empty")
      val session = sessions.createSession(null, SessionKind.Roleplay, "character-a")
      val initial = repository.manifest(session.id)!!
      assertEquals(listOf("character:character-a"), initial.books.single().sources)
      val disabled =
        repository.setManifest(
          initial.copy(books = initial.books.map { it.copy(override = WorldOverride.DISABLE) }),
          initial.revision,
        )
      repository.setRoleBindings(bindings.copy(bookIds = emptyList()), bindings.revision)
      fails("WORLD_REFERENCED") { repository.delete(current.id, current.revision) }
      assertEquals(listOf(session.id), repository.usage(current.id).sessionIds)
      assertEquals(WorldOverride.DISABLE, disabled.books.single().override)
      val plan = requestPlan(session.id, disabled.revision)
      repository.savePlan(plan)
      repository.settlePlan(plan.requestId, true, "synthetic durable assistant receipt")
      sessions.deleteSession(session.id)
      assertNull(repository.manifest(session.id))
      assertNull(repository.plan(plan.requestId))
      assertEquals(WorldRuntimeState(), repository.runtimeState(session.id))
      repository.delete(current.id, current.revision)
      fails("WORLD_NOT_FOUND") { repository.read(current.id) }
    }

  @Test
  fun groupsPinOnceFutureDefaultsDoNotChangeExistingSessionsAndBranchesUseActualParent() =
    runBlocking {
      val first = repository.create(book())
      repository.setRoleBindings(WorldRoleBindings("a", bookIds = listOf(first.id)), "empty")
      repository.setRoleBindings(WorldRoleBindings("b", bookIds = listOf(first.id)), "empty")
      val group = sessions.createSession(null, SessionKind.Roleplay, "a", characterIds = listOf("a", "b"))
      assertEquals(listOf("a", "b"), group.cast)
      val manifest = repository.manifest(group.id)!!
      assertEquals(1, manifest.books.size)
      assertEquals(listOf("character:a", "character:b"), manifest.books.single().sources)
      val changed = repository.update(first.copy(description = "new revision"), first.revision)
      assertEquals(
        first.revision,
        repository
          .manifest(group.id)!!
          .books
          .single()
          .revision,
      )
      val upgraded = repository.setManifest(
        manifest.copy(books = manifest.books.map { it.copy(revision = changed.revision) }),
        manifest.revision,
      )
      val branch =
        sessions.createSession(
          null,
          SessionKind.Roleplay,
          "a",
          parentId = "sidebar-root",
          characterIds = group.cast,
          worldParentId = group.id,
        )
      val branchManifest = repository.manifest(branch.id)!!
      assertEquals("sidebar-root", branch.parentId)
      assertEquals(changed.revision, branchManifest.books.single().revision)
      assertNotEquals(upgraded.books.single().epoch, branchManifest.books.single().epoch)
      assertEquals(WorldRuntimeState(), repository.runtimeState(branch.id))
      val daily = sessions.createSession(null)
      assertNull(repository.manifest(daily.id))
    }

  @Test
  fun preparedPlanAndSettlementAreFixedAndIdempotent() =
    runBlocking {
      val session = sessions.createSession(null, SessionKind.Roleplay, "a")
      val manifest = repository.manifest(session.id)!!
      val plan = requestPlan(session.id, manifest.revision)
      assertEquals(plan, repository.savePlan(plan))
      assertEquals(plan, repository.savePlan(plan))
      fails("WORLD_REQUEST_CONFLICT") { repository.savePlan(plan.copy(seed = plan.seed + 1)) }
      fails("WORLD_REQUEST_PENDING") { repository.savePlan(plan.copy(requestId = "another-request")) }
      val settled = repository.settlePlan(plan.requestId, true, "synthetic committed assistant row")
      assertEquals("settled", settled.status)
      assertEquals(settled, repository.settlePlan(plan.requestId, false, "late cancellation"))
      assertEquals(WorldRuntimeState(1, mapOf("keyword" to 1)), repository.runtimeState(session.id))
      val failure = plan.copy(requestId = "failed-request", turn = 2)
      repository.savePlan(failure)
      repository.settlePlan(failure.requestId, false, "synthetic cancellation")
      assertEquals(1, repository.runtimeState(session.id).turn)
      val evidenceHash = database.worldBooks().plan(plan.requestId)!!.evidenceHash!!
      assertEquals(64, evidenceHash.length)
      assertFalse(evidenceHash.contains("synthetic"))
      assertNotNull(evidenceHash)
    }

  @Test
  fun originalImportFingerprintSurvivesEditsCopiesReopenAndDeletion() =
    runBlocking {
      val root = Json.parseToJsonElement("""{"name":"Synthetic imported book","entries":[]}""")
      val imported = book().copy(archive = WorldBookArchive(root, fingerprint = "untrusted-alias"))
      val original = repository.create(imported, "import-request", "import-parameters")
      val edited = repository.update(original.copy(name = "Local edit"), original.revision)
      val duplicate = repository.create(imported.copy(id = UUID.randomUUID().toString()), "dedup-request", "dedup-parameters")
      assertEquals(edited, duplicate)
      assertNull(repository.receipt("dedup-request", "dedup-parameters"))
      val copy = repository.copy(edited.id, edited.revision)
      assertNotEquals(edited.id, copy.id)
      assertTrue(
        copy.entries
          .map { it.id }
          .intersect(edited.entries.map { it.id }.toSet())
          .isEmpty(),
      )
      repository.delete(edited.id, edited.revision, "delete-request", "delete-parameters")
      database.close()
      reopen()
      repository.delete(edited.id, edited.revision, "delete-request", "delete-parameters")
      fails("WORLD_DUPLICATE_DELETED") {
        repository.create(
          imported.copy(
            id = UUID.randomUUID().toString(),
            archive = imported.archive!!.copy(fingerprint = "another-alias"),
          ),
        )
      }
      fails("WORLD_NOT_FOUND") { repository.create(imported, "import-request", "import-parameters") }
      assertEquals(copy, repository.read(copy.id))
    }

  @Test
  fun noOpsPreserveRevisionsAndWriteNoReceipt() = runBlocking {
    val original = repository.create(book())
    assertEquals(original, repository.update(original, original.revision, "noop-request", "noop-parameters"))
    assertNull(repository.receipt("noop-request", "noop-parameters"))
    val changed = repository.update(original.copy(description = "Later edit"), original.revision)
    fails("WORLD_REVISION_CONFLICT") { repository.update(original, original.revision, "noop-request", "noop-parameters") }
    assertEquals(changed, repository.read(original.id))
    val session = sessions.createSession(null, SessionKind.Roleplay, "a")
    val manifest = repository.manifest(session.id)!!
    val before = database.worldBooks().manifest(session.id)!!
    assertEquals(manifest, repository.setManifest(manifest, manifest.revision, "manifest-noop", "manifest-noop-parameters"))
    assertEquals(before, database.worldBooks().manifest(session.id))
    assertNull(repository.receipt("manifest-noop", "manifest-noop-parameters"))
  }

  @Test
  fun copyingAMaximumLengthNameStaysWithinTheStoredLimit() =
    runBlocking {
      val original = repository.create(book().copy(name = "书".repeat(512)))
      val copy = repository.copy(original.id, original.revision, "copy-request", "copy-parameters")
      assertEquals(512, copy.name.length)
      assertTrue(copy.name.endsWith(" · 副本"))
      assertEquals(copy, repository.read(copy.id))
    }

  @Test
  fun deletionKeepsHeadAndReceiptOnAnExistingTombstoneRevision() =
    runBlocking {
      val original = repository.create(book())
      repository.delete(original.id, original.revision, "delete-request", "delete-parameters")
      val receipt = repository.receipt("delete-request", "delete-parameters")!!
      val head = database.worldBooks().head(original.id)!!
      assertTrue(head.deleted)
      assertEquals(receipt.revision, head.revision)
      val row = database.worldBooks().revision(original.id, receipt.revision)
      assertNotNull("The head must point at a stored revision", row)
      fails("WORLD_NOT_FOUND") { repository.read(original.id) }
      // Replaying the committed deletion after a reopen must not write again.
      database.close()
      reopen()
      repository.delete(original.id, original.revision, "delete-request", "delete-parameters")
      assertEquals(receipt, repository.receipt("delete-request", "delete-parameters"))
    }

  @Test
  fun forgettingADeletedCardClearsItsDefaultsAndUnblocksBookDeletion() =
    runBlocking {
      val original = repository.create(book())
      repository.setRoleBindings(WorldRoleBindings("character-gone", bookIds = listOf(original.id)), "empty")
      assertEquals(listOf("character-gone"), repository.usage(original.id).characterIds)
      fails("WORLD_REFERENCED") { repository.delete(original.id, original.revision) }
      repository.forgetCharacter("character-gone")
      assertTrue(repository.usage(original.id).characterIds.isEmpty())
      repository.delete(original.id, original.revision)
      fails("WORLD_NOT_FOUND") { repository.read(original.id) }
    }

  @Test
  fun removedEntryAndDeletedBookIdentitiesAreNeverRecycled() =
    runBlocking {
      val original = repository.create(book())
      val empty = repository.update(original.copy(entries = emptyList()), original.revision)
      fails("WORLD_ENTRY_CONFLICT") { repository.update(empty.copy(entries = original.entries), empty.revision) }
      repository.delete(empty.id, empty.revision)
      fails("WORLD_ID_CONFLICT") { repository.create(original.copy(revision = "")) }
      fails("WORLD_ENTRY_CONFLICT") { repository.create(original.copy(id = UUID.randomUUID().toString(), revision = "")) }
      assertTrue(repository.list().isEmpty())
    }

  @Test
  fun legacyRoleplayDoesNotAcquireLaterDefaultsAndMissingParentRollsBackSessionCreation() =
    runBlocking {
      val legacyId = "synthetic-legacy-roleplay"
      database.store().insertSession(SessionEntity(legacyId, null, SessionKind.Roleplay, 1, 1, characterId = "a"))
      val book = repository.create(book())
      repository.setRoleBindings(WorldRoleBindings("a", bookIds = listOf(book.id)), "empty")
      assertNull(repository.manifest(legacyId))
      val branch = sessions.createSession(null, SessionKind.Roleplay, "a", worldParentId = legacyId)
      assertTrue(repository.manifest(branch.id)!!.books.isEmpty())
      val fresh = sessions.createSession(null, SessionKind.Roleplay, "a")
      assertEquals(listOf(book.id), repository.manifest(fresh.id)!!.books.map { it.bookId })
      val count =
        database.openHelper.writableDatabase.query("SELECT count(*) FROM session").use {
          it.moveToFirst()
          it.getInt(0)
        }
      fails("WORLD_SESSION_NOT_FOUND") { sessions.createSession(null, SessionKind.Roleplay, "a", worldParentId = "missing-parent") }
      val after =
        database.openHelper.writableDatabase.query("SELECT count(*) FROM session").use {
          it.moveToFirst()
          it.getInt(0)
        }
      assertEquals(count, after)
    }

  @Test
  fun savePlanRejectsAStaleManifestAndStoresOnlyAnEvidenceFingerprint() =
    runBlocking {
      val session = sessions.createSession(null, SessionKind.Roleplay, "a")
      val initial = repository.manifest(session.id)!!
      val book = repository.create(book())
      val current = repository.setManifest(initial.copy(books = listOf(WorldBookBinding(book.id, book.revision))), initial.revision)
      fails("WORLD_REVISION_CONFLICT") { repository.savePlan(requestPlan(session.id, initial.revision)) }
      val plan = requestPlan(session.id, current.revision)
      repository.savePlan(plan)
      fails("WORLD_EVIDENCE_REQUIRED") { repository.settlePlan(plan.requestId, true, "") }
      val evidence = "synthetic durable message identifier"
      repository.settlePlan(plan.requestId, true, evidence)
      val receipt = database.worldBooks().plan(plan.requestId)!!
      assertEquals(worldHash(evidence), receipt.evidenceHash)
      assertFalse(receipt.payload.contains(evidence))
    }

  private fun reopen() {
    database = Room.databaseBuilder(context, ZoneuDatabase::class.java, databaseName).build()
    repository = WorldBookRepository(database)
    sessions = RoomSessionStore(database)
  }

  private fun book() = WorldBook(name = "Synthetic world", entries = listOf(WorldBookEntry(rule = WorldEntry(content = "Synthetic entry"))))

  private fun requestPlan(
    sessionId: String,
    bindingRevision: String,
  ) = WorldRequestPlan(
    requestId = UUID.randomUUID().toString(),
    sessionId = sessionId,
    bindingRevision = bindingRevision,
    generation = "send",
    turn = 1,
    seed = 4,
    injections = listOf(WorldInjection("entry", "keyword", "depth", 0, "system", "Synthetic selected entry")),
    decisions = emptyList(),
    activated = listOf("keyword"),
  )

  private suspend fun fails(
    code: String,
    body: suspend () -> Unit,
  ) {
    val error = runCatching { body() }.exceptionOrNull()
    assertTrue("Expected $code, got ${error?.javaClass?.simpleName}", error is WorldBookException)
    assertEquals(code, (error as WorldBookException).code)
  }
}

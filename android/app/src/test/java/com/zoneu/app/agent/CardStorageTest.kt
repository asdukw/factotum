package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fault injection tests storage and journal ordering independently of Android. */
class CardStorageTest {
  private val cards = mutableMapOf<String, CardSnapshot>()
  private val receipts = mutableMapOf<String, CardRequestReceipt>()
  private var failConfirmation = false
  private var failIntent = false
  private var failWrite = false
  private var failReadAfterWrite = false
  private var writes = 0
  private var nextReadFails = false

  private fun writeReceipt(receipt: CardRequestReceipt) {
    if (failConfirmation && receipt.committed) error("journal unavailable")
    if (failIntent && !receipt.committed) error("journal unavailable")
    receipts[receipt.request] = receipt
  }

  private fun storage() = CardStorage({ id ->
    if (nextReadFails) {
      nextReadFails = false
      error("read-back unavailable")
    }
    cards[id]
  }, { cards.values.toList() }, { card ->
    if (failWrite) error("disk unavailable")
    cards[card.id] = CardSnapshot(card, card.storageRevision!!, true)
    writes++
    if (failReadAfterWrite) nextReadFails = true
  }, { cards.remove(it) }, receipts::get, ::writeReceipt)

  private fun updater() = CardUpdateCommitter(storage())

  private fun imported() = CharacterImporter.fromBytes("""{"name":"角色","description":"原人设"}""".toByteArray(), "x").card

  private fun create() = storage().create(imported().copy(id = "c"))

  private fun update(base: CardSnapshot, request: String = "r") = updater().submit(request, "params", "c", base.revision) {
    CardUpdateProposal(
      it.copy(
        world = it.world + WorldEntry(
          content = "new",
          constant = true,
          sourceToken = "entry-$request",
        ),
      ),
      CardChangeCounts(added = 1),
    )
  }

  private fun assertCode(code: String, action: () -> Unit) {
    assertEquals(code, assertThrows(CardOperationException::class.java, action).code)
  }

  @Test
  fun twoWritersCasDeleteAndAbaNeverOverwriteNewerContent() {
    val first = create()
    val second = storage().update("c", first.revision) { it.copy(name = "B") }
    assertCode("CARD_REVISION_CONFLICT") { storage().update("c", first.revision) { it.copy(name = "stale") } }
    assertCode("CARD_REVISION_CONFLICT") { storage().delete("c", first.revision) }
    val third = storage().update("c", second.revision) { it.copy(name = first.card.name) }
    assertNotEquals(first.revision, third.revision)
    storage().delete("c", third.revision)
    assertCode("CARD_NOT_FOUND") { storage().update("c", third.revision) { it.copy(name = "revived") } }
    assertTrue(cards.isEmpty())
  }

  @Test
  fun competingUiAndToolWritesShareOneLockAndOnlyOneCasSucceeds() {
    val base = create()
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val results = (1..2).map { index ->
        pool.submit<Boolean> {
          start.await()
          runCatching { storage().update("c", base.revision) { it.copy(name = "writer-$index") } }.isSuccess
        }
      }
      start.countDown()
      assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
      assertEquals(2, writes)
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun createRequiresAbsenceReadOnlyIsRealOriginAndNoChangeDoesNotWrite() {
    val base = create()
    assertCode("CARD_ALREADY_EXISTS") { storage().create(base.card) }
    assertEquals(base, storage().update("c", base.revision) { it })
    assertEquals(1, writes)
    cards["c"] = base.copy(editable = false)
    assertCode("CARD_READ_ONLY") { storage().update("c", base.revision) { it.copy(name = "bad") } }
    assertCode("CARD_READ_ONLY") { storage().delete("c", base.revision) }
    assertCode("CARD_INVALID_ID") { storage().create(base.card.copy(id = "../bad")) }
  }

  @Test
  fun finalReceiptFailureBlocksUiWriteAndDeleteAndRecoversWithoutApplyingTwice() {
    val base = create()
    failConfirmation = true
    assertThrows(IllegalStateException::class.java) { update(base) }
    val applied = cards.getValue("c")
    assertEquals(1, applied.card.world.size)
    assertThrows(IllegalStateException::class.java) { storage().update("c", applied.revision) { it.copy(name = "UI") } }
    assertThrows(IllegalStateException::class.java) { storage().delete("c", applied.revision) }
    assertEquals(2, writes)
    failConfirmation = false
    val next = storage().update("c", applied.revision) { it.copy(name = "UI") }
    val replay = update(base)
    assertTrue(replay.replay)
    assertEquals(applied.revision, replay.appliedRevision)
    assertEquals(next.revision, replay.snapshot.revision)
    assertEquals("UI", replay.snapshot.card.name)
    assertEquals(1, replay.snapshot.card.world.size)
    storage().delete("c", next.revision)
    assertCode("CARD_PREVIOUSLY_DELETED") { update(base) }
  }

  @Test
  fun intentWriteAndCardWriteAndReadBackFaultWindowsNeverReportSuccess() {
    val base = create()
    failIntent = true
    assertThrows(IllegalStateException::class.java) { update(base) }
    assertEquals(base, cards["c"])
    failIntent = false
    failWrite = true
    assertThrows(IllegalStateException::class.java) { update(base) }
    assertEquals(base, cards["c"])
    failWrite = false
    failReadAfterWrite = true
    assertThrows(IllegalStateException::class.java) { update(base) }
    failReadAfterWrite = false
    val replay = update(base)
    assertTrue(replay.replay)
    assertEquals(1, replay.snapshot.card.world.size)
  }

  @Test
  fun cancelledConflictingAndUnchangedRequestsNeverReapply() {
    val base = create()
    assertCode("CARD_CANCELLED") {
      updater().submit("cancelled", "p", "c", base.revision, { throw CardOperationException("CARD_CANCELLED") }) {
        error("must not transform")
      }
    }
    val result = updater().submit("same", "p", "c", base.revision) { CardUpdateProposal(it) }
    assertTrue(result.unchanged)
    assertFalse(result.replay)
    assertEquals(1, writes)
    assertTrue(updater().submit("same", "p", "c", base.revision) { error("replay") }.replay)
    assertCode("CARD_REQUEST_CONFLICT") {
      updater().submit("same", "different", "c", base.revision) { error("conflict") }
    }
  }

  @Test
  fun legacyUiReorderingUsesRevisionBoundSlotsNotEqualEntryContents() {
    val entry = WorldEntry(content = "identical", keys = listOf("x"))
    val old = CharacterCard("c", "legacy", imported = true, world = listOf(entry, entry)).withStorage(CardStorageState("legacy-r"))
    val base = CardSnapshot(old, "legacy-r", true)
    cards["c"] = base
    val draft = CardStorageIdentity.draft(old)
    val changed = storage().update("c", base.revision) { draft.copy(world = listOf(draft.world[1].copy(content = "edited"))) }
    assertEquals("edited", changed.card.world.single().content)
    assertFalse(changed.card.world.single().sourceToken.startsWith("ui-legacy-"))
    assertCode("CARD_REVISION_CONFLICT") { storage().update("c", base.revision) { draft } }
  }

  @Test
  fun identicalLegacyReorderingIsARealChangeButUneditedDraftIsNotAMigration() {
    val entry = WorldEntry(content = "same", constant = true)
    val old = CharacterCard("c", "legacy", imported = true, world = listOf(entry, entry)).withStorage(CardStorageState("legacy-r"))
    cards["c"] = CardSnapshot(old, "legacy-r", true)
    val draft = CardStorageIdentity.draft(old)
    assertEquals("legacy-r", storage().update("c", "legacy-r") { draft }.revision)
    assertEquals(0, writes)
    val reordered = storage().update("c", "legacy-r") { draft.copy(world = draft.world.reversed()) }
    assertNotEquals("legacy-r", reordered.revision)
    assertEquals(2, reordered.card.world.map { it.sourceToken }.distinct().size)
    assertTrue(reordered.card.world.all { it.sourceToken.isNotBlank() && !it.sourceToken.startsWith("ui-legacy-") })
  }

  @Test
  fun invalidReservedLegacyIdentityCannotBeMaterializedOrSilentlyPassedAsNoop() {
    val old = CharacterCard("c", "legacy", imported = true, world = listOf(WorldEntry(content = "same", constant = true)))
      .withStorage(CardStorageState("legacy-r"))
    cards["c"] = CardSnapshot(old, "legacy-r", true)
    val draft = CardStorageIdentity.draft(old)
    assertCode("CARD_ENTRY_CONFLICT") {
      storage().update("c", "legacy-r") { draft.copy(world = draft.world.map { it.copy(sourceToken = "ui-legacy-invalid") }) }
    }
    assertEquals(0, writes)
  }

  @Test
  fun separateCreateAndUpdateCoordinatorsAcknowledgeEachOtherBeforeTheNextWrite() {
    failConfirmation = true
    assertThrows(IllegalStateException::class.java) {
      CardCommitter(storage()).submit("create-request", "create-params", imported(), CardDownloadControl())
    }
    val created = cards.values.single()
    assertThrows(IllegalStateException::class.java) {
      updater().submit("edit-request", "edit-params", created.card.id, created.revision) {
        CardUpdateProposal(it.copy(name = "next"), CardChangeCounts(changedFields = listOf("name")))
      }
    }
    assertFalse(receipts.containsKey("edit-request"))
    assertEquals(1, writes)
    failConfirmation = false
    val edited = updater().submit("edit-request", "edit-params", created.card.id, created.revision) {
      CardUpdateProposal(it.copy(name = "next"), CardChangeCounts(changedFields = listOf("name")))
    }
    assertTrue(receipts.getValue("create-request").committed)
    assertEquals(edited.snapshot.card, CardCommitter(storage()).existing("create-request", "create-params"))
  }

  @Test
  fun cancellationAfterIntentAndBeforeCardWriteCanNeverClaimSavedOrApplyTheCard() {
    val base = create()
    var checks = 0
    assertCode("CARD_CANCELLED") {
      updater().submit("cancel-window", "p", "c", base.revision, {
        checks++
        if (checks == 3) throw CardOperationException("CARD_CANCELLED")
      }) { CardUpdateProposal(it.copy(name = "cancelled")) }
    }
    assertEquals(base, cards["c"])
    assertFalse(receipts.getValue("cancel-window").committed)
    assertEquals(1, writes)
  }

  @Test
  fun editingCannotForgeCreationHashStorageEvidenceOrLeakItThroughExportOrPrompt() {
    val base = create()
    val changed = storage().update("c", base.revision) {
      it.copy(
        name = "edited",
        importMetadata = it.importMetadata!!.copy(contentHash = "forged"),
      )
        .withStorage(CardStorageState("forged"))
    }
    assertEquals(base.card.importMetadata!!.contentHash, changed.card.importMetadata!!.contentHash)
    assertNotEquals("forged", changed.revision)
    assertFalse(CardExporter.toV3(changed.card).contains("_zoneu_storage"))
    assertFalse(CharacterStore.renderPrompt(changed.card).contains(changed.revision))
  }

  @Test
  fun committedAndPendingAppliedReplaysWinOverLateCancellation() {
    val base = create()
    val applied = update(base)
    val cancelled = { throw CardOperationException("CARD_CANCELLED") }
    val replay = updater().submit("r", "params", "c", base.revision, cancelled) { error("replay must not transform") }
    assertTrue(replay.replay)
    assertEquals(applied.appliedRevision, replay.appliedRevision)
    val secondBase = applied.snapshot
    failConfirmation = true
    assertThrows(IllegalStateException::class.java) { update(secondBase, "pending") }
    failConfirmation = false
    val pendingReplay = updater().submit("pending", "params", "c", secondBase.revision, cancelled) {
      error("saved evidence must win over late cancellation")
    }
    assertTrue(pendingReplay.replay)
    assertEquals(2, pendingReplay.snapshot.card.world.size)
    assertEquals(3, writes)
  }

  @Test
  fun legacyNoopNewUuidDuplicateAndStaleReservedReferencesAreHandledWithoutGuessing() {
    val entry = WorldEntry(content = "same", constant = true)
    val old = CharacterCard("c", "legacy", imported = true, world = listOf(entry, entry)).withStorage(CardStorageState("legacy"))
    cards["c"] = CardSnapshot(old, "legacy", true)
    val draft = CardStorageIdentity.draft(old)
    assertEquals("legacy", storage().update("c", "legacy") { draft }.revision)
    assertEquals(0, writes)
    val reordered = storage().update("c", "legacy") { draft.copy(world = draft.world.reversed()) }
    assertEquals(1, writes)
    assertNotEquals("legacy", reordered.revision)
    assertEquals(2, reordered.card.world.map { it.sourceToken }.distinct().size)
    assertCode("CARD_ENTRY_CONFLICT") {
      storage().update("c", reordered.revision) { draft }
    }
    val uuid = java.util.UUID.randomUUID().toString()
    val added = storage().update("c", reordered.revision) {
      it.copy(world = it.world + entry.copy(sourceToken = uuid))
    }
    assertEquals(uuid, added.card.world.last().sourceToken)
    assertCode("CARD_ENTRY_CONFLICT") {
      storage().update("c", added.revision) { it.copy(world = it.world + it.world.last()) }
    }
  }

  @Test
  fun deletedCardIdReuseDoesNotTurnAReplacementIntoAnOldUpdateReplay() {
    val base = create()
    val applied = update(base)
    val instance = applied.snapshot.card.storageState()!!.instanceId
    assertTrue(instance.isNotBlank())
    val edited = storage().update("c", applied.snapshot.revision) { it.copy(name = "later") }
    assertEquals(instance, edited.card.storageState()!!.instanceId)
    storage().delete("c", edited.revision)
    val replacement = storage().create(imported().copy(id = "c"))
    assertNotEquals(instance, replacement.card.storageState()!!.instanceId)
    assertCode("CARD_PREVIOUSLY_DELETED") { update(base) }
    assertEquals(replacement, storage().snapshot("c"))
  }

  @Test
  fun appendedWorldBookKeepsExistingSourceTokensArchiveAndCreationIdentity() {
    val imported = CharacterImporter.fromBytes(
      (
        """{"name":"Card","description":"before","character_book":{"entries": [""" +
          """{"keys":["x"],"content":"same","extensions":{"custom":"original"}}]}}"""
      ).toByteArray(),
      "x",
    ).card
    val base = storage().create(imported.copy(id = "c"))
    val source = base.card.world.single().sourceToken
    val hash = base.card.importMetadata!!.contentHash
    val saved = storage().update("c", base.revision) {
      WorldBookIo.appendText(
        it,
        """{"name":"Second","entries":[{"keys":["y"],"content":"different","extensions":{"custom":"appended"}}]}""",
      )
    }
    assertEquals(source, saved.card.world.first().sourceToken)
    assertEquals(hash, saved.card.importMetadata!!.contentHash)
    assertTrue(saved.card.world.last().sourceToken.isNotBlank())
    val text = CardExporter.toV3(saved.card)
    assertTrue(text.contains("original"))
    assertTrue(text.contains("appended"))
    val unchanged = storage().update("c", saved.revision) {
      WorldBookIo.appendText(
        it,
        """{"name":"Second","entries":[{"keys":["y"],"content":"different","extensions":{"custom":"appended"}}]}""",
      )
    }
    assertEquals(saved.revision, unchanged.revision)
  }
}

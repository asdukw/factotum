package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CardCommitterTest {
  private val receipts = mutableMapOf<String, CardRequestReceipt>()
  private val saved = mutableMapOf<String, CharacterCard>()
  private var failSave = false
  private var failCommitReceipt = false

  private fun card() = CharacterImporter.fromBytes("""{"name":"角色","description":"人设"}""".toByteArray(), "x").card

  private fun writeReceipt(receipt: CardRequestReceipt) {
    if (failCommitReceipt && receipt.committed) error("simulated crash after card save")
    receipts[receipt.request] = receipt
  }

  private fun storage() = CardStorage(
    { id -> saved[id]?.let { CardSnapshot(it, it.storageRevision!!, true) } },
    { saved.values.map { CardSnapshot(it, it.storageRevision!!, true) } },
    {
      if (failSave) throw CardOperationException("CARD_SAVE_FAILED")
      saved[it.id] = it
    },
    { saved.remove(it) },
    receipts::get,
    ::writeReceipt,
  )

  private fun committer() = CardCommitter(storage())

  @Test
  fun sameCallAndSameContentAcrossDifferentCallsDoNotDuplicateOrOverwriteEdits() {
    val (created, duplicate) = committer().submit("r1", "params", card(), CardDownloadControl())
    assertFalse(duplicate)
    storage().update(created.id, created.storageRevision!!) { it.copy(name = "本地修改") }
    assertEquals("本地修改", committer().existing("r1", "params")!!.name)
    val (again, existing) = committer().submit("r2", "other", card(), CardDownloadControl())
    assertTrue(existing)
    assertEquals(created.id, again.id)
    assertEquals("本地修改", again.name)
    assertEquals(1, saved.size)
    assertThrows(CardOperationException::class.java) { committer().existing("r1", "different") }
  }

  @Test
  fun crashAfterCardSaveRecoversToSameIdWithoutWritingAnotherCard() {
    failCommitReceipt = true
    assertThrows(IllegalStateException::class.java) { committer().submit("r", "p", card(), CardDownloadControl()) }
    assertEquals(1, saved.size)
    val id = saved.keys.single()
    failCommitReceipt = false
    assertEquals(id, committer().existing("r", "p")!!.id)
    assertTrue(receipts.getValue("r").committed)
  }

  @Test
  fun failureBeforeSaveKeepsFixedTargetAndNeverReportsSuccess() {
    failSave = true
    assertThrows(CardOperationException::class.java) { committer().submit("r", "p", card(), CardDownloadControl()) }
    val id = receipts.getValue("r").targetId
    assertTrue(saved.isEmpty())
    failSave = false
    val (retried, _) = committer().submit("r", "p", card(), CardDownloadControl())
    assertEquals(id, retried.id)
    saved.clear()
    assertEquals(
      "CARD_PREVIOUSLY_DELETED",
      assertThrows(CardOperationException::class.java) {
        committer().existing("r", "p")
      }.code,
    )
  }

  @Test
  fun cancelledRequestCannotWriteAnIntentOrCard() {
    val control = CardDownloadControl().apply { cancel() }
    assertThrows(CardOperationException::class.java) { committer().submit("r", "p", card(), control) }
    assertTrue(receipts.isEmpty())
    assertTrue(saved.isEmpty())
  }

  @Test
  fun requestMetadataStaysPrivateDuringExportAndValidationRejectsUnknownFields() {
    val (saved, _) = committer().submit("r", "p", card(), CardDownloadControl())
    val text = CardExporter.toV3(saved)
    assertFalse(text.contains("contentHash"))
    assertFalse(text.contains("originUrl"))
    CharacterCardOperations.validateFields(Json.parseToJsonElement("""{"name":"名字","description":"人设"}""") as JsonObject)
    assertThrows(IllegalArgumentException::class.java) {
      CharacterCardOperations.validateFields(JsonObject(mapOf("name" to JsonPrimitive("名字"), "system_prompt" to JsonPrimitive("no"))))
    }
  }

  @Test
  fun oldPendingReceiptWithMissingTargetCannotResurrectIt() {
    receipts["legacy"] = CardRequestReceipt("legacy", "p", "missing", "hash")
    assertEquals(
      "CARD_RECOVERY_UNCERTAIN",
      assertThrows(CardOperationException::class.java) {
        committer().existing("legacy", "p")
      }.code,
    )
    assertTrue(saved.isEmpty())
  }

  @Test
  fun fileImportedAndOlderArchivedCardsDeduplicateAgainstToolImportsWithoutReplacingEdits() {
    val original = card()
    val imported = storage().create(
      original.copy(
        id = "from-file",
        importMetadata = original.importMetadata!!.copy(contentHash = ""),
      ),
    )
    assertTrue(imported.card.importMetadata!!.contentHash.isNotBlank())
    val edited = storage().update(imported.card.id, imported.revision) { it.copy(name = "保留文件导入后的编辑") }
    val (duplicate, existing) = committer().submit("new-tool-call", "p", card(), CardDownloadControl())
    assertTrue(existing)
    assertEquals(edited.card, duplicate)
    assertEquals(1, saved.size)
    saved[duplicate.id] = duplicate.copy(importMetadata = duplicate.importMetadata!!.copy(contentHash = ""))
    val (legacyDuplicate, legacyExisting) = committer().submit("legacy-source", "p2", card(), CardDownloadControl())
    assertTrue(legacyExisting)
    assertEquals(duplicate.id, legacyDuplicate.id)
    assertEquals("保留文件导入后的编辑", legacyDuplicate.name)
    assertEquals(1, saved.size)
  }

  @Test
  fun aDeletedCreationReceiptCannotClaimAReplacementWithTheSameIdAndContent() {
    val (created, _) = committer().submit("old-request", "p", card(), CardDownloadControl())
    storage().delete(created.id, created.storageRevision!!)
    val replacement = storage().create(card().copy(id = created.id))
    assertEquals(
      "CARD_PREVIOUSLY_DELETED",
      assertThrows(CardOperationException::class.java) {
        committer().existing("old-request", "p")
      }.code,
    )
    assertEquals(replacement.card, saved.getValue(created.id))
  }

  @Test
  fun savedCreationReplayWinsOverCancellation() {
    val (created, _) = committer().submit("r", "p", card(), CardDownloadControl())
    val cancelled = CardDownloadControl().apply { cancel() }
    val (replayed, duplicate) = committer().submit("r", "p", card(), cancelled)
    assertTrue(duplicate)
    assertEquals(created, replayed)
    assertEquals(1, saved.size)
  }
}

package com.zoneu.app.agent

import com.zoneu.app.device.DeviceActionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host routing tests use real patch/query/CAS; preview and no-op paths must remain read-only. */
class CharacterCardLibraryTest {
  private val cards = mutableMapOf<String, CardSnapshot>()
  private val receipts = mutableMapOf<String, CardRequestReceipt>()
  private var writes = 0
  private var journalWrites = 0
  private var confirmationFails = false
  private val storage = CardStorage(
    { cards[it] },
    { cards.values.toList() },
    { card ->
      cards[card.id] = CardSnapshot(card, card.storageRevision!!, true)
      writes++
    },
    { cards.remove(it) },
    receipts::get,
    { receipt ->
      if (confirmationFails && receipt.committed) error("receipt unavailable")
      receipts[receipt.request] = receipt
      journalWrites++
    },
  )
  private val library = CharacterCardLibrary(storage, CardPreviewBindings())

  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun payload(result: DeviceActionResult): JsonObject {
    assertTrue(result.summary, result.ok)
    return json(result.payload!!)
  }

  private fun create() = storage.create(CharacterCard("c", "角色", description = "保留经历", firstMes = "保留开场"))

  private fun patch(base: CardSnapshot, fields: String = """{"personality":"PRIVATE_PERSONALITY"}""") = json(
    """{"character_id":"c","expected_revision":"${base.revision}","fields":$fields}""",
  )

  private fun fingerprint(preview: DeviceActionResult) = payload(preview).getValue("fingerprint").jsonPrimitive.content

  @Test
  fun realDiffPreviewDoesNotWriteAndExecutionIsBoundToExactParametersAndCas() {
    val base = create()
    val args = patch(base)
    val beforeWrites = writes
    val preview = library.preview("host-diff", args)
    val text = preview.payload!!
    assertFalse(text.contains("PRIVATE_PERSONALITY"))
    assertFalse(text.contains("保留经历"))
    assertTrue(text.contains("before_length"))
    assertEquals(beforeWrites, writes)
    assertEquals(0, journalWrites)
    val fp = fingerprint(preview)
    assertEquals("CARD_PREVIEW_CONFLICT", library.update("host-diff", patch(base, """{"personality":"changed"}"""), fp).code)
    assertEquals("CARD_PREVIEW_REQUIRED", library.update("not-previewed", args, fp).code)
    assertEquals(beforeWrites, writes)
    val result = payload(library.update("host-diff", args, fp))
    assertEquals("updated", result.getValue("disposition").jsonPrimitive.content)
    assertEquals("保留经历", cards.getValue("c").card.description)
    assertEquals("保留开场", cards.getValue("c").card.firstMes)
    assertNotEquals(base.revision, cards.getValue("c").revision)
    assertEquals("CARD_REVISION_CONFLICT", library.preview("stale", args).code)
  }

  @Test
  fun unchangedExecutionNeverWritesCardOrJournalAndCannotBecomeMutationAfterPreview() {
    val base = create()
    val args = patch(base, """{"description":"保留经历"}""")
    val preview = library.preview("host-unchanged", args)
    assertEquals("true", payload(preview).getValue("unchanged").jsonPrimitive.content)
    val beforeWrites = writes
    val receipt = payload(library.update("host-unchanged", args, fingerprint(preview)))
    assertEquals("unchanged", receipt.getValue("disposition").jsonPrimitive.content)
    assertEquals(beforeWrites, writes)
    assertEquals(0, journalWrites)
    assertEquals(base, storage.snapshot("c"))
    storage.update("c", base.revision) { it.copy(description = "外部修改") }
    val count = writes
    assertEquals("CARD_REVISION_CONFLICT", library.update("host-unchanged", args, fingerprint(preview)).code)
    assertEquals(count, writes)
    assertEquals(0, journalWrites)
  }

  @Test
  fun appliedReplayPreviewsBeforeRevisionCheckAndDeletedCardNeverReappears() {
    val base = create()
    val args = patch(base)
    val firstPreview = library.preview("host-replay", args)
    val first = payload(library.update("host-replay", args, fingerprint(firstPreview)))
    val applied = storage.snapshot("c")!!
    val ui = storage.update("c", applied.revision) { it.copy(name = "后续人工修改") }
    val beforeWrites = writes
    val beforeJournal = journalWrites
    val repeated = library.preview("host-replay", args)
    assertEquals("true", payload(repeated).getValue("already_applied").jsonPrimitive.content)
    assertEquals(beforeJournal, journalWrites)
    val replay = payload(library.update("host-replay", args, fingerprint(repeated)))
    assertEquals("true", replay.getValue("replay").jsonPrimitive.content)
    assertEquals(first["applied_revision"], replay["applied_revision"])
    assertEquals(ui.revision, replay.getValue("revision").jsonPrimitive.content)
    assertEquals("后续人工修改", cards.getValue("c").card.name)
    assertEquals(beforeWrites, writes)
    storage.delete("c", ui.revision)
    assertEquals("CARD_PREVIOUSLY_DELETED", library.preview("host-replay", args).code)
    assertFalse(library.update("host-replay", args, fingerprint(repeated)).ok)
    assertTrue(cards.isEmpty())
  }

  @Test
  fun previewRejectsAnOldAppliedRequestWhenItsCardIdHasBeenRecreated() {
    val base = create()
    val args = patch(base)
    val preview = library.preview("old-instance", args)
    payload(library.update("old-instance", args, fingerprint(preview)))
    val old = storage.snapshot("c")!!
    storage.delete("c", old.revision)
    val replacement = storage.create(CharacterCard("c", "replacement", description = "new"))
    assertEquals("CARD_PREVIOUSLY_DELETED", library.preview("old-instance", args).code)
    assertFalse(library.update("old-instance", args, fingerprint(preview)).ok)
    assertEquals(replacement, storage.snapshot("c"))
  }

  @Test
  fun pendingReplayPreviewIsReadOnlyAndExecutionAcknowledgesWithoutApplyingTwice() {
    val base = create()
    val args = json(
      """{"character_id":"c","expected_revision":"${base.revision}",
        "world_ops":[{"op":"add","fields":{"content":"PRIVATE_WORLD"}}]}""",
    )
    val first = library.preview("host-pending", args)
    confirmationFails = true
    assertFalse(library.update("host-pending", args, fingerprint(first)).ok)
    val applied = storage.snapshot("c")!!
    assertEquals(1, applied.card.world.size)
    val beforeWrites = writes
    val beforeJournal = journalWrites
    val repeated = library.preview("host-pending", args)
    assertEquals("true", payload(repeated).getValue("already_applied").jsonPrimitive.content)
    assertEquals(beforeWrites, writes)
    assertEquals(beforeJournal, journalWrites)
    assertEquals("CARD_RECOVERY_UNCERTAIN", library.preview("new-request", patch(applied)).code)
    confirmationFails = false
    val replay = payload(library.update("host-pending", args, fingerprint(repeated)))
    assertEquals("true", replay.getValue("replay").jsonPrimitive.content)
    assertEquals(1, cards.getValue("c").card.world.size)
    assertEquals(beforeWrites, writes)
    assertTrue(receipts.getValue("host-pending").committed)
  }

  @Test
  fun missingJournalEvidenceFailsClosedAndReadToolsNeverExposePrivateState() {
    val base = create()
    val args = patch(base)
    val preview = library.preview("lost-prior", args)
    confirmationFails = true
    assertFalse(library.update("lost-prior", args, fingerprint(preview)).ok)
    receipts.clear()
    val current = storage.snapshot("c")!!
    val count = writes
    assertEquals("CARD_RECOVERY_UNCERTAIN", library.preview("lost-prior", patch(current)).code)
    assertEquals(count, writes)
    val read = payload(library.read(json("""{"character_id":"c","section":"description"}""")))
    assertEquals("zoneu.character-card.read.v1", read.getValue("kind").jsonPrimitive.content)
    assertFalse(read.toString().contains("_zoneu_storage"))
    assertFalse(read.toString().contains("lost-prior"))
    val listed = payload(library.list(json("{}")))
    assertEquals("zoneu.character-card.list.v1", listed.getValue("kind").jsonPrimitive.content)
    assertFalse(listed.toString().contains("PRIVATE_PERSONALITY"))
  }
}

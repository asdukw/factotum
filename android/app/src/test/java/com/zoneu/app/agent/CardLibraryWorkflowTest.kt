package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real query/patch logic against the same transaction coordinator used by host tools and UI. */
class CardLibraryWorkflowTest {
  private val cards = mutableMapOf<String, CardSnapshot>()
  private val receipts = mutableMapOf<String, CardRequestReceipt>()
  private var confirmationFails = false
  private val storage = CardStorage(
    { cards[it] },
    { cards.values.toList() },
    { cards[it.id] = CardSnapshot(it, it.storageRevision!!, true) },
    { cards.remove(it) },
    receipts::get,
    { if (confirmationFails && it.committed) error("receipt unavailable") else receipts[it.request] = it },
  )

  private fun item(snapshot: CardSnapshot) = CardQueryItem(snapshot.card, snapshot.revision, snapshot.editable)

  private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun update(base: CardSnapshot, request: String, patch: JsonObject?, world: JsonArray?) =
    CardUpdateCommitter(storage).submit(request, cardHash("$patch:$world"), base.card.id, base.revision) { current ->
      val changes = CardPatch.apply(current, base.revision, patch, world)
      CardUpdateProposal(changes.card, CardChangeCounts(changes.changedFields, changes.added, changes.updated, changes.removed))
    }

  @Test
  fun queryReadUpdateReplayAndRemoveNeverDuplicateOrReplaceUnselectedData() {
    val imported = CharacterImporter.fromBytes(
      """{"name":"守站员","description":"保留经历","first_mes":"保留开场","system_prompt":"ARCHIVE_ONLY",
        "extensions":{"custom":{"v":[1,2]}},"character_book":{"entries":[
        {"id":7,"keys":["站台"],"content":"旧规则","extensions":{"custom":"ENTRY_ARCHIVE"}},
        {"id":7,"keys":["隐藏"],"content":"隐藏规则","enabled":false}]}}""".toByteArray(),
      "x",
    ).card
    val base = storage.create(imported.copy(id = "selected"))
    val other = storage.create(imported.copy(id = "same-name"))
    val listed = CharacterCardQuery.list(storage.snapshots().map(::item), fields("""{"query":"守站员"}"""))
    assertEquals(2, listed["items"]!!.jsonArray.size)
    assertEquals(2, listed["items"]!!.jsonArray.map { it.jsonObject["character_id"] }.distinct().size)
    val read = CharacterCardQuery.read(item(base), fields("""{"character_id":"selected","section":"world"}"""))
    // 第一条是启用的「旧规则」；第二条 disabled 也保留在列表里。
    val entry = read["items"]!!.jsonArray.first().jsonObject["entry_id"]!!.jsonPrimitive.content
    val patch = fields("""{"personality":"冷淡"}""")
    val ops = Json.parseToJsonElement(
      """[{"op":"update","entry_id":"$entry","fields":{"content":"修改旧规则"}},
        {"op":"add","fields":{"keys":["午夜"],"content":"午夜后不能走上三号站台"}}]""",
    ).jsonArray
    val changed = update(base, "tool-update", patch, ops)
    assertEquals(listOf("personality"), changed.changes.changedFields)
    assertEquals(1, changed.changes.added)
    assertEquals(1, changed.changes.updated)
    assertEquals("保留经历", changed.snapshot.card.description)
    assertEquals("保留开场", changed.snapshot.card.firstMes)
    assertEquals(other, storage.snapshot(other.card.id))
    val replay = update(base, "tool-update", patch, ops)
    assertTrue(replay.replay)
    assertEquals(changed.snapshot.revision, replay.appliedRevision)
    assertEquals(3, replay.snapshot.card.world.size)
    assertThrows(CardOperationException::class.java) {
      storage.update(base.card.id, base.revision) { it.copy(name = "过期UI草稿") }
    }
    val ui = storage.update(base.card.id, changed.snapshot.revision) { it.copy(name = "后续人工修改") }
    val afterUi = update(base, "tool-update", patch, ops)
    assertEquals(ui.revision, afterUi.snapshot.revision)
    assertEquals("后续人工修改", afterUi.snapshot.card.name)
    assertEquals(changed.appliedRevision, afterUi.appliedRevision)
    val addedIndex = ui.card.world.indexOfFirst { it.content.startsWith("午夜后") }
    val addedId = CardPatch.entryId(ui.card.id, ui.revision, addedIndex, ui.card.world[addedIndex])
    val deleted = update(
      ui,
      "remove-rule",
      null,
      Json.parseToJsonElement("""[{"op":"remove","entry_id":"$addedId"}]""").jsonArray,
    )
    val output = CardExporter.toV3(deleted.snapshot.card)
    assertFalse(output.contains("午夜后不能走上三号站台"))
    assertTrue(output.contains("ARCHIVE_ONLY"))
    assertTrue(output.contains("ENTRY_ARCHIVE"))
    assertTrue(output.contains("隐藏规则"))
    assertFalse(output.contains("_zoneu_storage"))
  }

  @Test
  fun pendingRealAddPatchRecoversOnceAndOldReadFailsAfterUiChange() {
    val base = storage.create(CharacterCard("c", "角色", description = "原设定"))
    val ops = Json.parseToJsonElement("""[{"op":"add","fields":{"content":"唯一新增","constant":true}}]""").jsonArray
    confirmationFails = true
    assertThrows(IllegalStateException::class.java) { update(base, "pending", null, ops) }
    val applied = storage.snapshot("c")!!
    assertEquals(1, applied.card.world.size)
    val token = applied.card.world.single().sourceToken
    assertTrue(token.isNotBlank())
    assertThrows(IllegalStateException::class.java) {
      storage.update("c", applied.revision) { it.copy(name = "不能覆盖提交证据") }
    }
    confirmationFails = false
    val recovered = update(base, "pending", null, ops)
    assertTrue(recovered.replay)
    assertEquals(token, recovered.snapshot.card.world.single().sourceToken)
    val ui = storage.update("c", recovered.snapshot.revision) { it.copy(description = "新设定") }
    assertEquals("新设定", ui.card.description)
    assertEquals(
      "CARD_REVISION_CONFLICT",
      assertThrows(CardOperationException::class.java) {
        update(base, "stale-other-request", fields("""{"personality":"旧补丁"}"""), null)
      }.code,
    )
    assertEquals(ui, storage.snapshot("c"))
  }
}

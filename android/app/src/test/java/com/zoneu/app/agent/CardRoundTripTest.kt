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
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入、内部持久化、编辑与导出是同一个合同，不只测两个相邻函数。 */
class CardRoundTripTest {
  private val source =
    """
    {"spec":"chara_card_v3","spec_version":"3.0","unknown_root":{"x":[1,true]},"data":{
      "name":"原名","description":"  人设  ","creator_notes":"第一行\n完整作者说明",
      "tags":["1","2","3","4","5","6","7"],"character_version":"作者版",
      "system_prompt":"PRIVATE_SYSTEM {{setvar::leaked::yes}}",
      "post_history_instructions":"PRIVATE_POST","mes_example":"PRIVATE_EXAMPLE",
      "extensions":{"unknown":{"a":[false,3]}},"group_only_greetings":["群开场"],"assets":[],
      "character_book":{"name":"原书","scan_depth":8,"extensions":{"book_unknown":7},"entries":[
        {"id":9,"keys":["甲"],"content":"  甲正文  ","insertion_order":17,"preventRecursion":true,
          "extensions":{"unknown":"A","caseSensitive":true}},
        {"id":9,"keys":["禁用"],"content":"禁用正文","enabled":false,"extensions":{"unknown":"OFF"}},
        {"id":9,"keys":["乙"],"content":"乙正文","extensions":{"unknown":"B"}},
        {"keys":["regex"],"content":"正则正文","use_regex":true},
        {"keys":[],"content":"","extensions":{"empty":true}}
      ]}
    }}
    """.trimIndent()

  private fun imported(text: String = source) = CharacterImporter.fromBytes(text.toByteArray(), "fallback").card

  private fun persisted(card: CharacterCard): CharacterCard {
    val json = Json { encodeDefaults = true }
    return json.decodeFromString(CharacterCard.serializer(), json.encodeToString(CharacterCard.serializer(), card))
  }

  private fun exported(card: CharacterCard) = Json.parseToJsonElement(CardExporter.toV3(card)).jsonObject

  private fun data(card: CharacterCard) = exported(card)["data"]!!.jsonObject

  private fun entries(card: CharacterCard) = data(card)["character_book"]!!.jsonObject["entries"]!!.jsonArray

  @Test
  fun unchangedCardRetainsRawValuesAndRequiredV3Fields() {
    val card = persisted(imported()).copy(name = "新名", tagline = "本地介绍")
    val original = Json.parseToJsonElement(source).jsonObject
    val out = exported(card)
    val data = out["data"]!!.jsonObject
    assertEquals(original["unknown_root"], out["unknown_root"])
    listOf(
      "description",
      "creator_notes",
      "tags",
      "character_version",
      "extensions",
      "assets",
      "group_only_greetings",
      "system_prompt",
      "post_history_instructions",
      "mes_example",
    ).forEach {
      assertEquals(it, original["data"]!!.jsonObject[it], data[it])
    }
    assertEquals("新名", data["name"]!!.jsonPrimitive.content)
    assertEquals(5, entries(card).size)
    entries(card).forEach {
      assertTrue(it.jsonObject["use_regex"] is JsonPrimitive)
      assertTrue(it.jsonObject["extensions"] is JsonObject)
    }
    assertEquals(card.importMetadata!!.report, persisted(card).importMetadata!!.report)
  }

  @Test
  fun deletionReorderAndDuplicateIdsNeverAttachTheWrongRaw() {
    val card = persisted(imported())
    assertEquals(2, card.world.size)
    assertEquals(2, card.world.map { it.sourceToken }.distinct().size)
    val reversed = card.copy(world = card.world.reversed())
    val active = entries(reversed).filter { it.jsonObject["enabled"]?.jsonPrimitive?.content != "false" }
    assertEquals("B", active[0].jsonObject["extensions"]!!.jsonObject["unknown"]!!.jsonPrimitive.content)
    assertEquals("A", active[1].jsonObject["extensions"]!!.jsonObject["unknown"]!!.jsonPrimitive.content)
    val deleted = card.copy(world = listOf(card.world[1].copy(content = "编辑乙")))
    val out = entries(deleted)
    assertFalse(out.any { it.jsonObject["content"]!!.jsonPrimitive.content.trim() == "甲正文" })
    assertTrue(out.any { it.jsonObject["content"]!!.jsonPrimitive.content == "编辑乙" })
    assertTrue(out.any { it.jsonObject["content"]!!.jsonPrimitive.content == "禁用正文" })
    assertEquals(3, entries(card.copy(world = emptyList())).size)
  }

  @Test
  fun archivedPromptsAndExtensionsCannotRenderOrWriteVariables() {
    val card = imported()
    val vars = TavernVars()
    val prompt = TavernPrompt.build(
      card,
      "",
      listOf(TavernMessage("user", "甲")),
      state = TavernPrompt.TurnState(vars = vars),
    ).joinToString { it.content }
    val fallback = CharacterStore.renderPrompt(card)
    listOf("PRIVATE_SYSTEM", "PRIVATE_POST", "PRIVATE_EXAMPLE", "正则正文", "禁用正文").forEach {
      assertFalse(prompt.contains(it))
      assertFalse(fallback.contains(it))
    }
    assertTrue(vars.snapshot().isEmpty())
    assertTrue(CardExporter.toV3(card).contains("PRIVATE_SYSTEM"))
  }

  @Test
  fun sameSourceReimportIsNoOpEvenAfterDeletionAndEdits() {
    val card = imported()
    val book = Json.parseToJsonElement(source).jsonObject["data"]!!.jsonObject["character_book"]!!
    val edited = card.copy(world = listOf(card.world.last().copy(content = "新正文")))
    assertEquals(edited, WorldBookIo.appendText(edited, book.toString()))
    assertEquals(edited, WorldBookIo.appendText(edited, "  $book \n"))
  }

  @Test
  fun crossSourceSemanticDedupPreservesHeadersAndHonorsCanonicalDeletion() {
    val card = imported()
    val second = """{"name":"次书","scan_depth":99,"entries":[
      {"keys":["乙"],"content":"乙正文","extensions":{"foreign":"different"}},
      {"keys":["新"],"content":"新正文"}]}"""
    val appended = persisted(WorldBookIo.appendText(card, second))
    assertEquals(3, appended.world.size)
    val out = data(appended)["character_book"]!!.jsonObject
    assertEquals("8", out["scan_depth"]!!.jsonPrimitive.content)
    assertTrue(out["extensions"].toString().contains("different"))
    val deleted = appended.copy(world = appended.world.filter { it.content != "乙正文" })
    assertFalse(data(deleted)["character_book"].toString().contains("different"))
    assertEquals(deleted, WorldBookIo.appendText(deleted, second))
  }

  @Test
  fun aliasesAreUpdatedWithoutOverwritingUnknownExtensionKeys() {
    val card = imported()
    val edited = card.copy(world = card.world.map { it.copy(caseSensitive = false, keys = listOf("改词")) })
    val first = entries(edited).first().jsonObject
    assertEquals("false", first["extensions"]!!.jsonObject["caseSensitive"]!!.jsonPrimitive.content)
    assertEquals("A", first["extensions"]!!.jsonObject["unknown"]!!.jsonPrimitive.content)
    assertEquals(JsonArray(listOf(JsonPrimitive("改词"))), first["keys"])
  }

  @Test
  fun oldInternalCardsRemainReadableAndExportable() {
    val card = Json.decodeFromString(CharacterCard.serializer(), """{"id":"old","name":"旧卡","description":"描述"}""")
    assertTrue(card.compatibilityNotes().any { it.code == "no_source" })
    assertEquals(JsonArray(emptyList()), data(card)["group_only_greetings"])
    assertFalse(CardExporter.toV3(card).contains("importMetadata"))
    assertFalse(CardExporter.toV3(card).contains("sourceToken"))
  }

  @Test
  fun malformedFieldTypesAreSafeAndReported() {
    val card = imported(
      """{"description":"合法","name":{},"tags":{},"character_book":{"entries":[
      1,{"content":"正文","enabled":{},"constant":[],"extensions":{"depth":{}}}]}}""",
    )
    assertEquals("fallback", card.name)
    assertEquals(1, card.world.size)
    assertTrue(card.compatibilityNotes().any { it.code == "normalize" })
    assertEquals(1, entries(card).size)
  }

  @Test
  fun positionAndKeywordAliasesRoundTripAfterEditing() {
    val card = imported(
      """{"description":"人设","first_message":"旧开场","world":{"items":[
      {"key":"原词","content":"正文","position":"before_char","extensions":{"unknown":3}}]}}""",
    )
    assertEquals(WorldEntry.POSITION_BEFORE, card.world.single().position)
    val edited = card.copy(
      firstMes = "新开场",
      world = card.world.map {
        it.copy(
          keys = listOf("新词"),
          position = WorldEntry.POSITION_DEPTH,
          depth = 6,
        )
      },
    )
    val output = data(edited)
    assertEquals(output["first_mes"], output["first_message"])
    assertEquals(output["world"], output["character_book"])
    val entry = entries(edited).single().jsonObject
    assertEquals("after_char", entry["position"]!!.jsonPrimitive.content)
    assertEquals(entry["keys"], entry["key"])
    val reimported = imported(CardExporter.toV3(edited))
    assertEquals(edited.world.single().copy(sourceToken = ""), reimported.world.single().copy(sourceToken = ""))
  }

  @Test
  fun queryReadSparseEditAndExportPreserveUnselectedFieldsAndArchivedWorldSlots() {
    val original = imported()
    val listed = CharacterCardQuery.list(
      listOf(CardQueryItem(original, "revision-1", true)),
      JsonObject(mapOf("query" to JsonPrimitive("原名"))),
    )
    assertEquals(original.id, listed["items"]!!.jsonArray.single().jsonObject["character_id"]!!.jsonPrimitive.content)
    val read = CharacterCardQuery.read(
      CardQueryItem(original, "revision-1", true),
      JsonObject(mapOf("character_id" to JsonPrimitive(original.id), "section" to JsonPrimitive("world"))),
    )
    val entryId = read["items"]!!.jsonArray.first().jsonObject["entry_id"]!!.jsonPrimitive.content
    val operations = Json.parseToJsonElement(
      """[{"op":"update","entry_id":"$entryId","fields":{"content":"修改甲正文"}},
        {"op":"add","fields":{"keys":["站台"],"content":"新增规则"}}]""",
    ).jsonArray
    val changed = CardPatch.apply(
      original,
      "revision-1",
      JsonObject(mapOf("personality" to JsonPrimitive("更冷淡"))),
      operations,
    )
    assertEquals(listOf("personality"), changed.changedFields)
    assertEquals(1, changed.updated)
    assertEquals(1, changed.added)
    val saved = persisted(changed.card.withStorage(CardStorageState("revision-2")))
    val output = data(saved)
    val originalData = Json.parseToJsonElement(source).jsonObject["data"]!!.jsonObject
    listOf("description", "creator_notes", "tags", "character_version", "extensions", "system_prompt").forEach {
      assertEquals(it, originalData[it], output[it])
    }
    assertTrue(entries(saved).any { it.jsonObject["content"]!!.jsonPrimitive.content == "修改甲正文" })
    assertTrue(entries(saved).any { it.jsonObject["content"]!!.jsonPrimitive.content == "禁用正文" })
    val addedIndex = saved.world.indexOfFirst { it.content == "新增规则" }
    val addedId = CardPatch.entryId(saved.id, "revision-2", addedIndex, saved.world[addedIndex])
    val removed = CardPatch.apply(
      saved,
      "revision-2",
      null,
      Json.parseToJsonElement("""[{"op":"remove","entry_id":"$addedId"}]""").jsonArray,
    )
    assertEquals(1, removed.removed)
    assertFalse(CardExporter.toV3(removed.card).contains("新增规则"))
    assertTrue(CardExporter.toV3(removed.card).contains("PRIVATE_SYSTEM"))
  }

  @Test
  fun storageRevisionAndPendingReceiptStayPrivateAcrossSerializationAndRendering() {
    val receipt = CardRequestReceipt(
      request = "PRIVATE_REQUEST_ID",
      parameters = "PRIVATE_PARAMETER_HASH",
      targetId = "local-test",
      contentHash = "PRIVATE_ORIGIN_HASH",
      operation = "update",
      appliedRevision = "PRIVATE_REVISION",
    )
    val card = persisted(imported().withStorage(CardStorageState("PRIVATE_REVISION", receipt)))
    assertEquals("PRIVATE_REVISION", card.storageRevision)
    assertEquals(receipt, card.storageState()!!.pending)
    val output = CardExporter.toV3(card)
    val prompt = CharacterStore.renderPrompt(card)
    val direct = TavernPrompt.build(card, "", listOf(TavernMessage("user", "你好"))).joinToString { it.content }
    listOf("_zoneu_storage", "PRIVATE_REQUEST_ID", "PRIVATE_PARAMETER_HASH", "PRIVATE_ORIGIN_HASH", "PRIVATE_REVISION").forEach {
      assertFalse(output.contains(it))
      assertFalse(prompt.contains(it))
      assertFalse(direct.contains(it))
    }
  }

  @Test
  fun sourceArchiveNeverOverwritesAnExistingNamespace() {
    val card = imported()
    val originalMetadata = card.importMetadata!!
    val originalBook = originalMetadata.sources.first().book
    val ext = JsonObject(mapOf("com.zoneu.card_sources" to JsonPrimitive("keep")))
    val metadata = originalMetadata.copy(
      sources = listOf(
        originalMetadata.sources.first().copy(book = JsonObject(originalBook + ("extensions" to ext))),
      ),
    )
    val appended = WorldBookIo.appendText(
      card.copy(importMetadata = metadata),
      """{"name":"次书","entries":[{"keys":["新"],"content":"新增"}]}""",
    )
    val out = data(appended)["character_book"]!!.jsonObject["extensions"]!!.jsonObject
    assertEquals(JsonPrimitive("keep"), out["com.zoneu.card_sources"])
    assertTrue("com.zoneu.card_sources.1" in out)
    assertEquals(appended.world.size, WorldBookIo.importText(WorldBookIo.exportText(appended)).size)
  }
}

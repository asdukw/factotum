package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPatchTest {
  private val original = CharacterCard(
    id = "local-test",
    name = "守站员",
    tagline = "本地简介",
    tags = listOf("雨夜", "站台"),
    description = "不变的经历",
    personality = "温和",
    scenario = "午夜",
    firstMes = "末班车到了。",
    alternateGreetings = listOf("早安", "晚安"),
    creator = "原作者",
    license = "原许可",
    imported = true,
    world = listOf(
      WorldEntry(
        keys = listOf("雨 · 雪", " 站台 "),
        content = "规则一",
        order = -13,
        constant = true,
        depth = 7,
        secondary = listOf("月亮"),
        secondaryLogic = SecondaryLogic.NOT_ALL,
        exclude = listOf("太阳"),
        probability = 70,
        sticky = 9,
        cooldown = 11,
        position = WorldEntry.POSITION_AFTER,
        role = WorldEntry.ROLE_ASSISTANT,
        useProbability = false,
        matchWholeWords = true,
        caseSensitive = true,
        preventRecursion = true,
        excludeRecursion = true,
        sourceToken = "original-a",
      ),
      WorldEntry(keys = listOf("夜"), content = "规则二", sourceToken = "original-b"),
    ),
  )

  private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun patch(text: String, card: CharacterCard = original) = CardPatch.apply(card, "revision-a", fields(text), null)

  private fun op(operation: String, id: String? = null, changes: JsonObject? = null) = buildJsonObject {
    put("op", operation)
    id?.let { put("entry_id", it) }
    changes?.let { put("fields", it) }
  }

  private fun id(index: Int, card: CharacterCard = original, revision: String = "revision-a") =
    CardPatch.entryId(card.id, revision, index, card.world[index])

  private fun world(vararg ops: JsonObject, card: CharacterCard = original) =
    CardPatch.apply(card, "revision-a", null, JsonArray(ops.toList()))

  private fun fails(code: String = "CARD_INVALID_FIELDS", block: () -> Unit) {
    if (code == "CARD_FIELD_TOO_LARGE") {
      val error = assertThrows(CardFieldTooLargeException::class.java) { block() }
      assertEquals(code, error.code)
      assertEquals(code, error.message)
      assertTrue(error.location.isNotBlank())
      assertTrue(error.length > 0)
    } else {
      val error = assertThrows(CardOperationException::class.java) { block() }
      assertEquals(code, error.code)
      assertEquals(code, error.message)
    }
  }

  @Test
  fun oneFieldChangesWithoutResettingOthersOrTrimming() {
    val result = patch("""{"personality":"  冷淡但守信  "}""")
    assertEquals(original.copy(personality = "  冷淡但守信  "), result.card)
    assertEquals(listOf("personality"), result.changedFields)
    assertEquals(listOf(0, 0, 0), listOf(result.added, result.updated, result.removed))
    assertSame(original.world.first(), result.card.world.first())
  }

  @Test
  fun explicitEmptyStringsAndArraysClearOnlySuppliedFields() {
    val result = patch("""{"tagline":"","tags":[],"description":"","first_mes":"","alternate_greetings":[]}""")
    assertEquals(
      original.copy(tagline = "", tags = emptyList(), description = "", firstMes = "", alternateGreetings = emptyList()),
      result.card,
    )
    assertEquals(listOf("tagline", "tags", "description", "first_mes", "alternate_greetings"), result.changedFields)
    assertEquals("午夜", result.card.scenario)
    assertEquals(listOf("  雨,雪  ", ""), patch("""{"tags":["  雨,雪  ",""]}""").card.tags)
  }

  @Test
  fun nullUnknownWrongTypesAndBlankNamesAreRejectedWithSafeCodes() {
    listOf(
      """{"description":null}""",
      """{"name":"  "}""",
      """{"name":""}""",
      """{"name":4}""",
      """{"creator_notes":"PRIVATE_BODY"}""",
      """{"raw":{}}""",
      """{"tags":"one"}""",
      """{"tags":[null]}""",
      """{"tags":[42]}""",
      """{"world":[]}""",
      """{"imported":true}""",
      """{"scenario":true}""",
      """{"alternate_greetings":[{}]}""",
    ).forEach { input -> fails { patch(input) } }
    fails("CARD_EMPTY_PATCH") { CardPatch.apply(original, "revision-a", null, null) }
    fails("CARD_EMPTY_PATCH") { CardPatch.apply(original, "revision-a", fields("{}"), JsonArray(emptyList())) }
    fails("CARD_REVISION_REQUIRED") { CardPatch.apply(original, "", fields("""{"name":"新"}"""), null) }
  }

  @Test
  fun newValuesRejectLimitsButDoNotNormalizeOrRevalidateOmittedLegacyValues() {
    fails("CARD_FIELD_TOO_LARGE") {
      patch(buildJsonObject { put("name", "a".repeat(100_001)) }.toString())
    }
    fails("CARD_FIELD_TOO_LARGE") {
      patch(JsonObject(mapOf("tags" to JsonArray(List(101) { JsonPrimitive("x") }))).toString())
    }
    fails("CARD_FIELD_TOO_LARGE") {
      world(op("add", changes = buildJsonObject { put("content", "x".repeat(WorldEntryDraft.MAX_CONTENT + 1)) }))
    }
    fails("CARD_FIELD_TOO_LARGE") {
      world(op("add", changes = JsonObject(mapOf("keys" to JsonArray(List(25) { JsonPrimitive("x") })))))
    }
    val legacy = original.copy(world = listOf(original.world.first().copy(depth = 99, content = "x".repeat(5_000))))
    assertEquals(legacy.copy(name = "新名"), patch("""{"name":"新名"}""", legacy).card)
    val large = "PRIVATE_BODY".repeat(400)
    val error = assertThrows(CardFieldTooLargeException::class.java) {
      world(op("update", id(0), buildJsonObject { put("content", large) }))
    }
    assertEquals("world.content", error.location)
    assertEquals(id(0), error.entryId)
    assertEquals(JsonPrimitive(large).toString().toByteArray(Charsets.UTF_8).size, error.length)
    assertEquals("CARD_FIELD_TOO_LARGE", error.message)
  }

  @Test
  fun noOpReturnsOriginalCardAndDoesNotMaterializeAnyLegacyToken() {
    val legacy = original.copy(world = original.world.map { it.copy(sourceToken = "") })
    val result = CardPatch.apply(
      legacy,
      "revision-a",
      fields("""{"personality":"温和"}"""),
      JsonArray(listOf(op("update", id(0, legacy), fields("""{"content":"规则一"}""")))),
    )
    assertSame(legacy, result.card)
    assertTrue(result.changedFields.isEmpty())
    assertEquals(listOf(0, 0, 0), listOf(result.added, result.updated, result.removed))
    assertTrue(legacy.world.all { it.sourceToken.isEmpty() })
  }

  @Test
  fun legacyReferencesUseCardRevisionAndOriginalSlotNotEquality() {
    val entry = WorldEntry(keys = listOf("同词"), content = "同正文")
    val card = original.copy(world = listOf(entry, entry))
    assertNotEquals(id(0, card), id(1, card))
    assertNotEquals(id(0, card), id(0, card, "revision-b"))
    assertNotEquals(id(0, card), id(0, card.copy(id = "other-card")))
    val result = world(op("update", id(1, card), fields("""{"content":"只改第二项"}""")), card = card)
    assertEquals("同正文", result.card.world.first().content)
    assertEquals("只改第二项", result.card.world.last().content)
    assertTrue(result.card.world.all { it.sourceToken.isNotBlank() })
    assertEquals(2, result.card.world.map { it.sourceToken }.distinct().size)
    assertTrue(card.world.all { it.sourceToken.isEmpty() })
    fails("CARD_ENTRY_NOT_FOUND") {
      world(op("remove", id(0, card, "revision-b")), card = card)
    }
    val cardOnly = patch("""{"name":"新名"}""", card)
    assertTrue(cardOnly.card.world.all { it.sourceToken.isNotBlank() })
    assertEquals(0, cardOnly.updated)
  }

  @Test
  fun addUpdateRemoveUseOriginalIdsKeepOrderAndPreserveUnchangedRuntimeFields() {
    val result = world(
      op("remove", id(1)),
      op("add", changes = fields("""{"keys":["雨,雪"," ",""],"content":"  新规则  "}""")),
      op("update", id(0), fields("""{"content":"  冷淡的规则一  "}""")),
    )
    assertEquals(listOf(1, 1, 1), listOf(result.added, result.updated, result.removed))
    assertEquals(original.world.first().copy(content = "  冷淡的规则一  "), result.card.world.first())
    assertEquals(listOf("雨,雪", " ", ""), result.card.world.last().keys)
    assertEquals("  新规则  ", result.card.world.last().content)
    val added = result.card.world.last()
    assertEquals(WorldEntry().copy(keys = added.keys, content = "  新规则  "), added.copy(sourceToken = ""))
    assertTrue(result.card.world.last().sourceToken.isNotEmpty())
    assertEquals(id(0), CardPatch.entryId(original.id, "new-revision", 10, result.card.world.first()))
  }

  @Test
  fun everyRuntimeFieldHasAnExactSparseMapping() {
    val changes = fields(
      """{"keys":[],"content":"","order":2147483647,"constant":false,"depth":12,
      "secondary":[],"secondary_logic":"NOT_ANY","exclude":[],"probability":0,"sticky":20,"cooldown":0,
      "position":0,"role":1,"use_probability":true,"match_whole_words":false,"case_sensitive":false,
      "prevent_recursion":false,"exclude_recursion":false}""",
    )
    val result = world(op("update", id(0), changes))
    val expected = WorldEntry(
      order = Int.MAX_VALUE,
      depth = 12,
      secondaryLogic = SecondaryLogic.NOT_ANY,
      probability = 0,
      sticky = 20,
      position = WorldEntry.POSITION_BEFORE,
      role = WorldEntry.ROLE_USER,
      sourceToken = "original-a",
    )
    assertEquals(expected, result.card.world.first())
    assertSame(original.world.last(), result.card.world.last())
    SecondaryLogic.entries.forEach { logic ->
      val edited = world(op("update", id(0), buildJsonObject { put("secondary_logic", logic.name) }))
      assertEquals(logic, edited.card.world.first().secondaryLogic)
    }
  }

  @Test
  fun sparseAddDefaultsAndExplicitEmptyRuntimeValuesAreAllowed() {
    val default = world(op("add", changes = fields("""{"content":""}"""))).card.world.last()
    assertEquals(WorldEntry(), default.copy(sourceToken = ""))
    val empty = world(op("update", id(0), fields("""{"keys":[],"secondary":[],"exclude":[],"content":""}""")))
    assertEquals("", empty.card.world.first().content)
    assertTrue(empty.card.world.first().keys.isEmpty())
  }

  @Test
  fun illegalWorldShapesDuplicateOperationsAndTokenConflictsAreRejected() {
    listOf(
      op("add"),
      op("add", id(0), fields("""{"content":"new"}""")),
      op("add", changes = fields("{}")),
      op("remove", id(0), fields("{}")),
      op("remove"),
      op("update", id(0)),
      op("update", id(0), fields("{}")),
      op("replace", id(0), fields("{}")),
    ).forEach { value -> fails { world(value) } }
    fails("CARD_ENTRY_CONFLICT") { world(op("update", id(0), fields("""{"content":"规则一"}""")), op("remove", id(0))) }
    fails("CARD_ENTRY_NOT_FOUND") { world(op("remove", "forged")) }
    fails("CARD_ENTRY_CONFLICT") {
      patch("""{"name":"new"}""", original.copy(world = listOf(original.world.first(), original.world.first())))
    }
    listOf(
      """{"depth":-1}""",
      """{"depth":13}""",
      """{"depth":"4"}""",
      """{"depth":1.5}""",
      """{"order":2147483648}""",
      """{"order":null}""",
      """{"probability":101}""",
      """{"probability":-1}""",
      """{"sticky":21}""",
      """{"cooldown":-1}""",
      """{"position":2}""",
      """{"role":3}""",
      """{"constant":"true"}""",
      """{"exclude_recursion":1}""",
      """{"secondary_logic":3}""",
      """{"secondary_logic":"not_any"}""",
      """{"sourceToken":"forged"}""",
      """{"extensions":{}}""",
      """{"keys":[null]}""",
      """{"content":null}""",
      """{"secondary":"雨"}""",
    ).forEach { invalid ->
      try {
        world(op("update", id(0), fields(invalid)))
        org.junit.Assert.fail("expected rejection: $invalid")
      } catch (_: CardOperationException) {
        // expected
      }
    }
  }

  @Test
  fun rawExportCreatorNotesUnknownExtensionsAndSourceHashSurvivePreciseEdits() {
    val source = """{"unknown_root":{"n":8},"data":{"name":"原名","description":"经历","creator_notes":"完整作者备注\n不准覆盖",
      "system_prompt":"PRIVATE_ARCHIVE","extensions":{"unknown":"RAW_ONLY"},"character_book":{"unknown_book":7,"entries":[
      {"id":9,"keys":["甲"],"content":"甲正文","extensions":{"unknown":"A"}},
      {"id":9,"keys":["乙"],"content":"乙正文","extensions":{"unknown":"B"}}]}}}"""
    val parsed = CharacterImporter.fromBytes(source.toByteArray(), "fallback").card
    val metadata = parsed.importMetadata!!.copy(contentHash = "unchanged-source-hash")
    val card = parsed.copy(importMetadata = metadata)
    val result = CardPatch.apply(
      card,
      "revision-a",
      fields("""{"tagline":"新的本地简介","personality":"冷淡"}"""),
      JsonArray(listOf(op("update", id(1, card), fields("""{"content":"乙的新正文"}""")), op("remove", id(0, card)))),
    )
    assertSame(metadata, result.card.importMetadata)
    assertEquals("unchanged-source-hash", result.card.importMetadata!!.contentHash)
    val json = Json { encodeDefaults = true }
    val persisted = json.decodeFromString(CharacterCard.serializer(), json.encodeToString(CharacterCard.serializer(), result.card))
    val output = Json.parseToJsonElement(CardExporter.toV3(persisted)).jsonObject
    val data = output["data"]!!.jsonObject
    assertEquals("完整作者备注\n不准覆盖", data["creator_notes"]!!.jsonPrimitive.content)
    assertEquals("PRIVATE_ARCHIVE", data["system_prompt"]!!.jsonPrimitive.content)
    assertEquals("冷淡", data["personality"]!!.jsonPrimitive.content)
    assertEquals(JsonPrimitive(8), output["unknown_root"]!!.jsonObject["n"])
    assertEquals(JsonPrimitive("RAW_ONLY"), data["extensions"]!!.jsonObject["unknown"])
    val entries = data["character_book"]!!.jsonObject["entries"]!!.jsonArray
    assertEquals(1, entries.size)
    assertEquals("乙的新正文", entries.single().jsonObject["content"]!!.jsonPrimitive.content)
    assertEquals(JsonPrimitive("B"), entries.single().jsonObject["extensions"]!!.jsonObject["unknown"])
    assertFalse(output.toString().contains("unchanged-source-hash"))
  }
}

package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CharacterCardQueryTest {
  private fun item(
    id: String = "local-a",
    name: String = "守站员",
    revision: String = "revision-a",
    editable: Boolean = true,
  ) = CardQueryItem(
    CharacterCard(
      id = id,
      name = name,
      tagline = "城南车站的午夜守站员",
      tags = listOf("中文", "RAIN"),
      description = "原本是普通的铁路员工。",
      personality = "冷淡但守信。",
      scenario = "午夜的三号站台。",
      firstMes = "末班车停了。",
      alternateGreetings = listOf("早安。", "夜深了。", "雪又下起来了。"),
      world = listOf(
        WorldEntry(keys = listOf("站台"), content = "午夜不能走上三号站台。", sourceToken = "PRIVATE_SOURCE_A"),
        WorldEntry(keys = listOf("雪"), content = "雪会掩住旧轨道。", sourceToken = "PRIVATE_SOURCE_B"),
        WorldEntry(keys = listOf("月"), content = "月台只有一盏灯。"),
      ),
      imported = !editable,
    ),
    revision,
    editable,
  )

  private fun read(item: CardQueryItem, section: String, extra: JsonObject = JsonObject(emptyMap())) =
    CharacterCardQuery.read(item, JsonObject(extra + mapOf("section" to JsonPrimitive(section))))

  private fun withCursor(section: String, cursor: String, limit: Int = 1) = buildJsonObject {
    put("section", section)
    put("limit", limit)
    put("cursor", cursor)
  }

  private fun failure(code: String = "CARD_INVALID_FIELDS", block: () -> Unit) {
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
  fun sameNamesSortByPreciseIdAndPaginateWithoutDuplicates() {
    val items = listOf(item("b"), item("a"), item("c"))
    var result = CharacterCardQuery.list(items, args("""{"limit":1}"""))
    val seen = mutableListOf<String>()
    while (true) {
      assertEquals("zoneu.character-card.list.v1", result["kind"]!!.jsonPrimitive.content)
      assertEquals("3", result["total"]!!.jsonPrimitive.content)
      seen += result["items"]!!.jsonArray.single().jsonObject["character_id"]!!.jsonPrimitive.content
      if (result["has_more"] == JsonPrimitive(false)) break
      assertFalse(result["complete"]!!.jsonPrimitive.content.toBoolean())
      result = CharacterCardQuery.list(
        items.reversed(),
        buildJsonObject {
          put("limit", 1)
          put("cursor", cursor(result))
        },
      )
    }
    assertEquals(listOf("a", "b", "c"), seen)
    assertEquals(JsonNull, result["next_cursor"])
    assertEquals(JsonPrimitive(false), result["complete"])
  }

  @Test
  fun defaultTwentyAndLimitsOneThroughFiftyAreAccepted() {
    val items = List(60) { item("card-$it") }
    assertEquals(20, CharacterCardQuery.list(items, args("{}"))["items"]!!.jsonArray.size)
    assertEquals(1, CharacterCardQuery.list(items, args("""{"limit":1}"""))["items"]!!.jsonArray.size)
    assertEquals(50, CharacterCardQuery.list(items, args("""{"limit":50}"""))["items"]!!.jsonArray.size)
    listOf("0", "51", "-1", "1.5", "\"20\"", "null", "true").forEach { value ->
      failure { CharacterCardQuery.list(items, args("""{"limit":$value}""")) }
    }
    val empty = CharacterCardQuery.list(emptyList(), args("{}"))
    assertTrue(empty["items"]!!.jsonArray.isEmpty())
    assertEquals(JsonPrimitive(true), empty["complete"])
    assertEquals(JsonPrimitive(0), empty["total"])
  }

  @Test
  fun chineseQueryMatchesNameTagsAndLocalTaglineButNeverHiddenFields() {
    val byName = item("name", "守夜人")
    val byTag = item("tag").let { it.copy(card = it.card.copy(tags = listOf("守夜人设"))) }
    val byTagline = item("tagline").let { it.copy(card = it.card.copy(tagline = "守夜人正在等车")) }
    val hidden = item("hidden").let { it.copy(card = it.card.copy(description = "守夜人")) }
    val result = CharacterCardQuery.list(listOf(hidden, byTagline, byTag, byName), args("""{"query":"守夜人"}"""))
    val ids = result["items"]!!.jsonArray.map { it.jsonObject["character_id"]!!.jsonPrimitive.content }.toSet()
    assertEquals(setOf("name", "tag", "tagline"), ids)
    assertEquals(1, CharacterCardQuery.list(listOf(item()), args("""{"query":"rain"}"""))["items"]!!.jsonArray.size)
    assertTrue(CharacterCardQuery.list(listOf(item()), args("""{"query":"不存在"}"""))["items"]!!.jsonArray.isEmpty())
  }

  @Test
  fun listCursorsBindQueryLimitAndEntireOrderedRevisionSnapshot() {
    val items = listOf(item("a"), item("b"), item("z", "另一张卡"))
    val result = CharacterCardQuery.list(items, args("""{"query":"守站员","limit":1}"""))
    val continuation = buildJsonObject {
      put("query", "守站员")
      put("limit", 1)
      put("cursor", cursor(result))
    }
    failure("CARD_CURSOR_STALE") {
      CharacterCardQuery.list(items.map { if (it.card.id == "z") it.copy(revision = "changed-outside-query") else it }, continuation)
    }
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.list(items.dropLast(1), continuation) }
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.list(items + item("new"), continuation) }
    failure("CARD_CURSOR_STALE") {
      CharacterCardQuery.list(items, JsonObject(continuation + ("query" to JsonPrimitive("车站"))))
    }
    failure("CARD_CURSOR_STALE") {
      CharacterCardQuery.list(items, JsonObject(continuation + ("limit" to JsonPrimitive(2))))
    }
    failure("CARD_ID_CONFLICT") { CharacterCardQuery.list(listOf(item("same"), item("same")), args("{}")) }
    val partial = CharacterCardQuery.list(items, args("""{"query":"守站员","limit":1}"""), 2)
    assertEquals(JsonPrimitive(true), partial["partial"])
    assertEquals(JsonPrimitive(2), partial["unreadable_count"])
    assertTrue(partial.toString().toByteArray(Charsets.UTF_8).size <= CharacterCardQuery.MAX_OUTPUT_BYTES)
    val partialContinuation = JsonObject(continuation + ("cursor" to JsonPrimitive(cursor(partial))))
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.list(items, partialContinuation, 1) }
  }

  @Test
  fun summaryTruncationIsExplicitAndDoesNotSplitUnicodeCodePoints() {
    val long = item().let { it.copy(card = it.card.copy(tagline = "月".repeat(170), tags = List(10) { "夜".repeat(80) })) }
    val result = CharacterCardQuery.list(listOf(long), args("{}"))["items"]!!.jsonArray.single().jsonObject
    assertEquals("月".repeat(160) + "…", result["tagline"]!!.jsonPrimitive.content)
    assertEquals(JsonPrimitive(true), result["tagline_truncated"])
    assertEquals(JsonPrimitive(true), result["tags_truncated"])
    assertEquals(JsonPrimitive(10), result["tags_total"])
    assertEquals(8, result["tags"]!!.jsonArray.size)
    val supplementary = item().let { it.copy(card = it.card.copy(tagline = "𠀀".repeat(170))) }
    val projected = CharacterCardQuery.list(listOf(supplementary), args("{}"))["items"]!!.jsonArray.single().jsonObject
    val summary = projected["tagline"]!!.jsonPrimitive.content
    assertEquals("𠀀".repeat(160) + "…", summary)
  }

  @Test
  fun sourceAndEditableReflectSuppliedLocationNotUntrustedCardImportedFlag() {
    listOf(item(editable = true), item(editable = false)).forEach { source ->
      val expected = if (source.editable) "imported" else "builtin"
      val summary = CharacterCardQuery.list(listOf(source), args("{}"))["items"]!!.jsonArray.single().jsonObject
      assertEquals(JsonPrimitive(expected), summary["source"])
      assertEquals(JsonPrimitive(source.editable), summary["editable"])
      val result = read(source, "basic")
      assertEquals(JsonPrimitive(expected), result["source"])
      assertEquals(JsonPrimitive(source.editable), result["editable"])
    }
  }

  @Test
  fun explicitTextSectionsAndBasicOnlyReturnTheSelectedContent() {
    val source = item()
    val expected = mapOf(
      "name" to source.card.name,
      "tagline" to source.card.tagline,
      "description" to source.card.description,
      "personality" to source.card.personality,
      "scenario" to source.card.scenario,
      "first_mes" to source.card.firstMes,
    )
    expected.forEach { (section, content) ->
      val result = read(source, section)
      assertEquals("zoneu.character-card.read.v1", result["kind"]!!.jsonPrimitive.content)
      assertEquals(JsonPrimitive(source.revision), result["revision"])
      assertEquals(JsonPrimitive(content), result["content"])
      assertEquals(JsonPrimitive(true), result["complete"])
      assertFalse(result.keys.any { it in setOf("description", "world", "personality", "tags") })
    }
    val basic = read(source, "basic")["content"]!!.jsonObject
    assertEquals(setOf("name", "tagline"), basic.keys)
    assertEquals(JsonPrimitive(source.card.name), basic["name"])
    assertEquals(JsonPrimitive(source.card.tagline), basic["tagline"])
  }

  @Test
  fun arrayPagesUseWholeItemsAndStateWhetherTheyAreTheWholeArray() {
    val source = item()
    val first = read(source, "alternate_greetings", args("""{"limit":1}"""))
    assertEquals(JsonArray(listOf(JsonPrimitive("早安。"))), first["items"])
    assertEquals(JsonPrimitive(3), first["total"])
    assertEquals(JsonPrimitive(false), first["complete"])
    val second = CharacterCardQuery.read(source, withCursor("alternate_greetings", cursor(first)))
    assertEquals(JsonArray(listOf(JsonPrimitive("夜深了。"))), second["items"])
    assertEquals(JsonPrimitive(1), second["offset"])
    val final = CharacterCardQuery.read(source, withCursor("alternate_greetings", cursor(second)))
    assertEquals(JsonArray(listOf(JsonPrimitive("雪又下起来了。"))), final["items"])
    assertEquals(JsonPrimitive(false), final["has_more"])
    assertEquals(JsonPrimitive(false), final["complete"])
    val tags = read(source, "tags")
    assertEquals(JsonArray(source.card.tags.map(::JsonPrimitive)), tags["items"])
    assertEquals(JsonPrimitive(true), tags["complete"])
    val empty = source.copy(card = source.card.copy(tags = emptyList()))
    assertEquals(JsonPrimitive(true), read(empty, "tags")["complete"])
  }

  @Test
  fun worldExactEntryReadsExposeEveryRuntimeFieldAndCanFeedSparsePatches() {
    val entry = WorldEntry(
      keys = listOf("主"),
      content = "正文",
      order = -6,
      constant = true,
      depth = 8,
      secondary = listOf("副"),
      secondaryLogic = SecondaryLogic.NOT_ANY,
      exclude = listOf("排除"),
      probability = 50,
      sticky = 11,
      cooldown = 12,
      position = WorldEntry.POSITION_AFTER,
      role = WorldEntry.ROLE_USER,
      useProbability = false,
      matchWholeWords = true,
      caseSensitive = true,
      preventRecursion = true,
      excludeRecursion = true,
      sourceToken = "PRIVATE_SOURCE",
    )
    val source = item().let { it.copy(card = it.card.copy(world = listOf(entry, entry.copy(sourceToken = "other")))) }
    val result = read(source, "world", args("""{"limit":1}"""))
    val projected = result["items"]!!.jsonArray.single().jsonObject
    val id = projected["entry_id"]!!.jsonPrimitive.content
    val exact = read(source, "world", buildJsonObject { put("entry_id", id) })
    assertEquals(result["items"], exact["items"])
    assertEquals(JsonPrimitive(true), exact["complete"])
    val op = buildJsonObject {
      put("op", "update")
      put("entry_id", id)
      put("fields", JsonObject(projected - "entry_id"))
    }
    val unchanged = CardPatch.apply(source.card, source.revision, null, JsonArray(listOf(op)))
    assertEquals(source.card, unchanged.card)
    assertEquals(0, unchanged.updated)
    assertFalse(projected.toString().contains("PRIVATE_SOURCE"))
    assertEquals(19, projected.size)
    failure("CARD_ENTRY_NOT_FOUND") { read(source, "world", args("""{"entry_id":"forged"}""")) }
    failure {
      read(
        source,
        "world",
        buildJsonObject {
          put("entry_id", id)
          put("limit", 1)
        },
      )
    }
  }

  @Test
  fun legacyIdentitiesStayReadOnlyAndRevisionBoundWithoutEqualityGuessing() {
    val source = item().let { item ->
      val entry = WorldEntry(content = "同正文")
      item.copy(card = item.card.copy(world = listOf(entry, entry)))
    }
    val first = read(source, "world")["items"]!!.jsonArray
    assertNotEquals(first[0].jsonObject["entry_id"], first[1].jsonObject["entry_id"])
    assertEquals(first, read(source, "world")["items"])
    assertTrue(source.card.world.all { it.sourceToken.isEmpty() })
    val changed = read(source.copy(revision = "revision-b"), "world")["items"]!!.jsonArray
    assertNotEquals(first[0].jsonObject["entry_id"], changed[0].jsonObject["entry_id"])
    val ambiguous = source.copy(card = source.card.copy(world = source.card.world.map { it.copy(sourceToken = "duplicate") }))
    failure("CARD_ENTRY_CONFLICT") { read(ambiguous, "world") }
  }

  @Test
  fun readCursorsCannotMoveAcrossCardRevisionSectionOrLimit() {
    val source = item()
    val first = read(source, "world", args("""{"limit":1}"""))
    val continuation = withCursor("world", cursor(first))
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.read(source.copy(revision = "revision-b"), continuation) }
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.read(item("other"), continuation) }
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.read(source, withCursor("alternate_greetings", cursor(first))) }
    failure("CARD_CURSOR_STALE") { CharacterCardQuery.read(source, withCursor("world", cursor(first), 2)) }
    assertEquals(1, CharacterCardQuery.read(source, continuation)["items"]!!.jsonArray.size)
  }

  @Test
  fun whitelistNeverLeaksRawPromptsPrivateOriginsOrMetadata() {
    val source = item()
    val metadata = CardImportMetadata(
      raw = args("""{"system_prompt":"PRIVATE_SYSTEM","extensions":{"unknown":"PRIVATE_RAW"}}"""),
      baseline = args("""{"hidden":"PRIVATE_BASELINE"}"""),
      contentHash = "PRIVATE_HASH",
      originHost = "private.example",
      originUrl = "https://private.example/card?token=PRIVATE_NETWORK_TOKEN",
    )
    val secured = source.copy(
      card = source.card.copy(
        importMetadata = metadata,
        creator = "PRIVATE_CREATOR",
        license = "PRIVATE_LICENSE",
      ),
    )
    val sections = listOf(
      "basic",
      "name",
      "tagline",
      "description",
      "personality",
      "scenario",
      "first_mes",
      "tags",
      "alternate_greetings",
      "world",
    )
    val all = listOf(CharacterCardQuery.list(listOf(secured), args("{}"))) + sections.map { read(secured, it) }
    all.forEach { result ->
      assertEquals(JsonPrimitive(true), result["untrusted"])
      assertTrue(result["notice"]!!.jsonPrimitive.content.contains("不可信"))
      assertFalse(result.toString().contains("PRIVATE_"))
      assertFalse(result.toString().contains("private.example"))
      assertFalse(result.toString().contains("importMetadata"))
    }
  }

  @Test
  fun utf8AndJsonEscapesCountTowardTheExactOutputBudget() {
    val source = item()
    val base = read(source.copy(card = source.card.copy(description = "")), "description")
    val overhead = base.toString().toByteArray(Charsets.UTF_8).size
    val capacity = CharacterCardQuery.MAX_OUTPUT_BYTES - overhead
    val fitting = source.copy(card = source.card.copy(description = "x".repeat(capacity)))
    val full = read(fitting, "description")
    assertEquals(CharacterCardQuery.MAX_OUTPUT_BYTES, full.toString().toByteArray(Charsets.UTF_8).size)
    assertEquals(fitting.card.description, full["content"]!!.jsonPrimitive.content)
    failure("CARD_FIELD_TOO_LARGE") {
      read(source.copy(card = source.card.copy(description = "x".repeat(capacity + 1))), "description")
    }
    failure("CARD_FIELD_TOO_LARGE") {
      read(source.copy(card = source.card.copy(description = "月".repeat(11_000))), "description")
    }
    failure("CARD_FIELD_TOO_LARGE") {
      read(source.copy(card = source.card.copy(description = "\n".repeat(17_000))), "description")
    }
    failure("CARD_FIELD_TOO_LARGE") {
      CharacterCardQuery.list(listOf(item(name = "x".repeat(33_000))), args("{}"))
    }
    failure("CARD_FIELD_TOO_LARGE") {
      read(source.copy(card = source.card.copy(world = listOf(WorldEntry(content = "长".repeat(11_000))))), "world")
    }
    failure("CARD_FIELD_TOO_LARGE") {
      read(source.copy(card = source.card.copy(alternateGreetings = listOf("long".repeat(10_000)))), "alternate_greetings")
    }
    val error = assertThrows(CardFieldTooLargeException::class.java) {
      read(source.copy(card = source.card.copy(description = "PRIVATE_LONG_BODY".repeat(3_000))), "description")
    }
    assertEquals("description", error.location)
    assertEquals(JsonPrimitive("PRIVATE_LONG_BODY".repeat(3_000)).toString().toByteArray(Charsets.UTF_8).size, error.length)
    assertEquals(null, error.entryId)
    assertEquals("CARD_FIELD_TOO_LARGE", error.message)
    val oversizedWorld = source.copy(card = source.card.copy(world = listOf(WorldEntry(content = "月".repeat(11_000)))))
    val entryError = assertThrows(CardFieldTooLargeException::class.java) { read(oversizedWorld, "world") }
    assertEquals("world.content", entryError.location)
    assertEquals(CardPatch.entryId(source.card.id, source.revision, 0, oversizedWorld.card.world.single()), entryError.entryId)
  }

  @Test
  fun byteBoundedArrayPagesNeverPretendPrefixesAreComplete() {
    val source = item().let { it.copy(card = it.card.copy(alternateGreetings = List(5) { "条$it" + "月".repeat(5_000) })) }
    val first = read(source, "alternate_greetings")
    assertEquals(2, first["items"]!!.jsonArray.size)
    assertEquals(JsonPrimitive(false), first["complete"])
    assertTrue(first.toString().toByteArray(Charsets.UTF_8).size <= CharacterCardQuery.MAX_OUTPUT_BYTES)
    assertEquals(source.card.alternateGreetings.take(2), first["items"]!!.jsonArray.map { it.jsonPrimitive.content })
    val next = CharacterCardQuery.read(source, withCursor("alternate_greetings", cursor(first), 20))
    assertEquals(JsonPrimitive(2), next["offset"])
    assertEquals(source.card.alternateGreetings.subList(2, 4), next["items"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun forgedAndEditedCursorPayloadsCannotSkipItems() {
    val source = item()
    val first = read(source, "world", args("""{"limit":1}"""))
    val valid = cursor(first)
    val components = valid.split('.')
    val decoded = Base64.getUrlDecoder().decode(components[0]).toString(Charsets.US_ASCII)
    val edited = decoded.substringBeforeLast(':') + ":2"
    val forged = Base64.getUrlEncoder().withoutPadding().encodeToString(edited.toByteArray()) + "." + components[1]
    listOf("garbage", "1", "e30.aaaa", forged, components[0] + ".AAAA").forEach { invalid ->
      failure("CARD_INVALID_CURSOR") { CharacterCardQuery.read(source, withCursor("world", invalid)) }
    }
    failure { CharacterCardQuery.read(source, withCursor("world", "a".repeat(513))) }
    failure { CharacterCardQuery.list(listOf(source), args("""{"cursor":null}""")) }
  }

  @Test
  fun invalidSectionsTypesAndCrossSectionArgumentsFailWithoutEchoingInput() {
    val source = item()
    listOf(
      "{}",
      """{"section":null}""",
      """{"section":"creator_notes"}""",
      """{"section":"raw"}""",
      """{"section":"../PRIVATE_PATH"}""",
      """{"section":"basic","limit":1}""",
      """{"section":"name","cursor":"fake"}""",
      """{"section":"tags","entry_id":"fake"}""",
      """{"section":"world","fields":{}}""",
      """{"section":"description","extensions":{}}""",
      """{"section":4}""",
    ).forEach { invalid -> failure { CharacterCardQuery.read(source, args(invalid)) } }
    failure("CARD_NOT_FOUND") { CharacterCardQuery.read(source, args("""{"character_id":"wrong","section":"name"}""")) }
    failure { CharacterCardQuery.list(listOf(source), args("""{"query":false}""")) }
    failure { CharacterCardQuery.list(listOf(source), args("""{"unknown":"PRIVATE_VALUE"}""")) }
  }
}

private fun cursor(value: JsonObject): String = value["next_cursor"]!!.jsonPrimitive.content

private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

package com.zoneu.app.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookQueryTest {
  private val book = WorldBook(
    id = "book",
    revision = "r1",
    name = "Station",
    tags = listOf("rain"),
    entries = listOf(
      WorldBookEntry(
        id = "entry-a",
        enabled = false,
        rule = WorldEntry(content = "A", sourceToken = "PRIVATE_TOKEN"),
        raw = Json.parseToJsonElement("""{"private":"PRIVATE_RAW"}"""),
      ),
      WorldBookEntry(id = "entry-b", rule = WorldEntry(content = "B")),
    ),
  )

  @Test
  fun exactWhitelistKeepsDisabledRulesAndDropsPrivateArchiveData() {
    val result = WorldBookQuery.read(book, args("""{"book_id":"book","section":"entries","entry_id":"entry-a"}"""))
    assertEquals("zoneu.world-book.read.v1", result["kind"]!!.jsonPrimitive.content)
    assertEquals(JsonPrimitive(true), result["untrusted"])
    val entry = result["items"]!!.jsonArray.single().jsonObject
    assertEquals(JsonPrimitive(false), entry["enabled"])
    assertTrue("prevent_recursion" in entry)
    assertFalse(result.toString().contains("PRIVATE"))
    assertFalse("raw" in entry)
  }

  @Test
  fun sameNamesReturnAllExactIdsWithStableWholeItemPagination() {
    val books = listOf(book.copy(id = "c"), book.copy(id = "a"), book.copy(id = "b"))
    var result = WorldBookQuery.list(books, args("""{"limit":1}"""))
    val ids = mutableListOf<String>()
    while (true) {
      ids += result["items"]!!.jsonArray.single().jsonObject["book_id"]!!.jsonPrimitive.content
      if (result["has_more"] == JsonPrimitive(false)) break
      result = WorldBookQuery.list(books.reversed(), args("""{"limit":1,"cursor":"${cursor(result)}"}"""))
    }
    assertEquals(listOf("a", "b", "c"), ids)
  }

  @Test
  fun cursorsBindLibraryQueryLimitSectionRevisionAndUsageReferences() {
    val books = listOf(book, book.copy(id = "other"))
    val first = WorldBookQuery.list(books, args("""{"limit":1}"""))
    val token = cursor(first)
    fails("WORLD_CURSOR_STALE") { WorldBookQuery.list(books.map { it.copy(revision = "r2") }, args("""{"limit":1,"cursor":"$token"}""")) }
    fails("WORLD_CURSOR_STALE") { WorldBookQuery.list(books, args("""{"limit":2,"cursor":"$token"}""")) }
    fails("WORLD_CURSOR_STALE") { WorldBookQuery.list(books, args("""{"query":"station","limit":1,"cursor":"$token"}""")) }
    val read = WorldBookQuery.read(book, args("""{"book_id":"book","section":"entries","limit":1}"""))
    fails("WORLD_CURSOR_STALE") {
      WorldBookQuery.read(
        book.copy(revision = "r2"),
        args("""{"book_id":"book","section":"entries","limit":1,"cursor":"${cursor(read)}"}"""),
      )
    }
    val usage = WorldUsage(listOf("role"), listOf("session"))
    val used = WorldBookQuery.read(book, args("""{"book_id":"book","section":"usage","limit":1}"""), usage)
    fails("WORLD_CURSOR_STALE") {
      WorldBookQuery.read(
        book,
        args("""{"book_id":"book","section":"usage","limit":1,"cursor":"${cursor(used)}"}"""),
        usage.copy(sessionIds = listOf("new")),
      )
    }
    val tampered = token.substringBefore('.') + "." + "a".repeat(43)
    fails("WORLD_INVALID_CURSOR") { WorldBookQuery.list(books, args("""{"limit":1,"cursor":"$tampered"}""")) }
  }

  @Test
  fun oversizeIsAnErrorNeverATextPrefixAndUtf8BudgetIncludesMetadata() {
    fails("WORLD_FIELD_TOO_LARGE") {
      WorldBookQuery.read(book.copy(description = "界".repeat(12000)), args("""{"book_id":"book","section":"description"}"""))
    }
    val entries = List(8) { WorldBookEntry(id = "entry-$it", rule = WorldEntry(content = "界".repeat(2000))) }
    val result = WorldBookQuery.read(book.copy(entries = entries), args("""{"book_id":"book","section":"entries"}"""))
    assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= 32768)
    assertTrue(result["items"]!!.jsonArray.size in 1..7)
    result["items"]!!.jsonArray.forEach { assertEquals(2000, it.jsonObject["content"]!!.jsonPrimitive.content.length) }
    fails("WORLD_FIELD_TOO_LARGE") {
      WorldBookQuery.read(
        book.copy(entries = listOf(WorldBookEntry(id = "large", rule = WorldEntry(content = "x".repeat(33000))))),
        args("""{"book_id":"book","section":"entries"}"""),
      )
    }
  }

  @Test
  fun invalidReadSectionsHiddenFieldsAndRevisionMismatchFailClosed() {
    for (value in listOf(
      """{"book_id":"book","section":"raw"}""",
      """{"book_id":"book","section":"name","entry_id":"entry-a"}""",
      """{"book_id":"book","section":"name","limit":1}""",
      """{"book_id":"book","section":"entries","limit":0}""",
    )) {
      fails("WORLD_INVALID_ARGUMENT") { WorldBookQuery.read(book, args(value)) }
    }
    fails("WORLD_REVISION_CONFLICT") { WorldBookQuery.read(book, args("""{"book_id":"book","section":"name","revision":"r2"}""")) }
    fails("WORLD_ENTRY_NOT_FOUND") { WorldBookQuery.read(book, args("""{"book_id":"book","section":"entries","entry_id":"missing"}""")) }
  }

  @Test
  fun commandFingerprintIsCanonicalAndSeparatesOperations() {
    val a = WorldBookCommand.parse(
      "update_world_book",
      args("""{"book_id":"book","expected_revision":"r1","fields":{"enabled":false,"description":"x"}}"""),
    )
    val b = WorldBookCommand.parse(
      "update_world_book",
      args("""{"fields":{"description":"x","enabled":false},"expected_revision":"r1","book_id":"book"}"""),
    )
    assertEquals(a.fingerprint, b.fingerprint)
    val copy = WorldBookCommand.parse("copy_world_book", args("""{"book_id":"book","expected_revision":"r1"}"""))
    val delete = WorldBookCommand.parse("delete_world_book", copy.args)
    assertNotEquals(copy.parameters, delete.parameters)
    assertNotEquals(copy.fingerprint, delete.fingerprint)
    assertEquals(worldOperationId("request", "entry-0"), worldOperationId("request", "entry-0"))
  }

  @Test
  fun previewProofIsBoundedAndCannotChangeParametersOrResurrectCancellation() {
    val previews = WorldBookPreviews()
    previews.register("request", "parameters")
    previews.bind("request", "hash", "unchanged")
    assertEquals("unchanged", previews.check("request", "parameters", "hash", "hash"))
    fails("WORLD_PREVIEW_CONFLICT") { previews.check("request", "parameters", "hash", "other") }
    fails("WORLD_REQUEST_CONFLICT") { previews.register("request", "other") }
    assertEquals("parameters", previews.cancel("request"))
    previews.register("request", "parameters")
    assertTrue(previews.cancelled("request"))
    repeat(260) { previews.register("r-$it", "p-$it") }
    fails("WORLD_PREVIEW_REQUIRED") { previews.check("request", "parameters", "hash", "hash") }
  }

  @Test
  fun sessionChangesAreDeterministicExplicitlyPinnedAndPreserveUnchangedEpochs() = runBlocking {
    val initial = WorldManifest("session", "bindings", listOf(WorldBookBinding("book", "r1", listOf("character:role"), epoch = "old")))
    var reads = 0

    suspend fun apply(text: String) = applyWorldBindingChanges(
      initial,
      Json.parseToJsonElement(text).jsonArray,
      "request",
    ) {
      id,
      revision,
      ->
      reads++
      book.copy(id = id, revision = revision)
    }
    val disabled = apply("""[{"op":"disable","book_id":"book"}]""")
    assertEquals(WorldOverride.DISABLE, disabled.books.single().override)
    assertNotEquals("old", disabled.books.single().epoch)
    assertEquals(0, reads)
    val unchanged = applyWorldBindingChanges(
      disabled,
      Json.parseToJsonElement("""[{"op":"disable","book_id":"book"}]""").jsonArray,
      "other",
    ) {
      _,
      _,
      ->
      error("unexpected read")
    }
    assertEquals(disabled, unchanged)
    val upgraded = apply("""[{"op":"upgrade","book_id":"book","revision":"r2"}]""")
    assertEquals("r2", upgraded.books.single().revision)
    assertEquals(listOf("character:role"), upgraded.books.single().sources)
    assertEquals(1, reads)
    assertThrows(WorldBookException::class.java) { runBlocking { apply("""[{"op":"upgrade","book_id":"book"}]""") } }
    Unit
  }

  private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun cursor(value: JsonObject): String = value["next_cursor"]!!.jsonPrimitive.content

  private fun fails(code: String, block: () -> Unit) {
    assertEquals(code, assertThrows(WorldBookException::class.java, block).code)
  }
}

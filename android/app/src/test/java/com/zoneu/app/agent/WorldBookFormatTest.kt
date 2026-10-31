package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class WorldBookFormatTest {
  private fun parse(text: String) = Json.parseToJsonElement(text)

  private fun fields(text: String) = parse(text).jsonObject

  private fun output(book: WorldBook) = parse(WorldBookFormat.exportText(book))

  private fun op(name: String, id: String? = null, changes: String? = null, index: Int? = null) = buildJsonObject {
    put("op", name)
    id?.let { put("entry_id", it) }
    changes?.let { put("fields", fields(it)) }
    index?.let { put("index", it) }
  }

  @Test
  fun bookLevelRuntimeSettingsImportAndExport() {
    val book =
      WorldBookFormat.importText(
        """{"name":"书","scan_depth":7,"token_budget":120,"recursive_scanning":false,
          "entries":[{"keys":["雨"],"content":"正文"}]}""",
      )
    assertEquals(7, book.scanDepth)
    assertEquals(120, book.tokenBudget)
    assertEquals(false, book.recursiveScanning)
    val out = output(book).jsonObject
    assertEquals("7", out["scan_depth"]!!.jsonPrimitive.content)
    assertEquals("120", out["token_budget"]!!.jsonPrimitive.content)
    assertEquals("false", out["recursive_scanning"]!!.jsonPrimitive.content)
    // 清空书级设置后导出必须真的删掉旧键，不能保留一个界面已不显示的值。
    val cleared = WorldBookFormat.exportText(book.copy(scanDepth = null, tokenBudget = null, recursiveScanning = null))
    assertFalse(cleared.contains("scan_depth"))
    assertFalse(cleared.contains("token_budget"))
    assertFalse(cleared.contains("recursive_scanning"))
  }

  @Test
  fun knownContainersPreserveAllJsonValuesWithoutEditing() {
    val entry = """{"id":4,"key":["词"],"content":"  原文  ","x":{"assets":["https://example.invalid/a"]}}"""
    val inputs = listOf(
      "[$entry]",
      """{"name":"书","description":"说明","tags":["a"],"category":"分类","entries":[$entry]}""",
      """{"items":[$entry],"x":2}""",
      """{"entries":{"8":$entry,"custom":$entry},"scan_depth":999}""",
      """{"spec":"lorebook_v3","spec_version":"3.0","data":{"name":"书","entries":[$entry]},"assets":[1]}""",
      """{"spec":"lorebook_v3","book":{"entries":[$entry]},"x":true}""",
      """{"data":{"character_book":{"entries":[$entry]},"name":"卡"},"unknown":9}""",
      """{"character_book":{"items":[$entry]}}""",
      """{"world":{"items":[$entry]}}""",
    )
    inputs.forEach { input ->
      val book = WorldBookFormat.importText(input)
      assertEquals(input, parse(input), output(book))
      assertEquals("  原文  ", book.entries.first().rule.content)
      assertEquals(cardHash(cardCanonical(parse(input)).toString()), book.archive!!.fingerprint)
    }
    val header = WorldBookFormat.importText(inputs[1])
    assertEquals(listOf("书", "说明", "分类"), listOf(header.name, header.description, header.category))
    assertEquals(listOf("a"), header.tags)
    assertEquals("fallback", WorldBookFormat.importText("[]", "fallback").name)
  }

  @Test
  fun duplicateExternalIdsAndIdenticalBodiesHaveIndependentInternalIdentity() {
    val input = """{"entries":[{"id":7,"content":"一样"},{"id":7,"content":"一样"}]}"""
    val first = WorldBookFormat.importText(input)
    val again = WorldBookFormat.importText(input)
    assertEquals(2, first.entries.size)
    assertEquals(2, first.entries.map { it.id }.distinct().size)
    assertTrue(first.entries.none { it.id == "7" || it.rule.sourceToken.isNotEmpty() })
    assertNotEquals(first.id, again.id)
    assertNotEquals(first.entries.first().id, again.entries.first().id)
    assertEquals(first.archive!!.fingerprint, again.archive!!.fingerprint)
    assertEquals(parse(input), output(first))
    val reorderedKeys = """{"entries":[{"content":"一样","id":7},{"content":"一样","id":7}]}"""
    assertEquals(first.archive!!.fingerprint, WorldBookFormat.importText(reorderedKeys).archive!!.fingerprint)
  }

  @Test
  fun disabledEmptyInvalidRegexAndDecoratorRowsRemainManagedAndArchived() {
    val input = """{"entries":[{"content":"停用","disable":true,"custom":1},{"content":""},
      {"content":"正则","use_regex":true},{"content":"@@activate\n条件"},
      {"content":"位置","position":3},{"content":"角色","extensions":{"role":8}},null,"odd"]}"""
    val book = WorldBookFormat.importText(input)
    assertEquals(8, book.entries.size)
    assertFalse(book.entries.first().enabled)
    assertEquals("", book.entries[1].rule.content)
    assertEquals(listOf("use_regex"), book.entries[2].unsupported)
    assertEquals(listOf("decorator"), book.entries[3].unsupported)
    assertEquals(listOf("position"), book.entries[4].unsupported)
    assertEquals(listOf("role"), book.entries[5].unsupported)
    assertEquals(listOf("invalid_entry"), book.entries.last().unsupported)
    assertEquals(parse(input), output(book))
    val edits = JsonArray(listOf(op("update", book.entries.first().id, """{"enabled":true}""")))
    val changed = WorldBookEdits.apply(book, entryOps = edits)
    val raw = output(changed).jsonObject["entries"]!!.jsonArray.first().jsonObject
    assertEquals(JsonPrimitive(false), raw["disable"])
    assertEquals(JsonPrimitive(true), raw["enabled"])
    assertEquals(JsonPrimitive(1), raw["custom"])
    assertEquals("停用", changed.entries.first().rule.content)
  }

  @Test
  fun enabledAliasConflictIsExplicitAndConservativeForBookAndEntry() {
    val input = """{"enabled":true,"disable":true,"entries":[{"content":"a","enabled":false,"disable":false}]}"""
    val book = WorldBookFormat.importText(input)
    assertFalse(book.enabled)
    assertFalse(book.entries.single().enabled)
    assertTrue(book.archive!!.notes.contains("book:enabled_alias_conflict"))
    assertTrue(book.archive!!.notes.contains("entries[0]:enabled_alias_conflict"))
    assertEquals(parse(input), output(book))
    val changed = WorldBookEdits.apply(book, fields("""{"enabled":true}"""))
    assertEquals(JsonPrimitive(false), output(changed).jsonObject["disable"])
  }

  @Test
  fun sparseAliasChangesPreserveUnknownHeadersAndEntryExtensions() {
    val input = """{"items":[{"id":99,"key":["旧"],"content":"原文","order":-8,"unknown":"x",
      "secondaryKeys":["副"],"prevent_recursion":false,"caseSensitive":false,
      "extensions":{"caseSensitive":false,"custom":{"n":9},"depth":4}}],"scan_depth":777,"assets":["ref"]}"""
    val book = WorldBookFormat.importText(input)
    val edits = JsonArray(
      listOf(
        op(
          "update",
          book.entries.single().id,
          """{"keys":["新"],"order":12,"secondary":["新副"],"case_sensitive":true,"prevent_recursion":true}""",
        ),
      ),
    )
    val changed = WorldBookEdits.apply(book, fields("""{"description":"介绍"}"""), edits)
    val out = output(changed).jsonObject
    assertEquals(JsonPrimitive(777), out["scan_depth"])
    assertEquals(parse("[\"ref\"]"), out["assets"])
    assertEquals(JsonPrimitive("介绍"), out["description"])
    val entry = out["items"]!!.jsonArray.single().jsonObject
    assertEquals(JsonPrimitive(99), entry["id"])
    assertEquals(parse("[\"新\"]"), entry["key"])
    assertEquals(JsonPrimitive(12), entry["order"])
    assertEquals(parse("[\"新副\"]"), entry["secondaryKeys"])
    assertEquals(JsonPrimitive(true), entry["prevent_recursion"])
    assertEquals(JsonPrimitive(true), entry["caseSensitive"])
    assertEquals(parse("{\"n\":9}"), entry["extensions"]!!.jsonObject["custom"])
    val roundTrip = WorldBookFormat.importText(WorldBookFormat.exportText(changed))
    assertEquals(changed.entries.single().rule, roundTrip.entries.single().rule)
  }

  @Test
  fun deletionAndMoveNeverResurrectOrGraftExtensionsAcrossDuplicateIds() {
    val book = WorldBookFormat.importText(
      """{"data":{"character_book":{"entries":[
      {"id":2,"content":"a","x":"A"},{"id":2,"content":"b","x":"B"},{"id":2,"content":"c","x":"C"}]}}}""",
    )
    val edited = WorldBookEdits.apply(
      book,
      entryOps = JsonArray(
        listOf(
          op("remove", book.entries[1].id),
          op("move", book.entries[2].id, index = 0),
          op("update", book.entries[0].id, """{"content":"edited"}"""),
        ),
      ),
    )
    val raw = output(edited).jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject["entries"]!!.jsonArray
    assertEquals(listOf("C", "A"), raw.map { it.jsonObject["x"]!!.jsonPrimitive.content })
    assertEquals(listOf("c", "edited"), raw.map { it.jsonObject["content"]!!.jsonPrimitive.content })
    assertEquals(output(edited), output(WorldBookFormat.importText(WorldBookFormat.exportText(edited))))
  }

  @Test
  fun mapKeysAreStableAndNewKeysCannotOverwriteSurvivors() {
    val book = WorldBookFormat.importText("""{"entries":{"0":{"id":0,"content":"a"},"1":{"id":0,"content":"b"}}}""")
    val edited = WorldBookEdits.apply(
      book,
      entryOps = JsonArray(
        listOf(
          op("move", book.entries[1].id, index = 0),
          op("add", changes = """{"content":"c"}"""),
        ),
      ),
    )
    val map = output(edited).jsonObject["entries"]!!.jsonObject
    assertEquals(listOf("1", "0", "2"), map.keys.toList())
    assertEquals(listOf("b", "a", "c"), map.values.map { it.jsonObject["content"]!!.jsonPrimitive.content })
    assertEquals(book.entries.map { it.id }.toSet(), edited.entries.take(2).map { it.id }.toSet())
  }

  @Test
  fun newBooksUseIndependentStandardHeaderAndNeverExportInternalMetadata() {
    val book = WorldBook(
      id = "PRIVATE_BOOK",
      revision = "PRIVATE_REV",
      name = "新书",
      entries = listOf(
        WorldBookEntry(id = "PRIVATE_ENTRY", enabled = false, rule = WorldEntry(content = "", sourceToken = "PRIVATE_TOKEN")),
      ),
    )
    val text = WorldBookFormat.exportText(book)
    val root = parse(text).jsonObject
    assertEquals(JsonPrimitive("新书"), root["name"])
    assertEquals(JsonPrimitive(false), root["entries"]!!.jsonArray.single().jsonObject["enabled"])
    assertFalse(text.contains("PRIVATE_"))
    assertEquals(1, WorldBookFormat.importText(text).entries.size)
  }

  @Test
  fun portableEmbeddingPreservesCrossBookDuplicatesAndArchivesHeadersCollisionSafely() {
    val source = """{"data":{"name":"卡","description":"角色","character_book":{
      "name":"内书","entries":[{"keys":["词"],"content":"同文"}],"extensions":{"com.zoneu.card_sources":"external"}}}}"""
    val card = CharacterImporter.fromBytes(source.toByteArray(), "卡").card
    val books = listOf("一", "二").map { name ->
      WorldBookFormat.importText("""{"name":"$name","assets":["ref"],"entries":[{"content":"同文","x":"$name"}]}""")
        .copy(revision = "PRIVATE_REV")
    }
    val before = CardExporter.toV3(card)
    val text = WorldBookFormat.portableCard(card, books)
    val embedded = parse(text).jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject
    assertEquals(3, embedded["entries"]!!.jsonArray.size)
    val ext = embedded["extensions"]!!.jsonObject
    assertEquals(JsonPrimitive("external"), ext["com.zoneu.card_sources"])
    val archived = ext["com.zoneu.card_sources.1"]!!.jsonArray
    assertEquals(2, archived.size)
    assertEquals("一", archived.first().jsonObject["header"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    assertFalse(text.contains("PRIVATE_REV"))
    books.forEach {
      assertFalse(text.contains(it.id))
      assertFalse(text.contains(it.entries.single().id))
    }
    assertFalse(text.contains("fingerprint"))
    assertFalse(text.contains("sourceToken"))
    assertEquals(before, CardExporter.toV3(card))
  }

  @Test
  fun portableDisabledAndUnsupportedRowsStayInactiveAndInvalidRowsAreNotDropped() {
    val book = WorldBookFormat.importText("""{"entries":[{"disable":true,"content":"a"},{"use_regex":true,"content":"b"},null]}""")
    val card = CharacterCard(id = "card", name = "卡")
    val text = WorldBookFormat.portableCard(card, listOf(book))
    val entries = parse(text).jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject["entries"]!!.jsonArray
    assertEquals(3, entries.size)
    assertEquals(JsonPrimitive(false), entries[0].jsonObject["enabled"])
    assertEquals(JsonPrimitive(false), entries[1].jsonObject["enabled"])
    assertEquals(parse("null"), entries[2])
    // 禁用条目现在会保留为 enabled=false（可再编辑），正则条目仍不进入运行模型。
    val roundTrip = WorldBookIo.importText(text)
    assertEquals(1, roundTrip.size)
    assertFalse(roundTrip.single().enabled)
  }

  @Test
  fun unsupportedDocumentsFailWithSafeCodesRatherThanEmptySuccessfulImports() {
    listOf("{}", "42", "\"hello\"", "{\"entries\":true}").forEach { input ->
      val error = assertThrows(WorldBookException::class.java) { WorldBookFormat.importText(input) }
      assertEquals("WORLD_UNSUPPORTED_FORMAT", error.code)
    }
    assertEquals(
      "WORLD_INVALID_JSON",
      assertThrows(WorldBookException::class.java) {
        WorldBookFormat.importText("{")
      }.code,
    )
  }

  @Test
  fun numericAndExtensionStringPositionsProjectExactlyAndMalformedRegexFailsClosed() {
    val book = WorldBookFormat.importText(
      """{"entries":[
      {"content":"before","position":0},{"content":"after","position":1},{"content":"depth","position":4},
      {"content":"extension","extensions":{"position":"before_char"}},
      {"content":"regex","use_regex":"true"}]}""",
    )
    assertEquals(listOf(0, 1, 4, 0), book.entries.take(4).map { it.rule.position })
    assertTrue(book.entries.take(4).all { it.unsupported.isEmpty() })
    assertEquals(listOf("use_regex"), book.entries.last().unsupported)
    assertEquals(book.archive!!.root, output(book))
  }

  @Test
  fun portableFreezesPerBookEnablementRatherThanLettingPrimaryHeaderDisableOtherBooks() {
    val disabled = WorldBookFormat.importText("""{"enabled":false,"disable":true,"entries":[{"content":"off"}]}""")
    val enabled = WorldBookFormat.importText("""{"entries":[{"content":"on"}]}""")
    val card = CharacterCard(id = "card", name = "card")
    val root = parse(WorldBookFormat.portableCard(card, listOf(disabled, enabled)))
    val book = root.jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject
    assertEquals(JsonPrimitive(true), book["enabled"])
    assertEquals(JsonPrimitive(false), book["disable"])
    val entries = book["entries"]!!.jsonArray
    assertEquals(JsonPrimitive(false), entries.first().jsonObject["enabled"])
    assertEquals(JsonPrimitive(true), entries.last().jsonObject["enabled"])
  }

  @Test
  fun scalarExtensionsAreNotDiscardedByRuleEditsOrPortableHeaderArchives() {
    val book = WorldBookFormat.importText(
      """{"extensions":"BOOK_EXTENSION","entries":[
      {"content":"text","extensions":[1,2]}]}""",
    )
    val changed = WorldBookEdits.apply(
      book,
      entryOps = JsonArray(
        listOf(
          op("update", book.entries.single().id, """{"depth":7,"position":0}"""),
        ),
      ),
    )
    val raw = output(changed).jsonObject["entries"]!!.jsonArray.single().jsonObject
    assertEquals(parse("[1,2]"), raw["extensions"])
    assertEquals(7, WorldBookFormat.importText(WorldBookFormat.exportText(changed)).entries.single().rule.depth)
    assertEquals(0, WorldBookFormat.importText(WorldBookFormat.exportText(changed)).entries.single().rule.position)
    val portable = parse(WorldBookFormat.portableCard(CharacterCard(id = "c", name = "card"), listOf(changed)))
    val embedded = portable.jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject
    val archive = embedded["extensions"]!!.jsonObject["com.zoneu.card_sources"]!!.jsonArray
    assertEquals(JsonPrimitive("BOOK_EXTENSION"), archive.single().jsonObject["header"]!!.jsonObject["extensions"])
  }

  @Test
  fun malformedRegexFlagsArePreservedButNeverAssumedExecutable() {
    val input = """{"entries":[{"content":"text","use_regex":"true"}]}"""
    val book = WorldBookFormat.importText(input)
    assertEquals(listOf("use_regex"), book.entries.single().unsupported)
    assertEquals(parse(input), output(book))
  }

  @Test
  fun editingBareArrayHeaderPromotesToAStandardStandaloneBook() {
    val book = WorldBookFormat.importText("""[{"content":"text","unknown":1}]""")
    val changed = WorldBookEdits.apply(book, fields("""{"name":"named"}"""))
    val out = output(changed).jsonObject
    assertEquals(JsonPrimitive("named"), out["name"])
    assertEquals(JsonPrimitive(1), out["entries"]!!.jsonArray.single().jsonObject["unknown"])
  }
}

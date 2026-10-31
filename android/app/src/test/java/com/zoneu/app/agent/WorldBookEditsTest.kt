package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEditsTest {
  private val original = WorldBook(
    id = "book",
    revision = "unchanged",
    name = "书",
    description = "介绍",
    tags = listOf("甲"),
    category = "分类",
    entries = listOf(
      WorldBookEntry(id = "first", rule = WorldEntry(content = "原文", keys = listOf("词"), sourceToken = "legacy")),
      WorldBookEntry(id = "second", rule = WorldEntry(content = "另条")),
    ),
  )

  private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun op(name: String, id: String? = null, changes: JsonObject? = null, index: Int? = null) = buildJsonObject {
    put("op", name)
    id?.let { put("entry_id", it) }
    changes?.let { put("fields", it) }
    index?.let { put("index", it) }
  }

  private fun edit(vararg ops: JsonObject, book: WorldBook = original) = WorldBookEdits.apply(book, entryOps = JsonArray(ops.toList()))

  private fun fails(code: String? = null, block: () -> Unit) {
    val error = assertThrows(WorldBookException::class.java) { block() }
    if (code != null) assertEquals(code, error.code)
    assertEquals(error.code, error.message)
  }

  @Test
  fun headerUpdatesAreSparseExactAndDoNotMoveRevisionOrEntries() {
    val changed = WorldBookEdits.apply(original, fields("""{"name":"  新名  ","tags":[],"enabled":false}"""))
    assertEquals(original.copy(name = "  新名  ", tags = emptyList(), enabled = false), changed)
    assertSame(original.entries.first(), changed.entries.first())
    val cleared = WorldBookEdits.apply(original, fields("""{"description":"","category":""}"""))
    assertEquals(original.copy(description = "", category = ""), cleared)
    assertEquals(original, WorldBookEdits.apply(original, fields("""{"tags":["甲"]}""")))
  }

  @Test
  fun clearingBookRuntimeSettingsActuallyClearsThem() {
    val configured = original.copy(scanDepth = 7, tokenBudget = 120, recursiveScanning = false)
    val cleared =
      WorldBookEdits.apply(
        configured,
        fields("""{"scan_depth":null,"token_budget":null,"recursive_scanning":null}"""),
      )
    assertNull(cleared.scanDepth)
    assertNull(cleared.tokenBudget)
    assertNull(cleared.recursiveScanning)
  }

  @Test
  fun depthZeroIsAValidEntryEdit() {
    // 编辑器标签写明「0 表示末尾」；补丁层不能把 0 当成越界，否则保存会从点击回调抛异常。
    val changed = edit(op("update", "first", fields("""{"depth":0}""")))
    assertEquals(0, changed.entries.first().rule.depth)
  }

  @Test
  fun noChangePreservesObjectIdentityTokensAndOversizedLegacyValues() {
    val legacy = original.copy(
      description = "x".repeat(100_001),
      entries = listOf(
        original.entries.first().copy(rule = WorldEntry(content = "z".repeat(100_001), depth = 99)),
      ),
    )
    assertSame(legacy, WorldBookEdits.apply(legacy))
    assertSame(legacy, WorldBookEdits.apply(legacy, fields("{}"), JsonArray(emptyList())))
    assertSame(legacy, edit(op("update", "first", fields("""{"enabled":true}""")), book = legacy))
    assertSame(legacy, edit(op("move", "first", index = 0), book = legacy))
    val changed = WorldBookEdits.apply(legacy, fields("""{"name":"new"}"""))
    assertEquals(legacy.entries, changed.entries)
    assertEquals("", changed.entries.single().rule.sourceToken)
    assertEquals(legacy.revision, changed.revision)
  }

  @Test
  fun fullRuleWhitelistMapsWithoutResettingOmittedMetadata() {
    val changes = fields(
      """{"keys":[],"content":"","order":-13,"constant":true,"depth":12,
      "secondary":["副"],"secondary_logic":"NOT_ALL","exclude":["排除"],"probability":42,"sticky":20,"cooldown":9,
      "position":0,"role":2,"use_probability":false,"match_whole_words":true,"case_sensitive":true,
      "prevent_recursion":true,"exclude_recursion":true,"enabled":false}""",
    )
    val changed = edit(op("update", "first", changes))
    val expected = WorldEntry(
      content = "",
      order = -13,
      constant = true,
      depth = 12,
      secondary = listOf("副"),
      secondaryLogic = SecondaryLogic.NOT_ALL,
      exclude = listOf("排除"),
      probability = 42,
      sticky = 20,
      cooldown = 9,
      position = 0,
      role = 2,
      useProbability = false,
      matchWholeWords = true,
      caseSensitive = true,
      preventRecursion = true,
      excludeRecursion = true,
      sourceToken = "legacy",
    )
    assertEquals(original.entries.first().copy(enabled = false, rule = expected), changed.entries.first())
    assertSame(original.entries.last(), changed.entries.last())
    assertEquals(original.revision, changed.revision)
  }

  @Test
  fun longNewContentAllowedEmptyContentSavedAndAddedIdsAreIndependent() {
    val long = "z".repeat(100_000)
    val changed = edit(
      op("add", changes = buildJsonObject { put("content", long) }),
      op("add", changes = fields("""{"content":"","enabled":false}""")),
    )
    assertEquals(4, changed.entries.size)
    assertEquals(long, changed.entries[2].rule.content)
    assertFalse(changed.entries.last().enabled)
    assertEquals("", changed.entries.last().rule.content)
    assertNotEquals(changed.entries[2].id, changed.entries[3].id)
    assertTrue(changed.entries.takeLast(2).all { it.rule.sourceToken.isEmpty() })
    assertEquals(original.revision, changed.revision)
  }

  @Test
  fun sameOldEntryMayNotBeEditedMovedOrRemovedTwiceAndMoveUsesCurrentIndices() {
    fails("WORLD_ENTRY_CONFLICT") {
      edit(op("update", "first", fields("""{"content":"x"}""")), op("move", "first", index = 0))
    }
    fails("WORLD_ENTRY_CONFLICT") { edit(op("remove", "first"), op("remove", "first")) }
    fails("WORLD_ENTRY_NOT_FOUND") { edit(op("remove", "missing")) }
    val moved = edit(op("move", "second", index = 0), op("update", "first", fields("""{"content":"x"}""")))
    assertEquals(listOf("second", "first"), moved.entries.map { it.id })
    assertEquals("x", moved.entries.last().rule.content)
    fails { edit(op("move", "first", index = 2)) }
    fails { edit(op("move", "first", index = -1)) }
  }

  @Test
  fun rawAndUnsupportedCapabilitiesCannotBeForgedOrEnabledAround() {
    val imported = WorldBookFormat.importText("""{"entries":[{"content":"a","use_regex":true,"enabled":false}]}""")
    val entry = imported.entries.single()
    val enabled = edit(op("update", entry.id, fields("""{"enabled":true,"content":"b"}""")), book = imported)
    assertEquals(listOf("use_regex"), enabled.entries.single().unsupported)
    assertTrue(enabled.entries.single().enabled)
    assertSame(entry.raw, enabled.entries.single().raw)
    listOf("raw", "unsupported", "externalKey", "id", "baseline", "sourceToken", "use_regex").forEach { key ->
      fails { edit(op("update", "first", buildJsonObject { put(key, "forged") })) }
    }
  }

  @Test
  fun nullUnknownTypesRangesAndFullArrayReplacementAreRejected() {
    listOf(
      """{"name":" "}""",
      """{"name":null}""",
      """{"description":null}""",
      """{"category":0}""",
      """{"tags":[null]}""",
      """{"enabled":"true"}""",
      """{"raw":{}}""",
      """{"entries":[]}""",
    ).forEach { input -> fails { WorldBookEdits.apply(original, fields(input)) } }
    listOf(
      """{"depth":-1}""",
      """{"depth":13}""",
      """{"order":2147483648}""",
      """{"order":null}""",
      """{"content":null}""",
      """{"enabled":null}""",
      """{"keys":[null]}""",
      """{"probability":101}""",
      """{"position":3}""",
      """{"role":4}""",
      """{"secondary_logic":0}""",
      """{"constant":1}""",
    ).forEach { input -> fails { edit(op("update", "first", fields(input))) } }
    listOf(
      op("add"),
      op("add", changes = fields("{}")),
      op("update", "first"),
      op("replace", "first"),
      op("remove", "first", fields("{}")),
      op("add", "first", fields("""{"content":"x"}""")),
    ).forEach { invalid -> fails { edit(invalid) } }
  }

  @Test
  fun boundedNewFieldsOperationsAndRequestSizeFailWithSafeCodes() {
    fails("WORLD_FIELD_TOO_LARGE") {
      WorldBookEdits.apply(original, buildJsonObject { put("description", "x".repeat(100_001)) })
    }
    fails("WORLD_FIELD_TOO_LARGE") {
      edit(op("add", changes = buildJsonObject { put("content", "x".repeat(100_001)) }))
    }
    fails("WORLD_FIELD_TOO_LARGE") {
      edit(op("update", "first", JsonObject(mapOf("keys" to JsonArray(List(25) { JsonPrimitive("x") })))))
    }
    fails("WORLD_FIELD_TOO_LARGE") {
      WorldBookEdits.apply(original, entryOps = JsonArray(List(201) { op("add", changes = fields("""{"content":""}""")) }))
    }
    fails("WORLD_FIELD_TOO_LARGE") {
      val changes = buildJsonObject { put("content", "x".repeat(100_000)) }
      WorldBookEdits.apply(original, entryOps = JsonArray(List(85) { op("add", changes = changes) }))
    }
  }

  @Test
  fun publicProjectionContainsOnlyWhitelistedAssetAndRuleFields() {
    val imported = WorldBookFormat.importText("""{"entries":[{"content":"text","secret_extension":"RAW_PRIVATE"}]}""")
    val public = WorldBookEdits.publicBook(imported).toString()
    assertFalse(public.contains("RAW_PRIVATE"))
    assertFalse(public.contains("fingerprint"))
    assertFalse(public.contains("sourceToken"))
    assertFalse(public.contains("externalKey"))
    assertFalse(public.contains("baseline"))
    val entry = WorldBookEdits.publicEntry(imported.entries.single())
    assertEquals(JsonPrimitive(imported.entries.single().id), entry["entry_id"])
    assertTrue(entry.keys.containsAll(listOf("enabled", "unsupported", "keys", "content", "order", "depth", "prevent_recursion")))
  }

  @Test
  fun deterministicPreviewUsesInjectedIdentityAndRejectsOriginalIdentityReuse() {
    val ops = JsonArray(listOf(op("add", changes = fields("""{"content":"draft"}"""))))
    val first = WorldBookEdits.apply(original, entryOps = ops, newEntryId = { "preview-entry" })
    val second = WorldBookEdits.apply(original, entryOps = ops, newEntryId = { "preview-entry" })
    assertEquals(first, second)
    assertEquals("preview-entry", first.entries.last().id)
    fails("WORLD_ENTRY_CONFLICT") {
      WorldBookEdits.apply(
        original,
        entryOps = JsonArray(
          listOf(
            op("remove", "first"),
            op("add", changes = fields("""{"content":"new"}""")),
          ),
        ),
        newEntryId = { "first" },
      )
    }
    assertSame(
      original,
      WorldBookEdits.apply(original, fields("""{"name":"书"}"""), newEntryId = {
        throw AssertionError("No-op must not request an ID")
      }),
    )
  }

  @Test
  fun newlyAuthoredDecoratorContentIsMarkedUnsupportedWithoutLosingItsText() {
    val changes = fields("""{"content":"@@activate private condition"}""")
    val added = edit(op("add", changes = changes)).entries.last()
    assertEquals(listOf("decorator"), added.unsupported)
    assertEquals("@@activate private condition", added.rule.content)
    val updated = edit(op("update", "first", changes)).entries.first()
    assertEquals(listOf("decorator"), updated.unsupported)
    assertEquals("legacy", updated.rule.sourceToken)
  }
}

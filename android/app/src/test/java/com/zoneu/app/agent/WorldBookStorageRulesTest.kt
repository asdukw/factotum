package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WorldBookStorageRulesTest {
  @Test
  fun casRequiresTheExactExpectedRevision() {
    WorldBookStorageRules.cas("head", "head")
    assertEquals(
      "WORLD_REVISION_CONFLICT",
      assertThrows(WorldBookException::class.java) {
        WorldBookStorageRules.cas("head", "stale")
      }.code,
    )
  }

  @Test
  fun parameterHashesCanonicalizeObjectsButBindActualChangesEvenWithCallerParameters() {
    assertEquals(
      worldParameters("update", "{\"b\":2,\"a\":1}", "x").parameters,
      worldParameters("update", "{\"a\":1,\"b\":2}", "x").parameters,
    )
    assertNotEquals(worldParameters("update", "", "first"), worldParameters("update", "", "second"))
    val before = worldParameters("update", "same caller arguments", "first")
    val after = worldParameters("update", "same caller arguments", "second")
    assertEquals(before.parameters, after.parameters)
    assertNotEquals(before.actual, after.actual)
  }

  @Test
  fun duplicateBooksPreserveEverySourceAndDisabledWins() {
    val a = WorldBookBinding("book", "pin", listOf("character:a"), WorldOverride.ENABLE)
    val b = a.copy(sources = listOf("character:b", "character:a"), override = WorldOverride.DISABLE)
    val result = worldNormalizeManifest(WorldManifest("s", books = listOf(a, b)), null).books.single()
    assertEquals(listOf("character:a", "character:b"), result.sources)
    assertEquals(WorldOverride.DISABLE, result.override)
    assertFalse(result.epoch.isBlank())
    assertThrows(WorldBookException::class.java) {
      worldNormalizeManifest(WorldManifest("s", books = listOf(a, b.copy(revision = "other"))), null)
    }
  }

  @Test
  fun semanticChangesResetOnlyTheAffectedEpochAndDraftEpochCannotResetTiming() {
    val a = WorldBookBinding("a", "pin-a", listOf("direct"), epoch = "epoch-a")
    val b = WorldBookBinding("b", "pin-b", listOf("direct"), epoch = "epoch-b")
    val current = WorldManifest("s", "manifest", listOf(a, b))
    val draft = current.copy(books = listOf(a.copy(epoch = "forged"), b.copy(override = WorldOverride.DISABLE)))
    val saved = worldNormalizeManifest(draft, current)
    assertEquals("epoch-a", saved.books[0].epoch)
    assertNotEquals("epoch-b", saved.books[1].epoch)
    val enableDraft = saved.copy(books = listOf(saved.books[0], saved.books[1].copy(override = WorldOverride.ENABLE)))
    val enabled = worldNormalizeManifest(enableDraft, saved)
    assertNotEquals(saved.books[1].epoch, enabled.books[1].epoch)
    assertEquals(current, current.copy())
  }

  @Test
  fun creationFingerprintIgnoresGeneratedIdentitiesButNotEntryOrderOrBody() {
    val entries = listOf(WorldBookEntry(rule = WorldEntry(content = "one")), WorldBookEntry(rule = WorldEntry(content = "two")))
    val book = WorldBook(entries = entries)
    val renamed = book.copy(id = UUID.randomUUID().toString(), entries = book.entries.map { it.copy(id = UUID.randomUUID().toString()) })
    assertEquals(WorldBookStorageRules.createDraft(book), WorldBookStorageRules.createDraft(renamed))
    val reordered = WorldBookStorageRules.createDraft(book.copy(entries = book.entries.reversed()))
    assertNotEquals(WorldBookStorageRules.createDraft(book), reordered)
  }

  @Test
  fun stateAdvancesOnlyTheSelectedKeywordKeysAndRejectsStaleTurns() {
    val state = WorldRuntimeState(4, mapOf("old" to 2))
    val plan = plan(5, listOf("new"))
    assertEquals(WorldRuntimeState(5, mapOf("old" to 2, "new" to 5)), worldSettleState(state, plan))
    assertThrows(WorldBookException::class.java) { worldSettleState(state, plan.copy(turn = 4)) }
    assertThrows(WorldBookException::class.java) { worldSettleState(state, plan.copy(activated = listOf("not-selected"))) }
    assertTrue(state.activated.keys == setOf("old"))
  }

  private fun plan(
    turn: Int,
    activated: List<String>,
  ) = WorldRequestPlan(
    requestId = "request",
    sessionId = "session",
    bindingRevision = "empty",
    generation = "test",
    turn = turn,
    seed = 1,
    injections = listOf(WorldInjection("entry", "new", "depth", 0, "system", "synthetic")),
    decisions = emptyList(),
    activated = activated,
  )
}

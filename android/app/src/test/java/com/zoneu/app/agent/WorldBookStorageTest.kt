package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookStorageTest {
  @Test
  fun casRejectsStaleVersionsAndAcceptsTheExactVersion() {
    worldCas("one", "one")
    fails("WORLD_REVISION_CONFLICT") { worldCas("two", "one") }
  }

  @Test
  fun fingerprintCanonicalizesObjectsButNotArrayOrder() {
    val a = Json.parseToJsonElement("""{"a":1,"b":{"d":3,"c":2}}""")
    val b = Json.parseToJsonElement("""{"b":{"c":2,"d":3},"a":1}""")
    assertEquals(worldCanonical(a), worldCanonical(b))
    assertNotEquals(worldCanonical(Json.parseToJsonElement("[1,2]")), worldCanonical(Json.parseToJsonElement("[2,1]")))
    assertNotEquals(worldParameters("create", "", "one"), worldParameters("create", "", "two"))
  }

  @Test
  fun hostAndPluginParameterPayloadsHashIdentically() {
    val args =
      Json.parseToJsonElement(
        """{"book_id":"00000000-0000-4000-8000-000000000001","expected_revision":"r1","fields":{"description":"新"}}""",
      ).jsonObject
    val host = WorldBookCommand.parse("update_world_book", args).parameters
    // Exact shape produced by world-tools.mjs: canonical({operation: <tool>, arguments}).
    val plugin =
      "{\"arguments\":{\"book_id\":\"00000000-0000-4000-8000-000000000001\",\"expected_revision\":\"r1\"," +
        "\"fields\":{\"description\":\"新\"}},\"operation\":\"update_world_book\"}"
    assertEquals(
      WorldBookStorageRules.parameterHash(host, host),
      WorldBookStorageRules.parameterHash(plugin, plugin),
    )
  }

  @Test
  fun manifestDeduplicatesSourcesAndDisableWinsWithoutCombiningDifferentBooks() {
    val draft =
      WorldManifest(
        "session",
        books =
          listOf(
            binding("book", "rev", "character:a"),
            binding("book", "rev", "character:b", WorldOverride.ENABLE),
            binding("book", "rev", "character:a", WorldOverride.DISABLE),
            binding("other", "rev", "character:a"),
          ),
      )
    val normalized = worldNormalizeManifest(draft, null)
    assertEquals(2, normalized.books.size)
    assertEquals(listOf("character:a", "character:b"), normalized.books.first().sources)
    assertEquals(WorldOverride.DISABLE, normalized.books.first().override)
  }

  @Test
  fun ambiguousPinsAreNotSilentlyUpgraded() {
    fails("WORLD_BINDING_CONFLICT") {
      worldNormalizeManifest(WorldManifest("s", books = listOf(binding("b", "r1", "a"), binding("b", "r2", "b"))), null)
    }
  }

  @Test
  fun manifestEpochIsRepositoryOwnedAndOnlyChangedBindingsReset() {
    val existing = WorldManifest("s", "binding-rev", listOf(binding("a", "r1", "role"), binding("b", "r2", "role")))
    val draft = existing.copy(books = existing.books.map { it.copy(epoch = "forged") })
    assertEquals(existing, worldNormalizeManifest(draft, existing))
    val disableDraft = draft.copy(books = listOf(draft.books[0].copy(override = WorldOverride.DISABLE), draft.books[1]))
    val changed = worldNormalizeManifest(disableDraft, existing)
    assertNotEquals(existing.books[0].epoch, changed.books[0].epoch)
    assertEquals(existing.books[1].epoch, changed.books[1].epoch)
    val reopened = worldNormalizeManifest(existing, changed)
    assertNotEquals(existing.books[0].epoch, reopened.books[0].epoch)
  }

  @Test
  fun settleAdvancesOnlyThePlannedNextTurnAndOnlySelectedKeywordKeys() {
    val state = WorldRuntimeState(3, mapOf("old" to 1))
    val plan = plan(turn = 4)
    val settled = worldSettleState(state, plan)
    assertEquals(WorldRuntimeState(4, mapOf("old" to 1, "keyword" to 4)), settled)
    assertFalse("constant" in settled.activated)
    fails("WORLD_TURN_CONFLICT") { worldSettleState(settled, plan) }
    fails("WORLD_PLAN_INVALID") { worldSettleState(state, plan.copy(activated = listOf("not-injected"))) }
  }

  @Test
  fun planFingerprintFixesSeedDecisionsAndMacroOutputAcrossRecollection() {
    val plan = plan(turn = 1)
    assertEquals(worldPlanHash(plan), worldPlanHash(plan.copy(status = "settled")))
    assertNotEquals(worldPlanHash(plan), worldPlanHash(plan.copy(seed = 7)))
    assertNotEquals(worldPlanHash(plan), worldPlanHash(plan.copy(messages = listOf(WorldPromptMessage("system", "changed macro")))))
    assertTrue(worldUuid(WorldBook().id))
    assertFalse(worldUuid("external-id"))
  }

  private fun binding(
    id: String,
    revision: String,
    source: String,
    override: WorldOverride = WorldOverride.INHERIT,
  ) = WorldBookBinding(id, revision, listOf(source), override)

  private fun plan(turn: Int) =
    WorldRequestPlan(
      requestId = "request",
      sessionId = "session",
      bindingRevision = "revision",
      generation = "send",
      turn = turn,
      seed = 1,
      injections =
        listOf(
          WorldInjection("e", "keyword", "depth", 0, "system", "keyword content"),
          WorldInjection("c", "constant", "depth", 0, "system", "constant content"),
        ),
      decisions = emptyList(),
      activated = listOf("keyword"),
    )

  private fun fails(
    code: String,
    body: () -> Unit,
  ) {
    val error = runCatching(body).exceptionOrNull()
    assertTrue("Expected $code, got ${error?.javaClass?.simpleName}", error is WorldBookException)
    assertEquals(code, (error as WorldBookException).code)
  }
}

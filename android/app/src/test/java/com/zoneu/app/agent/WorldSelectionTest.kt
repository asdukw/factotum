package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldSelectionTest {
  private fun row(id: String, rule: WorldEntry = WorldEntry(keys = listOf("雨"), content = id)): WorldResolvedEntry =
    WorldResolvedEntry(id, "book", "revision", id, "epoch", listOf("session"), true, emptyList(), rule)

  @Test
  fun disabledEmptyAndUnsupportedCannotEnterByConstantStickyOrRecursion() {
    val entries = listOf(
      row("seed", WorldEntry(content = "雨", constant = true)),
      row("disabled", WorldEntry(content = "disabled", constant = true, sticky = 5)).copy(enabled = false),
      row("empty", WorldEntry(content = "", constant = true, sticky = 5)),
      row("unsupported", WorldEntry(content = "unsupported", constant = true, sticky = 5)).copy(unsupported = listOf("regex")),
    )
    val result = WorldSelection.select(WorldSelection.Input(entries, "雨", WorldRuntimeState(1, entries.associate { it.key to 1 })))
    assertEquals(listOf("seed"), result.injections.map { it.entryId })
    assertEquals(listOf("constant", "disabled", "empty", "unsupported"), result.decisions.map { it.reason })
  }

  @Test
  fun constantEntriesMustPassProbabilityExclusionAndBudget() {
    val entries = listOf(
      row("zero", WorldEntry(content = "zero", constant = true, probability = 0)),
      row("excluded", WorldEntry(content = "excluded", constant = true, exclude = listOf("雨"))),
      row("large", WorldEntry(content = "0123456789", constant = true)),
      row("fits", WorldEntry(content = "yes", constant = true)),
    )
    val result = WorldSelection.select(WorldSelection.Input(entries, "雨", limits = WorldScan.Limits(maxChars = 3)))
    assertEquals(listOf("fits"), result.injections.map { it.entryId })
    assertTrue(result.activated.isEmpty())
  }

  @Test
  fun recursiveCandidatesUseSecondaryProbabilityExclusionCooldownAndBudget() {
    val seed = row("seed", WorldEntry(content = "密码 许可", constant = true))
    val entries = listOf(
      seed,
      row("secondary", WorldEntry(keys = listOf("密码"), secondary = listOf("不存在"), content = "bad")),
      row("probability", WorldEntry(keys = listOf("密码"), probability = 0, content = "bad")),
      row("excluded", WorldEntry(keys = listOf("密码"), exclude = listOf("许可"), content = "bad")),
      row("cooldown", WorldEntry(keys = listOf("密码"), cooldown = 3, content = "bad")),
      row("valid", WorldEntry(keys = listOf("密码"), secondary = listOf("许可"), content = "yes")),
    )
    val state = WorldRuntimeState(1, mapOf("cooldown" to 1))
    val result = WorldSelection.select(WorldSelection.Input(entries, "hello", state))
    assertEquals(listOf("seed", "valid"), result.injections.map { it.entryId })
    assertEquals(listOf("valid"), result.activated)
  }

  @Test
  fun stickyDoesNotRefreshItselfAndBudgetRejectedRowsDoNotActivate() {
    val entries = listOf(
      row("sticky", WorldEntry(content = "yes", sticky = 2, cooldown = 4)),
      row("large", WorldEntry(keys = listOf("雨"), content = "too large")),
    )
    val result = WorldSelection.select(
      WorldSelection.Input(
        entries,
        "雨",
        WorldRuntimeState(
          1,
          mapOf("sticky" to 1),
        ),
        limits = WorldScan.Limits(maxChars = 3),
      ),
    )
    assertEquals(listOf("sticky"), result.injections.map { it.entryId })
    assertTrue(result.activated.isEmpty())
    assertEquals("budget", result.decisions.last().reason)
  }

  @Test
  fun explainNeverCallsRendererAndProbabilityRemainsPending() {
    val entries = listOf(
      row("write", WorldEntry(content = "{{incvar::mood}}", constant = true)),
      row("random", WorldEntry(content = "random", constant = true, probability = 50)),
    )
    val result = WorldSelection.select(WorldSelection.Input(entries, "", explain = true)) { _, _ -> error("explain rendered a macro") }
    assertEquals("{{incvar::mood}}", result.injections.single().content)
    assertEquals("probability_pending", result.decisions.last().reason)
    assertTrue(result.activated.isEmpty())
  }

  @Test
  fun selectionIsStableAcrossRetriesAndUnrelatedRowsDoNotPerturbProbability() {
    val chance = row("chance", WorldEntry(content = "chance", constant = true, probability = 50))
    val first = WorldSelection.select(WorldSelection.Input(listOf(chance), "", seed = 42))
    assertEquals(first, WorldSelection.select(WorldSelection.Input(listOf(chance), "", seed = 42)))
    val withOther = WorldSelection.select(WorldSelection.Input(listOf(row("disabled").copy(enabled = false), chance), "", seed = 42))
    assertEquals(first.injections, withOther.injections)
  }

  @Test
  fun sourceIdentityUsesFullContentSessionRevisionAndEpoch() {
    val a = WorldEntry(keys = listOf("雨"), content = "x".repeat(80) + "a")
    val b = a.copy(content = "x".repeat(80) + "b")
    assertNotEquals(WorldTracking.key(a), WorldTracking.key(b))
    val key = WorldResolver.identity("session", "book", "entry", "revision", "epoch")
    assertEquals(64, key.length)
    assertNotEquals(key, WorldResolver.identity("other", "book", "entry", "revision", "epoch"))
    assertNotEquals(key, WorldResolver.identity("session", "book", "entry", "new", "epoch"))
    assertNotEquals(key, WorldResolver.identity("session", "book", "entry", "revision", "new"))
    assertFalse(key.contains("session"))
  }
}

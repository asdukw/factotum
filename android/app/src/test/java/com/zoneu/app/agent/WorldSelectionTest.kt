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
  fun constantEntriesIgnoreProbabilityAndExclusionAndUseTheirOwnBudget() {
    val entries = listOf(
      row("zero", WorldEntry(content = "zero", constant = true, probability = 0)),
      row("excluded", WorldEntry(content = "excluded", constant = true, exclude = listOf("雨"))),
      row("large", WorldEntry(content = "0123456789", constant = true)),
      row("fits", WorldEntry(content = "yes", constant = true)),
    )
    val result =
      WorldSelection.select(
        WorldSelection.Input(entries, "雨", limits = WorldScan.Limits(maxChars = 3, maxConstantChars = 3)),
      )
    // 概率/排除词仍然生效；常驻只换用自己的字数预算。
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
  fun explainRendersForBudgetParityButLeavesProbabilityPending() {
    val entries = listOf(
      row("write", WorldEntry(content = "{{incvar::mood}}", constant = true)),
      row("random", WorldEntry(content = "random", constant = true, probability = 50)),
    )
    var rendered = 0
    val result =
      WorldSelection.select(WorldSelection.Input(entries, "", explain = true)) { entry, _ ->
        rendered++
        entry.rule.content
      }
    assertEquals(1, rendered)
    assertEquals("{{incvar::mood}}", result.injections.single().content)
    assertEquals("probability_pending", result.decisions.last().reason)
    assertTrue(result.activated.isEmpty())
  }

  @Test
  fun selectiveFalseDisablesSecondaryKeys() {
    val entry = row("e", WorldEntry(keys = listOf("雨"), secondary = listOf("伞"), selective = false, content = "ok"))
    val result = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨"))
    assertEquals(listOf("e"), result.injections.map { it.entryId })
  }

  @Test
  fun stickySkipsProbabilityExclusionAndSecondary() {
    val entry =
      row(
        "sticky",
        WorldEntry(
          keys = listOf("雨"),
          secondary = listOf("伞"),
          exclude = listOf("雨"),
          probability = 0,
          sticky = 3,
          content = "kept",
        ),
      )
    val result =
      WorldSelection.select(
        WorldSelection.Input(listOf(entry), "雨", WorldRuntimeState(5, mapOf(entry.key to 5))),
      )
    assertEquals(listOf("sticky"), result.injections.map { it.entryId })
    assertTrue(result.activated.isEmpty())
  }

  @Test
  fun groupOverrideKeepsOnlyTheHighestOrderMember() {
    val low = row("low", WorldEntry(keys = listOf("雨"), content = "low", group = "scene", order = 1))
    val high = row("high", WorldEntry(keys = listOf("雨"), content = "high", group = "scene", groupOverride = true, order = 9))
    val result = WorldSelection.select(WorldSelection.Input(listOf(low, high), "雨"))
    assertEquals(listOf("high"), result.injections.map { it.entryId })
    assertEquals("group", result.decisions.first { it.entryId == "low" }.reason)
  }

  @Test
  fun weightedGroupsStillSelectExactlyOneDeterministicMember() {
    val a = row("a", WorldEntry(keys = listOf("雨"), content = "a", group = "scene", groupWeight = 1))
    val b = row("b", WorldEntry(keys = listOf("雨"), content = "b", group = "scene", groupWeight = 9))
    val first = WorldSelection.select(WorldSelection.Input(listOf(a, b), "雨", seed = 7))
    val second = WorldSelection.select(WorldSelection.Input(listOf(a, b), "雨", seed = 7))
    assertEquals(1, first.injections.size)
    assertEquals(first.injections, second.injections)
  }

  @Test
  fun delaySuppressesEarlyTurns() {
    val entry = row("delay", WorldEntry(keys = listOf("雨"), delay = 5, content = "later"))
    val early = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨", WorldRuntimeState(1, emptyMap())))
    val ready = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨", WorldRuntimeState(5, emptyMap())))
    assertEquals("delay", early.decisions.single().reason)
    assertEquals(listOf("delay"), ready.injections.map { it.entryId })
  }

  @Test
  fun delayUntilRecursionWaitsForTheRequestedPass() {
    val seed = row("seed", WorldEntry(content = "雨", constant = true))
    val gated = row("gated", WorldEntry(keys = listOf("雨"), delayUntilRecursion = 2, content = "deep"))
    val result =
      WorldSelection.select(
        WorldSelection.Input(listOf(seed, gated), "雨", limits = WorldScan.Limits(depth = 2)),
      )
    assertEquals(listOf("seed", "gated"), result.injections.map { it.entryId })
    assertEquals("recursive_gated", result.decisions.first { it.entryId == "gated" }.reason)
  }

  @Test
  fun triggersFilterByGenerationType() {
    val entry = row("regen", WorldEntry(keys = listOf("雨"), triggers = listOf("regenerate"), content = "again"))
    val normal = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨"))
    val regen =
      WorldSelection.select(
        WorldSelection.Input(listOf(entry), "雨", generation = WorldSelection.GENERATION_REGENERATE),
      )
    assertEquals("trigger", normal.decisions.single().reason)
    assertEquals(listOf("regen"), regen.injections.map { it.entryId })
  }

  @Test
  fun characterFilterMatchesNamesAndTagsWithExclude() {
    val onlyA = row("a", WorldEntry(keys = listOf("雨"), characterFilterNames = listOf("A"), content = "a"))
    val tag = row("tag", WorldEntry(keys = listOf("雨"), characterFilterTags = listOf("奇幻"), content = "tag"))
    val notA =
      row(
        "notA",
        WorldEntry(keys = listOf("雨"), characterFilterNames = listOf("A"), characterFilterExclude = true, content = "n"),
      )
    val matching =
      WorldSelection.select(
        WorldSelection.Input(listOf(onlyA, tag, notA), "雨", characterNames = listOf("B"), characterTags = setOf("奇幻")),
      )
    assertEquals(listOf("tag", "notA"), matching.injections.map { it.entryId })
    assertEquals("character_filter", matching.decisions.first { it.entryId == "a" }.reason)
  }

  @Test
  fun perEntryScanDepthCanLookFurtherBackThanTheDefault() {
    val history =
      listOf(
        TavernMessage(TavernMessage.USER, "你校徽歪了"),
        TavernMessage(TavernMessage.ASSISTANT, "我扶一下。"),
        TavernMessage(TavernMessage.USER, "走吧"),
        TavernMessage(TavernMessage.ASSISTANT, "嗯。"),
      )
    val shallow = row("shallow", WorldEntry(keys = listOf("校徽"), content = "silver"))
    val deep = row("deep", WorldEntry(keys = listOf("校徽"), content = "silver", scanDepth = 4))
    val result =
      WorldSelection.select(
        WorldSelection.Input(listOf(shallow, deep), "", history = history, scanDepth = 2),
      )
    assertEquals(listOf("deep"), result.injections.map { it.entryId })
  }

  @Test
  fun nonAdvancingGenerationsReuseTheCurrentTurn() {
    val entry = row("e", WorldEntry(keys = listOf("雨"), cooldown = 2, content = "ok"))
    val state = WorldRuntimeState(3, mapOf(entry.key to 1))
    val variant = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨", state, turnAdvance = false))
    val next = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨", state, turnAdvance = true))
    assertEquals("cooldown", variant.decisions.single().reason)
    assertEquals(listOf("e"), next.injections.map { it.entryId })
  }

  @Test
  fun bookBudgetCapsEntriesFromThatBookOnly() {
    val tooLong = row("long", WorldEntry(keys = listOf("雨"), content = "12345")).copy(bookBudget = 3)
    val fits = row("fits", WorldEntry(keys = listOf("雨"), content = "123")).copy(bookBudget = 3)
    val result = WorldSelection.select(WorldSelection.Input(listOf(tooLong, fits), "雨"))
    assertEquals(listOf("fits"), result.injections.map { it.entryId })
    assertEquals("budget", result.decisions.first { it.entryId == "long" }.reason)
  }

  @Test
  fun cooldownStartsAfterTheStickyWindowEnds() {
    val entry = row("e", WorldEntry(keys = listOf("雨"), sticky = 2, cooldown = 2, content = "ok"))
    // 上次激活在第 1 轮；第 4 轮粘性窗口已结束，但冷却还应再持续 2 轮。
    val result = WorldSelection.select(WorldSelection.Input(listOf(entry), "雨", WorldRuntimeState(3, mapOf(entry.key to 1))))
    assertEquals("cooldown", result.decisions.single().reason)
  }

  @Test
  fun sameTurnReplayDoesNotTripCooldown() {
    val entry = row("e", WorldEntry(keys = listOf("雨"), cooldown = 2, content = "ok"))
    // 正常轮在第 1 轮激活了它；重写/续写复用第 1 轮时不能把它当成「冷却中」。
    val result =
      WorldSelection.select(
        WorldSelection.Input(listOf(entry), "雨", WorldRuntimeState(1, mapOf(entry.key to 1)), turnAdvance = false),
      )
    assertEquals(listOf("e"), result.injections.map { it.entryId })
  }

  @Test
  fun ignoreBudgetEntriesDoNotConsumeTheEntryQuota() {
    val outside = row("outside", WorldEntry(keys = listOf("雨"), content = "a", ignoreBudget = true))
    val inside = row("inside", WorldEntry(keys = listOf("雨"), content = "b"))
    val result =
      WorldSelection.select(
        WorldSelection.Input(listOf(outside, inside), "雨", limits = WorldScan.Limits(maxEntries = 1)),
      )
    assertEquals(listOf("outside", "inside"), result.injections.map { it.entryId })
  }

  @Test
  fun bookBudgetIsHandedToTheRendererBeforeCommit() {
    val entry = row("e", WorldEntry(keys = listOf("雨"), content = "unused")).copy(bookBudget = 3)
    var budgetSeen = -1
    WorldSelection.select(WorldSelection.Input(listOf(entry), "雨")) { _, budget ->
      budgetSeen = budget
      "12345"
    }
    // 渲染器看到的是「本书还能用多少」，而不是全局剩余；超长的 setvar 因此不会被提交。
    assertEquals(3, budgetSeen)
  }

  @Test
  fun injectionsAreCappedBelowTheDshProjectionLimit() {
    val constants = (1..125).map { row("c$it", WorldEntry(content = "x", constant = true)) }
    val result = WorldSelection.select(WorldSelection.Input(constants, "", limits = WorldScan.Limits(maxConstantChars = 2_000)))
    assertEquals(WorldSelection.MAX_INJECTIONS, result.injections.size)
  }

  @Test
  fun hugeGroupWeightsDoNotOverflow() {
    val a = row("a", WorldEntry(keys = listOf("雨"), content = "a", group = "g", groupWeight = Int.MAX_VALUE))
    val b = row("b", WorldEntry(keys = listOf("雨"), content = "b", group = "g", groupWeight = Int.MAX_VALUE))
    val result = WorldSelection.select(WorldSelection.Input(listOf(a, b), "雨", seed = 3))
    assertEquals(1, result.injections.size)
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

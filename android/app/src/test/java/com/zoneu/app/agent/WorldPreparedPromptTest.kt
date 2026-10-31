package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldPreparedPromptTest {
  private val card = CharacterCard(id = "synthetic-card", name = "Synthetic", description = "role body")
  private val history = listOf(TavernMessage("user", "one"), TavernMessage("assistant", "two"), TavernMessage("user", "three"))

  private fun injection(id: String, depth: Int = 0, position: String = "depth", role: String = "system") =
    WorldInjection(id, id, position, depth, role, "lore-$id")

  @Test fun depthZeroAndSameDepthOrderingCountOriginalHistoryOnly() {
    val injections = listOf(injection("a", 2), injection("b", 2), injection("zero", 0), injection("deep", 12))
    val result = TavernPrompt.build(card, "", history, state = TavernPrompt.TurnState(injections = injections))
    assertEquals(
      listOf("lore-deep", "one", "lore-a", "lore-b", "two", "three", "lore-zero"),
      result.drop(1).dropLast(1).map { it.content },
    )
    assertEquals("system", result[result.size - 2].role)
  }

  @Test fun preparedWorldNeverRescansCardConstantsOrKeywords() {
    val withWorld = card.copy(
      world = listOf(
        WorldEntry(content = "legacy-constant", constant = true),
        WorldEntry(keys = listOf("one"), content = "legacy-keyword"),
      ),
    )
    val result = TavernPrompt.build(withWorld, "", history, state = TavernPrompt.TurnState(injections = emptyList()))
    assertFalse(result.any { "legacy-" in it.content })
  }

  @Test fun characterRelativePlacementFallsBackWhenTheTemplateLacksTheSlot() {
    val bad = TavernPreset.DEFAULT.copy(system = "unanchored role")
    val result =
      TavernPrompt.build(
        card,
        "",
        history,
        bad,
        state = TavernPrompt.TurnState(
          injections = listOf(injection("before", position = "before")),
        ),
      )
    val system = result.first().content
    assertTrue(system.startsWith("unanchored role"))
    assertTrue(system.contains("lore-before"))
  }

  @Test fun beforeAfterOccupyTheirExplicitRoleSlotsAndDoNotChangeInput() {
    val original = history.toList()
    val result = TavernPrompt.build(
      card,
      "",
      history,
      state = TavernPrompt.TurnState(
        injections = listOf(
          injection(
            "before",
            position = "before",
          ),
          injection(
            "after",
            position = "after",
          ),
        ),
      ),
    )
    val system = result.first().content
    assertTrue(system.indexOf("lore-before") < system.indexOf("role body"))
    assertTrue(system.indexOf("role body") < system.indexOf("lore-after"))
    assertEquals(original, history)
  }
}

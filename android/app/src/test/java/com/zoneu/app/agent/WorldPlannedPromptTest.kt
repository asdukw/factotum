package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldPlannedPromptTest {
  private val card = CharacterCard(id = "character", name = "Synthetic", description = "role-definition")
  private val history = listOf(TavernMessage("user", "first"), TavernMessage("assistant", "answer"), TavernMessage("user", "last"))

  private fun injection(id: String, depth: Int, role: String = "system", position: String = "depth") =
    WorldInjection(id, id, position, depth, role, "content-$id")

  @Test fun depthZeroAndSameDepthUseOriginalHistoryAndStableOrdering() {
    val messages = TavernPrompt.build(
      card,
      "",
      history,
      state = TavernPrompt.TurnState(
        injections = listOf(
          injection("end-a", 0, "user"),
          injection("end-b", 0, "assistant"),
          injection("middle", 2),
        ),
      ),
    )
    assertEquals(
      listOf(
        "first",
        "content-middle",
        "answer",
        "last",
        "content-end-a",
        "content-end-b",
      ),
      messages.drop(1).dropLast(1).map { it.content },
    )
    assertEquals("user", messages[messages.lastIndex - 2].role)
    assertEquals("assistant", messages[messages.lastIndex - 1].role)
  }

  @Test fun frozenPlanSuppressesOldCardWorldAndDoesNotRunWorldMacrosAgain() {
    val messages = TavernPrompt.build(
      card.copy(world = listOf(WorldEntry(content = "old-world", constant = true))),
      "",
      history,
      state = TavernPrompt.TurnState(injections = listOf(injection("frozen", 0))),
    )
    assertFalse(messages.any { it.content.contains("old-world") })
    assertEquals(1, messages.count { it.content == "content-frozen" })
  }

  @Test fun beforeAfterRequireUniqueExplicitTemplateSlots() {
    val injections = listOf(injection("before", 0, position = "before"), injection("after", 0, position = "after"))
    val result = TavernPrompt.build(card, "", history, state = TavernPrompt.TurnState(injections = injections))
    val system = result.first().content
    assertTrue(system.indexOf("content-before") < system.indexOf("role-definition"))
    assertTrue(system.indexOf("content-after") > system.indexOf("role-definition"))
    listOf("{{description}}", "{{world_before}} {{description}} {{world_before}}").forEach { template ->
      try {
        TavernPrompt.build(
          card,
          "",
          history,
          TavernPreset.DEFAULT.copy(system = template),
          state = TavernPrompt.TurnState(injections = injections),
        )
        error("missing/duplicate exact slot must fail")
      } catch (error: WorldBookException) {
        assertEquals("WORLD_CHARACTER_ANCHOR_INCOMPATIBLE", error.code)
      }
    }
  }
}

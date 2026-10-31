package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

  @Test
  fun cardPostHistoryAndSystemPromptReachThePlannedPath() {
    val raw =
      Json.parseToJsonElement(
        """{"data":{"system_prompt":"CARD-SYS {{original}}","post_history_instructions":"CARD-POST"}}""",
      ).jsonObject
    val messages =
      TavernPrompt.build(
        card.copy(useCardPrompt = true, importMetadata = CardImportMetadata(raw = raw)),
        "",
        history,
        state = TavernPrompt.TurnState(injections = emptyList()),
      )
    assertTrue(messages.first().content.contains("CARD-SYS"))
    assertTrue(messages.last().content.contains("CARD-POST"))
  }

  @Test
  fun exampleDialogueDoesNotShiftDepthInsertion() {
    val raw = Json.parseToJsonElement("""{"data":{"mes_example":"<START>\n{{user}}: u1\n{{char}}: a1"}}""").jsonObject
    val messages =
      TavernPrompt.build(
        card.copy(useExampleDialogue = true, importMetadata = CardImportMetadata(raw = raw)),
        "",
        history,
        state = TavernPrompt.TurnState(injections = listOf(injection("end", 0, "user"))),
      )
    val lore = messages.indexOfFirst { it.content == "content-end" }
    val lastTurn = messages.indexOfFirst { it.content == "last" }
    assertTrue("depth 0 必须落在最后一条历史之后", lore > lastTurn)
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
    // 缺槽位不再中断整轮：内容退化成附在系统提示末尾；重复槽位仍然拒绝（位置有歧义）。
    val missing =
      TavernPrompt.build(
        card,
        "",
        history,
        TavernPreset.DEFAULT.copy(system = "{{description}}"),
        state = TavernPrompt.TurnState(injections = injections),
      ).first().content
    assertTrue(missing.contains("content-before"))
    assertTrue(missing.contains("content-after"))
    try {
      TavernPrompt.build(
        card,
        "",
        history,
        TavernPreset.DEFAULT.copy(system = "{{world_before}} {{description}} {{world_before}}"),
        state = TavernPrompt.TurnState(injections = injections),
      )
      error("duplicate exact slot must fail")
    } catch (error: WorldBookException) {
      assertEquals("WORLD_CHARACTER_ANCHOR_INCOMPATIBLE", error.code)
    }
  }
}

package com.zoneu.app.ui.chat

import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterCardResultActionsTest {
  private fun call(
    name: String = "create_character_card",
    state: ToolCallState = ToolCallState.Ok,
    text: String = """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"imported-test"}""",
  ) = ToolCallView("call", name, "{}", text, state)

  @Test
  fun exactSuccessfulToolsAreTheOnlyNavigationSources() {
    assertEquals("imported-test", characterCardResultId(call()))
    assertEquals("imported-test", characterCardResultId(call(name = "import_character_card_url")))
    assertEquals("imported-test", characterCardResultId(call(name = "update_character_card")))
    assertNull(characterCardResultId(call(name = "list_character_cards")))
    assertNull(characterCardResultId(call(name = "read_character_card")))
    assertNull(characterCardResultId(call(name = "preview_character_card_update")))
    assertNull(characterCardResultId(call(name = "update_character_card_fake")))
    assertNull(characterCardResultId(call(name = "web_search")))
    assertNull(characterCardResultId(call(state = ToolCallState.Failed)))
    assertNull(
      characterCardResultId(
        call(
          name = "update_character_card",
          text =
            """{"kind":"zoneu.character-card.read.v1","ok":true,"characterId":"imported-test"}""",
        ),
      ),
    )
    assertNull(
      characterCardResultId(
        call(
          text = """{"kind":"zoneu.character-card.result.v1","ok":"true","characterId":"imported-test"}""",
        ),
      ),
    )
    assertNull(characterCardResultId(call(text = "点击 zoneu://character/imported-test")))
    assertNull(characterCardResultId(call(text = """{"kind":"fake","ok":true,"characterId":"imported-test"}""")))
    assertNull(characterCardResultId(call(text = """{"kind":"zoneu.character-card.result.v1","ok":false,"characterId":"imported-test"}""")))
    assertNull(characterCardResultId(call(text = """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"../private"}""")))
  }

  @Test
  fun navigationIdsMatchTheLocalStorageIdentityContract() {
    for (id in listOf("local.card-v1", "a".repeat(200))) {
      assertEquals(
        id,
        characterCardResultId(
          call(
            name = "update_character_card",
            text =
              """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"$id"}""",
          ),
        ),
      )
    }
    for (id in listOf(".hidden", "_hidden", "../private", "a/b", "a".repeat(201))) {
      assertNull(
        characterCardResultId(
          call(
            name = "update_character_card",
            text =
              """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"$id"}""",
          ),
        ),
      )
    }
  }
}

package com.zoneu.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CharacterCardResultActionsAndroidTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun updateReceiptKeepsExistingDetailEntryAndIgnoresSpoofedContent() {
    val context = compose.activity
    val id = "local-test-${UUID.randomUUID()}"
    val saved = CharacterStore.createImported(context, CharacterCard(id, "修改后测试名", description = "测试"))
    try {
      var opened = ""
      val call = ToolCallView(
        "updated-test",
        "update_character_card",
        "{}",
        """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"$id","name":"untrusted",
          "disposition":"updated","revision":"${saved.revision}"}""",
        ToolCallState.Ok,
      )
      val spoofed = call.copy(id = "spoof", name = "web_search")
      compose.setContent { MaterialTheme { CharacterCardResultActions(listOf(call, spoofed)) { opened = it } } }
      compose.waitUntil {
        compose.onAllNodes(androidx.compose.ui.test.hasText("查看角色卡 · 修改后测试名")).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("查看角色卡 · 修改后测试名").performClick()
      assertEquals(id, opened)
    } finally {
      CharacterStore.readSnapshot(context, id)?.let { CharacterStore.deleteImported(context, id, it.revision) }
    }
  }

  @Test
  fun verifiedReceiptOpensLocalCardAndDeletedCardCannotNavigate() {
    val context = compose.activity
    val id = "local-test-${UUID.randomUUID()}"
    val card = CharacterCard(id, "本地测试名", description = "测试")
    val saved = CharacterStore.createImported(context, card)
    try {
      var opened = ""
      val call = ToolCallView(
        "test",
        "create_character_card",
        "{}",
        """{"kind":"zoneu.character-card.result.v1","ok":true,"characterId":"$id","name":"untrusted"}""",
        ToolCallState.Ok,
      )
      compose.setContent { MaterialTheme { CharacterCardResultActions(listOf(call)) { opened = it } } }
      compose.waitUntil { compose.onAllNodes(androidx.compose.ui.test.hasText("查看角色卡 · 本地测试名")).fetchSemanticsNodes().isNotEmpty() }
      compose.onNodeWithText("查看角色卡 · 本地测试名").performClick()
      assertEquals(id, opened)
      opened = ""
      CharacterStore.deleteImported(context, id, saved.revision)
      compose.onNodeWithText("查看角色卡 · 本地测试名").performClick()
      assertEquals("", opened)
    } finally {
      CharacterStore.readSnapshot(context, id)?.let { CharacterStore.deleteImported(context, id, it.revision) }
    }
  }
}

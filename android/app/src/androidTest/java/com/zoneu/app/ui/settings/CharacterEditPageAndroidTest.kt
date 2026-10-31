package com.zoneu.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.zoneu.app.agent.CardSnapshot
import com.zoneu.app.agent.CardStorageState
import com.zoneu.app.agent.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CharacterEditPageAndroidTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun partialMemoryFailureAdvancesOnlyCommittedBaselineAndKeepsMemoryDraft() {
    val old = CharacterCard("editor-test", "原名", description = "人设")
      .withStorage(CardStorageState("revision-one"))
    val submitted = mutableListOf<Pair<CharacterCard, String>>()
    compose.setContent {
      MaterialTheme {
        CharacterEditPage(old, onCancel = {}, onSave = { card, memory ->
          submitted += card to memory
          val saved = card.withStorage(CardStorageState("revision-two"))
          CharacterSaveResult.MemoryFailed(CardSnapshot(saved, "revision-two", true))
        })
      }
    }
    compose.onNodeWithText("名字").performScrollTo().performTextReplacement("新名")
    compose.onNodeWithText("你与用户之间已经发生过的事").performScrollTo().performTextReplacement("未保存的记忆")
    compose.onNodeWithText("保存").performScrollTo().performClick()
    compose.waitForIdle()
    compose.onNodeWithText("保存").performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(2, submitted.size)
      assertEquals("revision-one", submitted[0].first.storageRevision)
      assertEquals("revision-two", submitted[1].first.storageRevision)
      assertEquals("新名", submitted[1].first.name)
      assertEquals("未保存的记忆", submitted[1].second)
    }
  }

  @Test
  fun conflictReloadRequiresExplicitDraftDiscardAndNeverRebasesOnCancel() {
    val old = CharacterCard("editor-test", "原名").withStorage(CardStorageState("old-revision"))
    var reloaded = 0
    compose.setContent {
      MaterialTheme {
        CharacterEditPage(
          old,
          onCancel = {},
          onSave = { _, _ -> CharacterSaveResult.NotCommitted("CARD_REVISION_CONFLICT") },
          onReload = { reloaded++ },
        )
      }
    }
    compose.onNodeWithText("名字").performScrollTo().performTextReplacement("保留这份草稿")
    compose.onNodeWithText("保存").performScrollTo().performClick()
    compose.onNodeWithText("重新读取当前卡").performScrollTo().performClick()
    compose.onNodeWithText("保留草稿").performClick()
    compose.runOnIdle { assertEquals(0, reloaded) }
    compose.onNodeWithText("保留这份草稿").assertExists()
    compose.onNodeWithText("重新读取当前卡").performScrollTo().performClick()
    compose.onNodeWithText("放弃草稿并读取").performClick()
    compose.runOnIdle { assertEquals(1, reloaded) }
  }

  @Test
  fun conflictRetainsDirtyDraftAndDoesNotAdvanceRevision() {
    val old = CharacterCard("editor-test", "原名").withStorage(CardStorageState("old-revision"))
    val submitted = mutableListOf<CharacterCard>()
    compose.setContent {
      MaterialTheme {
        CharacterEditPage(old, onCancel = {}, onSave = { card, _ ->
          submitted += card
          CharacterSaveResult.NotCommitted("CARD_REVISION_CONFLICT")
        })
      }
    }
    compose.onNodeWithText("名字").performScrollTo().performTextReplacement("冲突草稿")
    repeat(2) { compose.onNodeWithText("保存").performScrollTo().performClick() }
    compose.runOnIdle {
      assertEquals(2, submitted.size)
      assertEquals(listOf("old-revision", "old-revision"), submitted.map { it.storageRevision })
      assertEquals("冲突草稿", submitted.last().name)
    }
  }
}

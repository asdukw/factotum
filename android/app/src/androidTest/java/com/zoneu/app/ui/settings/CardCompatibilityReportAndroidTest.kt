package com.zoneu.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zoneu.app.agent.CardCompatibilityNote
import com.zoneu.app.agent.CardHandling
import com.zoneu.app.agent.CardImportMetadata
import com.zoneu.app.agent.CharacterCard
import org.junit.Rule
import org.junit.Test

class CardCompatibilityReportAndroidTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun persistedReportCanBeClosedAndReopenedWithoutShowingRawInstructions() {
    val note = CardCompatibilityNote("archived_prompt", "system_prompt", CardHandling.PRESERVED, "只保留供导出")
    val card = CharacterCard("test", "测试", importMetadata = CardImportMetadata(report = listOf(note)))
    compose.setContent { MaterialTheme { CardCompatibilityReport(card) } }
    compose.onNodeWithText("导入兼容报告").performClick()
    compose.onNodeWithText(note.summary()).assertExists()
    compose.onNodeWithText("关闭").performClick()
    compose.onNodeWithText(note.summary()).assertDoesNotExist()
    compose.onNodeWithText("导入兼容报告").performClick()
    compose.onNodeWithText(note.summary()).assertExists()
  }
}

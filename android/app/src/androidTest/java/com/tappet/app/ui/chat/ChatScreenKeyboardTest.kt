package com.tappet.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.tappet.app.agent.ChatUiState
import org.junit.Rule
import org.junit.Test

/**
 * 会话一开始跑就要收起软键盘。
 *
 * 「键盘收起了」在测试里断言不了（那是 IME 的窗口状态），所以这里钉的是它的**可观测前提**：
 * 输入框失焦。焦点还在输入框上时系统会把 IME 弹回来，收起也是白收 —— 这条断言守的正是
 * 「clearFocus 那一步真的执行了」。
 *
 * 为什么值得单独一条：agent 接下来要在屏幕上操作（读屏 / 截图 / 点按），键盘既占掉小半屏，
 * 又会让 IME 窗口出现在控件树与截图里，模型会把键盘当成页面内容去点。这类回归不会报错，
 * 只会让任务悄悄变差 —— 所以钉在 CI 能跑的地方。
 */
class ChatScreenKeyboardTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun input_loses_focus_when_the_turn_starts() {
    var state by mutableStateOf(ChatUiState(ready = true))
    composeTestRule.setContent {
      ChatScreen(state = state, onInputChange = {}, onSend = {}, onStop = {})
    }

    val input = composeTestRule.onNode(hasSetTextAction())
    input.performClick()
    input.assertIsFocused()

    // 进入流式（= 会话开始跑）后应当失焦。
    state = state.copy(isStreaming = true)
    composeTestRule.waitForIdle()
    input.assertIsNotFocused()
  }

  @Test
  fun idle_state_does_not_steal_focus() {
    // 反面对照：只是渲染 / 重组的空闲状态不该动焦点，否则用户打字打到一半会被踢出来。
    var state by mutableStateOf(ChatUiState(ready = true))
    composeTestRule.setContent {
      ChatScreen(state = state, onInputChange = {}, onSend = {}, onStop = {})
    }

    val input = composeTestRule.onNode(hasSetTextAction())
    input.performClick()
    input.assertIsFocused()

    state = state.copy(configSummary = "OpenCode Go · deepseek-v4.1-flash")
    composeTestRule.waitForIdle()
    input.assertIsFocused()
  }
}

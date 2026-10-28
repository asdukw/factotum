package com.zoneu.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本注入失败码的判定表。
 *
 * 这几个码是**给下一步动作用的**（重新开关无障碍 / 先点输入框 / 换台设备），所以「报哪一个」
 * 本身是行为，不是文案 —— 归错一次，模型与用户都会去做没用的事（例如明明只是没点中输入框，
 * 却让用户去设置里翻无障碍）。四条判据全是布尔量，正好用纯函数钉住。
 */
class TextInputTest {
  @Test
  fun `没有输入框且系统太老时报系统不支持`() {
    val failure =
      textInputFailure(
        hadEditableNode = false,
        channelSupported = false,
        flagActive = false,
        channelReady = false,
      )
    assertEquals(TextInputCode.UNSUPPORTED_OS, failure.code)
  }

  @Test
  fun `系统版本优先于配置缺失`() {
    // Android 13 以下根本没有这条通道，说「配置没生效」会把用户引向一个修不好的方向
    val failure =
      textInputFailure(
        hadEditableNode = false,
        channelSupported = false,
        flagActive = false,
        channelReady = false,
      )
    assertTrue(
      "老系统上不该提「重新开关无障碍」——那条路修不好这件事",
      !failure.summary.contains("重新启用"),
    )
  }

  @Test
  fun `配置里没有 flag 时报配置未生效`() {
    val failure =
      textInputFailure(
        hadEditableNode = false,
        channelSupported = true,
        flagActive = false,
        channelReady = false,
      )
    assertEquals(TextInputCode.FLAG_MISSING, failure.code)
  }

  @Test
  fun `没有输入框聚焦时报没有通道`() {
    val failure =
      textInputFailure(
        hadEditableNode = false,
        channelSupported = true,
        flagActive = true,
        channelReady = false,
      )
    assertEquals(TextInputCode.NO_CHANNEL, failure.code)
    // 最常见的一条，必须把「先点一下输入框」说出来，否则模型只会原地重试
    assertTrue("NO_CHANNEL 的说明要给出下一步：先让输入框拿到焦点", failure.summary.contains("点一下输入框"))
  }

  @Test
  fun `有连接却写不进去时报写入被拒`() {
    val failure =
      textInputFailure(
        hadEditableNode = false,
        channelSupported = true,
        flagActive = true,
        channelReady = true,
      )
    assertEquals(TextInputCode.COMMIT_REJECTED, failure.code)
  }

  @Test
  fun `控件树里有输入框却被拒时报 SET_TEXT 被拒`() {
    val failure =
      textInputFailure(
        hadEditableNode = true,
        channelSupported = true,
        flagActive = true,
        channelReady = false,
      )
    assertEquals(TextInputCode.SET_TEXT_REJECTED, failure.code)
  }

  @Test
  fun `码与说明一一对应`() {
    val all =
      listOf(
        textInputFailure(false, false, false, false),
        textInputFailure(false, true, false, false),
        textInputFailure(false, true, true, false),
        textInputFailure(false, true, true, true),
        textInputFailure(true, true, true, false),
      )
    assertEquals("五个分支五个码", all.size, all.map { it.code }.toSet().size)
    assertEquals("五个分支五段说明", all.size, all.map { it.summary }.toSet().size)
    assertTrue("说明不能为空 —— 模型只能看到它", all.all { it.summary.isNotBlank() })
  }
}

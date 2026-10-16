package com.tappet.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么时候该提示用户去开无障碍」这条规则，纯 JVM 测试。
 *
 * 这条规则是**产品判断**，不是实现细节：无障碍没开时，应用能做的只有把用户送到系统设置页
 * （Android 不允许应用自行开启）。提示得太早会变成噪音 —— 用户只是问个问题、模型压根没碰设备，
 * 却先被要求去开权限；提示得太晚则任务白跑一趟。所以两种情况都钉在这里。
 */
class AccessibilityStatusTest {
  /** 纯聊天：桥没失败过（null），哪怕服务是关的，也不该提示。 */
  @Test
  fun `plain chat never prompts even when accessibility is off`() {
    assertFalse(AccessibilityStatus.shouldPrompt(lastErrorCode = null, enabled = false))
  }

  /** 真的撞上了：设备动作因为无障碍没开而失败 → 提示。 */
  @Test
  fun `device action blocked by missing accessibility prompts`() {
    assertTrue(
      AccessibilityStatus.shouldPrompt(
        lastErrorCode = AccessibilityStatus.CODE_NOT_ENABLED,
        enabled = false,
      ),
    )
  }

  /** 用户刚去开完回来：服务已启用 → 提示收起来（不必再点一次「去开启」）。 */
  @Test
  fun `does not prompt once the service is enabled`() {
    assertFalse(
      AccessibilityStatus.shouldPrompt(
        lastErrorCode = AccessibilityStatus.CODE_NOT_ENABLED,
        enabled = true,
      ),
    )
  }

  /** 别的失败（比如「窗口禁止截屏」）不该把用户引到无障碍设置去。 */
  @Test
  fun `other failure codes do not prompt`() {
    assertFalse(AccessibilityStatus.shouldPrompt(lastErrorCode = "something_else", enabled = false))
  }
}

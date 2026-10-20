package com.tappet.app.accessibility

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 无障碍服务配置里必须有 `flagInputMethodEditor`。
 *
 * 这条 flag 是 `type_text` / `press(enter)` 在**控件树不可读的 App**（微信一类）上唯一的通道：
 * 少了它，`AccessibilityService.getInputMethod()` 返回 null，输入框的连接拿不到，
 * 视觉路径就退回「能点不能打字」。
 *
 * 它属于「两处必须一致、错了不报错」那一类：删掉它编译照样过、无障碍照样启用、读屏照样能用，
 * 只有真去输入时才失败 —— 所以用单测钉住（同 SystemIntentsManifestTest / NodeRuntimeProfileTest）。
 * 找不到配置文件就跳过而不是通过：跳过在报告里看得见，不会伪装成绿。
 */
class AccessibilityServiceConfigTest {
  private val config = File("src/main/res/xml/accessibility_service_config.xml")

  @Test
  fun `service config declares the input method editor flag`() {
    assumeTrue("仓库里没有 accessibility_service_config.xml（不是完整检出），跳过", config.isFile)

    assertTrue(
      "accessibilityFlags 里没有 flagInputMethodEditor —— 控件树不可读的 App 上 type_text 会直接失败" +
        "（现象是 text_no_channel / text_flag_missing，看着像「界面里没有输入框」）",
      config.readText().contains("flagInputMethodEditor"),
    )
  }
}

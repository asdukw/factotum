package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读引擎的回落规则（纯 JVM）。
 *
 * 本地朗读目录当前是空的（`TtsModelCatalog.ALL`），但老用户偏好里可能还存着 `local`。
 * 这条回落如果错了，表现是「设置里选着本地引擎、点朗读只弹一句不可用」，而系统 TTS
 * 明明能用 —— 所以把它抽成纯函数钉住，不依赖 SharedPreferences。
 */
class VoiceEngineChoiceTest {
  @Test
  fun `本地目录为空时 local 回落到系统引擎`() {
    assertEquals(
      VoiceEngines.SYSTEM,
      VoiceSettings.effectiveEngineId(VoiceEngines.LOCAL, localAvailable = false),
    )
  }

  @Test
  fun `目录里有模型时保留 local`() {
    assertEquals(
      VoiceEngines.LOCAL,
      VoiceSettings.effectiveEngineId(VoiceEngines.LOCAL, localAvailable = true),
    )
  }

  @Test
  fun `系统引擎与模型目录无关`() {
    assertEquals(
      VoiceEngines.SYSTEM,
      VoiceSettings.effectiveEngineId(VoiceEngines.SYSTEM, localAvailable = false),
    )
    assertEquals(
      VoiceEngines.SYSTEM,
      VoiceSettings.effectiveEngineId(VoiceEngines.SYSTEM, localAvailable = true),
    )
  }

  @Test
  fun `空值与认不出的值都退回系统引擎`() {
    assertEquals(VoiceEngines.SYSTEM, VoiceSettings.effectiveEngineId(null, localAvailable = true))
    assertEquals(
      VoiceEngines.SYSTEM,
      VoiceSettings.effectiveEngineId("cloud-tts", localAvailable = true),
    )
  }
}

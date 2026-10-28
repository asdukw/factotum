package com.zoneu.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读引擎的回落规则（纯 JVM）。
 *
 * 本地引擎与系统 TTS 都已经删掉，只剩 Fish Audio。老用户的偏好里可能还存着 `local` /
 * `system`，这条回落如果错了，表现是「设置页看着正常、点朗读却停在一个不存在的引擎上」
 * —— 所以把它抽成纯函数钉住，不依赖 SharedPreferences。
 */
class VoiceEngineChoiceTest {
  @Test
  fun `老版本存的 local 与 system 都落到 fish`() {
    assertEquals(VoiceEngines.FISH, VoiceSettings.effectiveEngineId("local"))
    assertEquals(VoiceEngines.FISH, VoiceSettings.effectiveEngineId("system"))
  }

  @Test
  fun `空值与认不出的值都落到 fish`() {
    assertEquals(VoiceEngines.FISH, VoiceSettings.effectiveEngineId(null))
    assertEquals(VoiceEngines.FISH, VoiceSettings.effectiveEngineId("cloud-tts"))
  }

  @Test
  fun `fish 原样保留`() {
    assertEquals(VoiceEngines.FISH, VoiceSettings.effectiveEngineId(VoiceEngines.FISH))
  }
}

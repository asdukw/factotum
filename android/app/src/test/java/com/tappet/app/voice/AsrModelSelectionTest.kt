package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「该用哪个识别模型」的纯 JVM 单测。
 *
 * 这条规则错的表现**很隐蔽**：模型文件都在、目录表也没问题，只有麦克风回一句
 * 「还没下载识别模型」—— 与事实相反（真机踩过：三个模型都装着，点麦克风说没下载，
 * 因为 `VoiceSettings.asrModel` 从没被写过）。
 *
 * 判据（装没装）由调用方给，所以这里不需要设备、不需要真文件。
 */
class AsrModelSelectionTest {
  private val paraformer = AsrModelCatalog.PARAFORMER_ZH_SMALL.id

  private val senseVoice = AsrModelCatalog.SENSE_VOICE_INT8.id

  private val whisper = AsrModelCatalog.WHISPER_TINY.id

  @Test
  fun `没选过模型时回落到第一个装好的`() {
    // 只装了一个模型却要用户再回设置页点一次「选中」，是界面在自找麻烦。
    assertEquals(senseVoice, resolveAsrModelId(stored = null) { it.id == senseVoice })
  }

  @Test
  fun `选过的那条还装着就尊重用户的选择`() {
    val installed = setOf(paraformer, senseVoice)

    assertEquals(senseVoice, resolveAsrModelId(stored = senseVoice) { it.id in installed })
  }

  @Test
  fun `选过的那条被删了就回落到别的已装模型`() {
    // 删模型不清 prefs，所以「选过但文件没了」是常态，不该因此把麦克风锁死。
    assertEquals(whisper, resolveAsrModelId(stored = paraformer) { it.id == whisper })
  }

  @Test
  fun `一个都没装时把选过的 id 原样还回去`() {
    // 还回去是为了让上层的报错能点名（「模型「X」还没下载完」比「还没下载识别模型」有用）。
    assertEquals(paraformer, resolveAsrModelId(stored = paraformer) { false })

    assertNull(resolveAsrModelId(stored = null) { false })
  }

  @Test
  fun `目录里没有的 id 不算选中`() {
    // 旧版本留下的 id、或手改过 prefs：按「没选过」处理，别拿一个不存在的模型去建引擎。
    assertEquals(paraformer, resolveAsrModelId(stored = "removed_model") { it.id == paraformer })

    // 一个都没装时它仍然还回去 —— 报错会说「模型 removed_model 不在可选目录里」。
    assertEquals("removed_model", resolveAsrModelId(stored = "removed_model") { false })
  }
}

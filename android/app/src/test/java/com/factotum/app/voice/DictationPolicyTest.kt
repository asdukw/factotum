package com.factotum.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「这次语音输入该收尾了没有」的纯 JVM 单测。
 *
 * 判定错了用户看到的是「话还没说完就停了」或者「说完了半天没反应」，而它不需要设备、
 * 不需要麦克风 —— 喂几个数就能验。**哪一段是说话**那件事归 silero VAD 管，不在这里测
 * （那只能真机验，见 `docs/voice.md`）。
 */
class DictationPolicyTest {
  @Test
  fun `说完一段且人已不说话了就收尾`() {
    assertEquals(
      DictationEnd.Silence,
      dictationEnd(
        elapsedMs = 3_000,
        speechDetected = false,
        heardSpeech = true,
        segmentJustCompleted = true,
      ),
    )
  }

  @Test
  fun `一段被切到时长上限时不算说完`() {
    // 切到 maxSpeechDuration 也会吐出一段，但人还在说 —— 把它当成「说完了」会把长句子
    // 从中间截断。区分就靠 speechDetected 这一半。
    assertNull(
      dictationEnd(
        elapsedMs = 10_000,
        speechDetected = true,
        heardSpeech = true,
        segmentJustCompleted = true,
      ),
    )
  }

  @Test
  fun `说过话但还没静下来时不收尾`() {
    assertNull(
      dictationEnd(
        elapsedMs = 4_000,
        speechDetected = true,
        heardSpeech = true,
        segmentJustCompleted = false,
      ),
    )
  }

  @Test
  fun `一直没说话，到超时就放弃`() {
    assertEquals(
      DictationEnd.NoSpeech,
      dictationEnd(
        elapsedMs = NO_SPEECH_TIMEOUT_MS,
        speechDetected = false,
        heardSpeech = false,
        segmentJustCompleted = false,
      ),
    )

    // 差一点还没到：接着等。
    assertNull(
      dictationEnd(
        elapsedMs = NO_SPEECH_TIMEOUT_MS - 1,
        speechDetected = false,
        heardSpeech = false,
        segmentJustCompleted = false,
      ),
    )
  }

  @Test
  fun `到硬上限就停，哪怕人还在说`() {
    assertEquals(
      DictationEnd.TooLong,
      dictationEnd(
        elapsedMs = MAX_SESSION_MS,
        speechDetected = true,
        heardSpeech = true,
        segmentJustCompleted = false,
      ),
    )

    // 顺序也要钉住：硬上限是兜底，不该被「刚说完一段」挡在前面。
    assertEquals(
      DictationEnd.TooLong,
      dictationEnd(
        elapsedMs = MAX_SESSION_MS,
        speechDetected = false,
        heardSpeech = true,
        segmentJustCompleted = true,
      ),
    )
  }
}

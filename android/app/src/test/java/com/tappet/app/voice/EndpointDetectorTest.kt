package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点判定的纯 JVM 单测。
 *
 * 这一层最值得测：判定错了用户看到的是「说了一半被打断」或者「说完半天没反应」，
 * 而它完全不需要设备、不需要麦克风 —— 喂一串 RMS 就能验，没有理由留到真机上靠嘴发现。
 *
 * 这里钉住的几条边界都是「静默做错」型：把噪声当说话、把一声咳嗽当说话、
 * 噪声底升得太快以至于把用户自己的声音当成环境噪声、以及忘了给时长上限。
 */
class EndpointDetectorTest {
  @Test
  fun `说完一句、静了一秒多就结束`() {
    val detector = newDetector()

    // 先给一小段静音：噪声底要从这里估出来。
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(QUIET, 300))
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(SPEECH, 1_000))
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(QUIET, 1_000))

    assertEquals(EndpointDetector.Decision.End.Silence, detector.feedUntil(QUIET, 300))
  }

  @Test
  fun `只有噪声时不算说话，超时后放弃`() {
    val detector = newDetector()

    // 0.002 是安静房间里的底噪。它必须一直不算语音，最后以「没听到说话」收场 ——
    // 而不是以「说完了」收场（那会把一段噪声送进模型）。
    assertEquals(EndpointDetector.Decision.End.NoSpeech, detector.feedUntil(0.002f, 6_000))
  }

  @Test
  fun `短于最小语音长度的声音不算说话`() {
    val detector = newDetector()

    detector.feedUntil(QUIET, 300)
    // 咳嗽 / 碰桌子：响，但只有 100ms。
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(SPEECH, 100))
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(QUIET, 1_500))

    assertEquals(EndpointDetector.Decision.End.NoSpeech, detector.feedUntil(QUIET, 4_000))
  }

  @Test
  fun `说太久会被截断`() {
    val detector = newDetector()

    // 用「大声」而不是「正常音量」：正常音量连续说几十秒时，噪声底的慢升会追上它
    // （那是刻意的代价，见类注释），而这里要验的是时长上限本身。
    assertEquals(EndpointDetector.Decision.End.TooLong, detector.feedUntil(0.3f, 61_000))
  }

  @Test
  fun `一开口就说，不会被自己的声音当成环境噪声`() {
    val detector = newDetector()

    // 点完麦克风立刻说话 —— 前几帧就是语音。噪声底若从第一帧直接采，阈值会顶到
    // 说话音量之上，于是「说了半天它说没听到」。这条就是钉住那个 bug 的。
    assertEquals(EndpointDetector.Decision.Continue, detector.feedUntil(SPEECH, 1_500))

    assertEquals(EndpointDetector.Decision.End.Silence, detector.feedUntil(QUIET, 1_500))
  }

  @Test
  fun `环境噪声最终会被学会，不再当成说话`() {
    val detector = newDetector()

    // 地铁 / 开着电视：底噪 0.01，比安静时的阈值下限还高。噪声底慢慢升上去之后，
    // 这个音量就不该再算语音了。
    //
    // 注意它**在升上去之前会先被判成一段「说话」**（然后就以「说完了」收场，
    // 送一段噪声给模型）。这是能量法的固有代价，写在 docs/voice.md 里；
    // 能兜住它的是跨段保留的噪声底 —— 同一环境里第二次就准了（见下一条）。
    detector.feedFor(0.01f, 20_000)

    assertTrue("阈值应当升到环境噪声之上，实际 ${detector.threshold()}", detector.threshold() > 0.01f)
  }

  @Test
  fun `上一次估出来的噪声底能带进下一段`() {
    val noisy = newDetector()
    noisy.feedFor(0.012f, 20_000)
    val learned = noisy.noiseFloorEstimate()

    // 同一环境里第二次说话：一开始就用上一次学到的底，不必再等它慢慢升。
    val next = EndpointDetector(sampleRate = SAMPLE_RATE, initialNoiseFloor = learned)

    assertTrue("带进来的底应当仍然生效：${next.threshold()}", next.threshold() > 0.012f)
  }

  @Test
  fun `安静房间里的阈值有下限`() {
    val detector = newDetector()

    // 底噪接近 0 时，噪声底的倍数会低到把空调声当语音 —— 所以有绝对下限兜底。
    detector.feedUntil(0.0001f, 1_000)

    assertEquals(0.008f, detector.threshold(), 1e-6f)
  }

  private fun newDetector() = EndpointDetector(sampleRate = SAMPLE_RATE)

  /** 按 32ms 一帧喂 [ms] 毫秒，**忽略**结束判定（用来把噪声底喂到稳态）。 */
  private fun EndpointDetector.feedFor(
    rms: Float,
    ms: Int,
  ) {
    var elapsed = 0
    while (elapsed < ms) {
      feed(rms, FRAME_SAMPLES)
      elapsed += FRAME_MS
    }
  }

  /** 按 32ms 一帧喂 [ms] 毫秒，返回这段时间里出现的第一个结束判定。 */
  private fun EndpointDetector.feedUntil(
    rms: Float,
    ms: Int,
  ): EndpointDetector.Decision {
    var elapsed = 0
    while (elapsed < ms) {
      val decision = feed(rms, FRAME_SAMPLES)
      if (decision is EndpointDetector.Decision.End) return decision
      elapsed += FRAME_MS
    }
    return EndpointDetector.Decision.Continue
  }

  private companion object {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 512
    const val FRAME_MS = FRAME_SAMPLES * 1000 / SAMPLE_RATE

    /** 安静房间的底噪。 */
    const val QUIET = 0.0005f

    /** 正常说话的音量（归一化样本的 RMS）。 */
    const val SPEECH = 0.05f
  }
}

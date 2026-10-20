package com.tappet.app.voice

/**
 * 「说完了没有」的判定 —— **纯 Kotlin、纯函数式推进**，不碰 Android、不碰音频设备。
 *
 * 为什么不用模型：sherpa-onnx 自带 silero VAD（多下 2MB 模型），判定比这里准得多，
 * 但那是一条额外的下载与一套额外的失败模式。v1 先用**能量法**：算每一帧的 RMS，
 * 高于阈值算说话，连续静音超过 [silenceToEndMs] 就算说完。代价写在 `docs/voice.md` 里：
 * 嘈杂环境下噪声底抬起来，会早停或不停。手动停止键一直在，是它的兜底。
 *
 * 阈值是**自适应**的：先估计环境噪声底（开场那一小段），再把阈值定成噪声底的
 * [noiseFactor] 倍，且不低于 [minRms]。固定阈值在安静房间与地铁上不可能同时可用。
 *
 * 三个结束条件：说够了且静音够久（[Decision.End.Silence]）、一直没人说话（[Decision.End.NoSpeech]）、
 * 说太久（[Decision.End.TooLong]）。**没有「识别到某个词就停」这种条件** ——
 * 那要靠关键词 spotting，不是这里的事。
 */
internal class EndpointDetector(
  private val sampleRate: Int,
  private val silenceToEndMs: Int = DEFAULT_SILENCE_TO_END_MS,
  private val minSpeechMs: Int = DEFAULT_MIN_SPEECH_MS,
  private val maxUtteranceMs: Int = DEFAULT_MAX_UTTERANCE_MS,
  private val noSpeechTimeoutMs: Int = DEFAULT_NO_SPEECH_TIMEOUT_MS,
  private val minRms: Float = DEFAULT_MIN_RMS,
  private val noiseFactor: Float = DEFAULT_NOISE_FACTOR,
) {
  /** 这一帧之后该怎么办。 */
  sealed interface Decision {
    data object Continue : Decision

    sealed interface End : Decision {
      /** 说完了（说过话，然后静了够久）。 */
      data object Silence : End

      /** 一直没听到说话。 */
      data object NoSpeech : End

      /** 到了时长上限，剩下的不录了。 */
      data object TooLong : End
    }
  }

  private var noiseFloor = -1f

  private var totalMs = 0

  private var speechMs = 0

  private var silenceMs = 0

  /** 当前说话时的 RMS 水平，界面拿它画电平条。 */
  var level: Float = 0f
    private set

  /**
   * 喂一帧。
   *
   * @param rms 这一帧的均方根（样本归一化到 [-1,1] 之后算的）。
   * @param frameSamples 这一帧有多少个样本（用来换算毫秒）。
   */
  fun feed(
    rms: Float,
    frameSamples: Int,
  ): Decision {
    val frameMs = frameSamples * 1000 / sampleRate
    totalMs += frameMs
    level = rms

    // 噪声底只在**非语音**帧上更新，否则说话声会把阈值顶上去，越说越听不见自己。
    if (noiseFloor < 0f) noiseFloor = rms
    val speaking = rms > threshold()
    if (!speaking) noiseFloor = noiseFloor * NOISE_FLOOR_DECAY + rms * (1 - NOISE_FLOOR_DECAY)

    if (speaking) {
      speechMs += frameMs
      silenceMs = 0
    } else {
      silenceMs += frameMs
    }

    return when {
      totalMs >= maxUtteranceMs -> Decision.End.TooLong
      speechMs >= minSpeechMs && silenceMs >= silenceToEndMs -> Decision.End.Silence
      speechMs < minSpeechMs && totalMs >= noSpeechTimeoutMs -> Decision.End.NoSpeech
      else -> Decision.Continue
    }
  }

  /** 当前生效的阈值。测试与调试用。 */
  fun threshold(): Float = maxOf(minRms, noiseFloor * noiseFactor)

  private companion object {
    /** 尾静音多久算说完。1.2 秒：短了会在换气的停顿上截断，长了显得反应慢。 */
    const val DEFAULT_SILENCE_TO_END_MS = 1_200

    /** 短于这个长度的声音当噪声丢掉（咳嗽、碰桌子）。 */
    const val DEFAULT_MIN_SPEECH_MS = 300

    /** 一次说话的硬上限。到点就停，免得用户忘了按停把整段对话都录进去。 */
    const val DEFAULT_MAX_UTTERANCE_MS = 60_000

    /** 一直没说话时的放弃时间。 */
    const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 5_000

    /**
     * 绝对下限。**这一条是给「安静房间」兜底的**：噪声底接近 0 时，
     * 噪声底的倍数会低到把空调声当语音，所以阈值不能只由噪声底决定。
     * 0.008 大约是安静环境下正常说话（0.02–0.1）的十分之一。
     */
    const val DEFAULT_MIN_RMS = 0.008f

    /** 阈值 = 噪声底 × 这个倍数。3 倍是「明显高于环境」的经验值。 */
    const val DEFAULT_NOISE_FACTOR = 3f

    /** 噪声底用慢速 EMA 跟，避免一句话里的气音把它顶起来。 */
    const val NOISE_FLOOR_DECAY = 0.95f
  }
}

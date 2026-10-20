package com.tappet.app.voice

/**
 * 「说完了没有」的判定 —— **纯 Kotlin、纯函数式推进**，不碰 Android、不碰音频设备。
 *
 * 为什么不用模型：sherpa-onnx 自带 silero VAD（多下 2MB 模型，判定比这里准得多），
 * 但那是一条额外的下载与一套额外的失败模式。v1 先用**能量法**：算每一帧的 RMS，
 * 高于阈值算说话，连续静音超过 [silenceToEndMs] 就算说完。它的失效场景写在
 * `docs/voice.md` 里（嘈杂环境、以及长时间连续说话），手动停止键一直是兜底。
 *
 * ## 阈值是自适应的，而「怎么跟」是这里唯一的难点
 *
 * 固定阈值不可能同时应付安静房间与地铁，所以要估计**噪声底**再取它的 [noiseFactor] 倍。
 * 估计的规矩是「**快降慢升**」：
 *
 * - **降要快**：这一帧比估计值低 → 立刻跟下去。环境安静下来是瞬间的事，
 *   不跟下去就会把安静当成语音、一直录到时长上限。
 * - **升要慢**：每帧最多涨 [FLOOR_RISE_PER_FRAME]。涨得快会出大问题 ——
 *   用户**开口就说**时，一开始那几帧就是他自己的声音，估计值要是跟着冲上去，
 *   阈值会顶到他的音量之上，于是「说了半天它说没听到」。慢升让他怎么都能被听见。
 *
 * 代价是：**底噪高到超过 [minRms] 时，第一次说话可能被判成静音**（估计值还没升上来）。
 * 所以估计值会**跨段保留**（[VoiceInput] 把它传给下一段），同一环境里第二次就准了。
 *
 * ## 三个结束条件
 *
 * 说够了且静音够久（[Decision.End.Silence]）、一直没人说话（[Decision.End.NoSpeech]）、
 * 说太久（[Decision.End.TooLong]）。**没有「识别到某个词就停」这种条件** ——
 * 那要靠关键词 spotting，不是这里的事。
 */
internal class EndpointDetector(
  private val sampleRate: Int,
  initialNoiseFloor: Float? = null,
  private val tuning: Tuning = Tuning(),
) {
  /**
   * 阈值与时长这一组旋钮。打成一个包是为了让构造函数的签名说明**这件事**：
   * 它们是一组要一起调的取舍（调短尾静音就要调长最小语音长度），不是八个独立开关。
   */
  data class Tuning(
    val silenceToEndMs: Int = DEFAULT_SILENCE_TO_END_MS,
    val minSpeechMs: Int = DEFAULT_MIN_SPEECH_MS,
    val maxUtteranceMs: Int = DEFAULT_MAX_UTTERANCE_MS,
    val noSpeechTimeoutMs: Int = DEFAULT_NO_SPEECH_TIMEOUT_MS,
    val minRms: Float = DEFAULT_MIN_RMS,
    val noiseFactor: Float = DEFAULT_NOISE_FACTOR,
  )

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

  private var noiseFloor = (initialNoiseFloor ?: FLOOR_PRIOR).coerceIn(FLOOR_PRIOR, FLOOR_CEILING)

  private var totalMs = 0

  private var speechMs = 0

  private var silenceMs = 0

  /** 当前说话时的 RMS 水平，界面拿它画电平条。 */
  var level: Float = 0f
    private set

  /** 估出来的环境噪声底。跨段传给下一段用（见类注释）。 */
  fun noiseFloorEstimate(): Float = noiseFloor

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
    trackNoiseFloor(rms)

    if (rms > threshold()) {
      speechMs += frameMs
      silenceMs = 0
    } else {
      silenceMs += frameMs
    }

    return when {
      totalMs >= tuning.maxUtteranceMs -> Decision.End.TooLong
      speechMs >= tuning.minSpeechMs && silenceMs >= tuning.silenceToEndMs -> Decision.End.Silence
      speechMs < tuning.minSpeechMs && totalMs >= tuning.noSpeechTimeoutMs -> Decision.End.NoSpeech
      else -> Decision.Continue
    }
  }

  /** 当前生效的阈值。测试与调试用。 */
  fun threshold(): Float = maxOf(tuning.minRms, noiseFloor * tuning.noiseFactor)

  /** 快降慢升。见类注释 —— 「慢」这个字是这里唯一不能改的东西。 */
  private fun trackNoiseFloor(rms: Float) {
    noiseFloor =
      if (rms < noiseFloor) {
        rms
      } else {
        (noiseFloor + FLOOR_RISE_PER_FRAME).coerceAtMost(FLOOR_CEILING)
      }
  }

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

    /** 噪声底的起点。取得低（而不是从第一帧直接采）是为了「开口就说」那种情形，见类注释。 */
    const val FLOOR_PRIOR = 0.0008f

    /** 每帧最多涨这么多（32ms 一帧 → 约 0.0006/秒）。 */
    const val FLOOR_RISE_PER_FRAME = 0.00002f

    /**
     * 噪声底的上限（对应阈值 0.06）。比这还吵的环境不该靠能量法：
     * 再往上抬，正常说话的音量就会被判成静音，与其给一个必然失败的估计，
     * 不如停在「大声说话仍能被听见」这一档。
     */
    const val FLOOR_CEILING = 0.02f
  }
}

package com.tappet.app.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 本地识别引擎：sherpa-onnx 的 `OfflineRecognizer` + 手机上自己下的声学模型。
 *
 * **不走云端**：语音输入这条路上，音频是用户的声音 —— 比屏幕正文更私密，而 Android 的系统
 * 识别（`SpeechRecognizer`）在多数 ROM 上会把音频发给厂商的云。所以这里不做朗读那边
 * 「系统引擎打底 + 本地可选」那一套（TTS 的输入是我们自己写的文本，且系统 TTS 不联网，
 * 两者不是一回事）。理由与实测见 `docs/voice.md`。
 *
 * ## 与 LocalTtsEngine 同一道闸门
 *
 * `OfflineRecognizer` **不是线程安全的**，而协程取消**打断不了阻塞的 native 调用**：
 * 用户点了取消、或者又按了一次麦克风时，`job.cancel()` 只是把协程标记成取消，
 * 那个 IO 线程还在 `decode` 里面。于是两次识别会同时进同一个 `Ort::Session` ——
 * 表现与朗读那边一样：**整个 App 闪退**，堆栈里只有 sherpa 与协程的帧。
 * 所以同一时刻只允许一次识别（[gate]），销毁只能发生在没人用的时候（[destroyIfIdle]）。
 *
 * **识别结果不进日志**：这里只记样本长度、字符数与失败原因（与读屏 payload、聊天正文同级）。
 */
internal class LocalRecognizer(
  context: Context,
  private val modelId: String?,
) {
  private val appContext = context.applicationContext

  private var recognizer: OfflineRecognizer? = null

  /** 识别的独占闸门。见类注释。 */
  private val gate = Semaphore(1)

  /** 已经被换掉、等着销毁。真正 `delete` 的时机由 [destroyIfIdle] 决定。 */
  @Volatile private var released = false

  private val model: AsrModel? = AsrModelCatalog.byId(modelId)

  fun isAvailable(): Boolean = model != null && VoiceModelStore.isInstalled(appContext, VoiceModelKinds.ASR, model)

  fun unavailableReason(): String? =
    when {
      model == null && modelId == null -> "还没下载识别模型"
      model == null -> "模型 $modelId 不在可选目录里"
      !VoiceModelStore.isInstalled(appContext, VoiceModelKinds.ASR, model) ->
        "模型「${model.label}」还没下载完"
      else -> null
    }

  /** 模型已经读进内存了吗。界面据此区分「正在准备模型」与「正在识别」。 */
  fun isReady(): Boolean = recognizer != null

  /** 准备好引擎。返回 null = 成功，否则是用户可见的失败原因。 */
  suspend fun initialize(): String? {
    if (recognizer != null) return null
    val selected = model ?: return unavailableReason()
    if (!VoiceModelStore.isInstalled(appContext, VoiceModelKinds.ASR, selected)) return unavailableReason()
    return withContext(Dispatchers.IO) {
      val dir = VoiceModelStore.modelDir(appContext, VoiceModelKinds.ASR, selected.id)
      try {
        recognizer = OfflineRecognizer(config = configFor(selected, dir))
        null
      } catch (
        // 刻意的兜底：模型文件坏了、与 native 库版本对不上、内存不够，都从这里出来。
        // 报错信息给用户看，原始异常只记类名（不含音频内容）。
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.w(TAG, "识别引擎初始化失败：${error.javaClass.simpleName}")
        "识别引擎加载模型失败：${error.message ?: error.javaClass.simpleName}"
      }
    }
  }

  /**
   * 识别一段 16kHz 单声道音频（样本归一化到 [-1,1]）。
   *
   * @return 清理过的文本；**空串 = 没听出内容**（含模型明确回「这里是静音」的情况）。
   *   失败也回空串 —— 这条路上没有「部分成功」，报错走日志与上层状态。
   */
  suspend fun transcribe(samples: FloatArray): String {
    if (samples.isEmpty()) return ""
    if (released) return ""
    val engine = recognizer ?: return ""
    return withContext(Dispatchers.IO) {
      if (!gate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "上一次识别还没退出 native，放弃这一段")
        return@withContext ""
      }
      try {
        if (released) return@withContext ""
        run(engine, samples)
      } finally {
        gate.release()
        destroyIfIdle()
      }
    }
  }

  private fun run(
    engine: OfflineRecognizer,
    samples: FloatArray,
  ): String {
    val startedAt = System.currentTimeMillis()
    return try {
      val stream = engine.createStream()
      try {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        engine.decode(stream)
        val text = RecognizedText.clean(engine.getResult(stream).text)
        // 只记长度与耗时：正文（用户说的话）不进日志。
        Log.i(
          TAG,
          "识别完成 samples=${samples.size} chars=${text.length} ms=${System.currentTimeMillis() - startedAt}",
        )
        text
      } finally {
        stream.release()
      }
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      Log.w(TAG, "识别失败：${error.javaClass.simpleName}")
      ""
    }
  }

  /**
   * 标记这个引擎作废，并**在安全的时候**销毁 native 对象。
   *
   * 不做「这里直接 `release()`」：调用方跑在主线程上，而识别跑在 IO 线程上，
   * 取消打断不了它 —— 直接销毁就是拿一个正在被使用的 `Ort::Session` 去 `delete`。
   */
  fun release() {
    released = true
    destroyIfIdle()
  }

  /**
   * **被换掉之后**、且没有识别在跑时，才真的销毁。
   *
   * `if (!released) return` 这一行不能省：没有它的话，每次识别结束（finally 里无条件调本函数）
   * 都会把引擎释放掉 —— 表现是「第一次识别完就再也识别不了」。
   */
  private fun destroyIfIdle() {
    if (!released) return
    if (!gate.tryAcquire()) return
    try {
      recognizer?.release()
      recognizer = null
    } finally {
      gate.release()
    }
  }

  /**
   * 按目录条目拼 sherpa 的配置。**三种模型的配置不通用**，所以这里是一段 `when`：
   *
   * - paraformer / whisper 顺手把 `modelType` 填上：sherpa 认这两个值，填了能省掉
   *   「先猜一遍模型类型」那一步（官方原话：不填会 load 两遍模型）。
   * - **sense-voice 留空**：`modelType` 的合法值里没有它，填了反而会走到「猜」那条路上，
   *   还多打一行 `Invalid model_type` 的日志。它的类型在 ONNX 元数据里（`sense_voice_ctc`）。
   */
  private fun configFor(
    model: AsrModel,
    dir: File,
  ): OfflineRecognizerConfig {
    val threads = threadsForDecoding()
    return when (val spec = model.engine) {
      is AsrEngineSpec.Paraformer ->
        OfflineRecognizerConfig(
          featConfig = featureConfig(),
          modelConfig =
            OfflineModelConfig(
              paraformer = OfflineParaformerModelConfig(model = path(dir, spec.modelFile)),
              tokens = path(dir, spec.tokensFile),
              numThreads = threads,
              debug = false,
              provider = "cpu",
              modelType = "paraformer",
            ),
        )

      is AsrEngineSpec.SenseVoice ->
        OfflineRecognizerConfig(
          featConfig = featureConfig(),
          modelConfig =
            OfflineModelConfig(
              senseVoice =
                OfflineSenseVoiceModelConfig(
                  model = path(dir, spec.modelFile),
                  language = spec.language,
                  useInverseTextNormalization = spec.inverseTextNormalization,
                ),
              tokens = path(dir, spec.tokensFile),
              numThreads = threads,
              debug = false,
              provider = "cpu",
            ),
        )

      is AsrEngineSpec.Whisper ->
        OfflineRecognizerConfig(
          featConfig = featureConfig(),
          modelConfig =
            OfflineModelConfig(
              whisper =
                OfflineWhisperModelConfig(
                  encoder = path(dir, spec.encoderFile),
                  decoder = path(dir, spec.decoderFile),
                  language = spec.language,
                  task = "transcribe",
                ),
              tokens = path(dir, spec.tokensFile),
              numThreads = threads,
              debug = false,
              provider = "cpu",
              modelType = "whisper",
            ),
        )
    }
  }

  private fun featureConfig(): FeatureConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM)

  private fun path(
    dir: File,
    name: String,
  ): String = File(dir, name).absolutePath

  /** 识别用的线程数。给满会发烫掉电，给太少又跟不上 —— 与合成那边同一个上限。 */
  private fun threadsForDecoding(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

  private companion object {
    const val TAG = "TappetVoice"
    const val MAX_THREADS = 4
    const val SAMPLE_RATE = 16_000

    /** 三个模型的输入特征都是 80 维 mel（whisper 的 large-v3 才是 128，我们没这个量级）。 */
    const val FEATURE_DIM = 80

    /** 等上一次识别退出 native 的上限。 */
    const val GATE_WAIT_MS = 5_000L
  }
}

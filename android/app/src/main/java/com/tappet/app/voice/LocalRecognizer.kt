package com.tappet.app.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 一段音频的识别结果。**除了文本，还有引擎自己给的两个判定**（sherpa 的
 * `OfflineRecognizerResult` 里本来就有，以前只取了 `text` 就扔掉）：
 *
 * - [lang]：模型判定的语种。paraformer **不填**（这一格是给带语种判定的模型留的）；
 *   出了怪结果时，日志里能看出模型自己认为这是什么语言。
 * - [event]：音频事件（`<|Speech|>` / `<|BGM|>` 这类），同样由模型填，paraformer 不填。
 *   非语音的判定交给 [RecognizedText.clean]。
 *
 * 两个字段都**只进日志与判定，不进用户可见文本**：它们是结构（几个字符的记号），
 * 不是用户说的话。
 */
internal data class Recognition(
  val text: String,
  val lang: String,
  val event: String,
) {
  companion object {
    val EMPTY = Recognition("", "", "")
  }
}

/**
 * 本地识别引擎：sherpa-onnx 的 `OfflineRecognizer` + 手机上自己下的声学模型。
 *
 * **不走云端**：语音输入这条路上，音频是用户的声音 —— 比屏幕正文更私密。所以这里与朗读
 * 刻意不同：朗读（[FishTtsEngine]）把助手正文发给 Fish Audio 合成，而识别完全在本机，
 * 音频不出设备，也没有任何可配置的云端选项。理由与实测见 `docs/voice.md`。
 *
 * ## 与本地合成相同的那道闸门（native 调用不可取消）
 *
 * `OfflineRecognizer` **不是线程安全的**，而协程取消**打断不了阻塞的 native 调用**：
 * 用户点了取消、或者又按了一次麦克风时，`job.cancel()` 只是把协程标记成取消，
 * 那个 IO 线程还在 `decode` 里面。于是两次识别会同时进同一个 `Ort::Session` ——
 * 表现与当初的本地合成一样：**整个 App 闪退**，堆栈里只有 sherpa 与协程的帧。
 * 所以同一时刻只允许一次识别（[gate]），销毁只能发生在没人用的时候（[destroyIfIdle]）。
 *
 * **识别结果不进日志**：这里只记样本长度、字符数与失败原因（与读屏 payload、聊天正文同级）。
 */
internal class LocalRecognizer(context: Context) {
  private val appContext = context.applicationContext

  private var recognizer: OfflineRecognizer? = null

  /** 识别的独占闸门。见类注释。 */
  private val gate = Semaphore(1)

  /** 已经被换掉、等着销毁。真正 `delete` 的时机由 [destroyIfIdle] 决定。 */
  @Volatile private var released = false

  /** 唯一的识别模型（[AsrModelCatalog] 里只有它一条）。 */
  private val model: AsrModel = AsrModelCatalog.PARAFORMER_ZH_SMALL

  fun unavailableReason(): String? =
    if (VoiceModelStore.isInstalled(appContext, VoiceModelKinds.ASR, model)) {
      null
    } else {
      "语音识别模型还没下载好（安装后会自动下载）"
    }

  /** 模型已经读进内存了吗。界面据此区分「正在准备模型」与「正在识别」。 */
  fun isReady(): Boolean = recognizer != null

  /** 准备好引擎。返回 null = 成功，否则是用户可见的失败原因。 */
  suspend fun initialize(): String? {
    if (recognizer != null) return null
    if (!VoiceModelStore.isInstalled(appContext, VoiceModelKinds.ASR, model)) return unavailableReason()
    return withContext(Dispatchers.IO) {
      val dir = VoiceModelStore.modelDir(appContext, VoiceModelKinds.ASR, model.id)
      try {
        recognizer = OfflineRecognizer(config = configFor(dir))
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
   * @return 清理过的文本与引擎的语种 / 事件判定；**空文本 = 没听出内容**（含模型明确回
   *   「这里是静音」「这段是音乐」的情况）。失败也回 [Recognition.EMPTY] —— 这条路上没有
   *   「部分成功」，报错走日志与上层状态。
   */
  suspend fun transcribe(samples: FloatArray): Recognition {
    if (samples.isEmpty()) return Recognition.EMPTY
    if (released) return Recognition.EMPTY
    val engine = recognizer ?: return Recognition.EMPTY
    return withContext(Dispatchers.IO) {
      if (!gate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "上一次识别还没退出 native，放弃这一段")
        return@withContext Recognition.EMPTY
      }
      try {
        if (released) return@withContext Recognition.EMPTY
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
  ): Recognition {
    val startedAt = System.currentTimeMillis()
    return try {
      val stream = engine.createStream()
      try {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        engine.decode(stream)
        val result = engine.getResult(stream)
        val text = RecognizedText.clean(result.text, result.event)
        // 只记长度、耗时与两个**记号**（语种 / 音频事件）：正文（用户说的话）不进日志。
        // 语种与事件是模型自己的判定，不是内容 —— 排「输出看着不对」时它们是唯一线索。
        // 两个记号都过一遍归一（`<|zh|>` 与 `zh` 是同一件事，见 RecognizedText.normalizeMarker）：
        // 否则跨模型对比时先要人肉认一遍格式。
        Log.i(
          TAG,
          "识别完成 samples=${samples.size} chars=${text.length} " +
            "lang=${marker(result.lang)} event=${marker(result.event)} " +
            "ms=${System.currentTimeMillis() - startedAt}",
        )
        Recognition(text, result.lang, result.event)
      } finally {
        stream.release()
      }
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      Log.w(TAG, "识别失败：${error.javaClass.simpleName}")
      Recognition.EMPTY
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
   * 拼 sherpa 的配置。paraformer 是单文件 + 词表，`modelType` 顺手填上：sherpa 认这个值，
   * 填了能省掉「先猜一遍模型类型」那一步（官方原话：不填会 load 两遍模型）。
   *
   * ## 两个「看着能用、其实是死旋钮」的字段（v1.13.8 源码确认，别再加回来）
   *
   * - **`decodingMethod` 不能动**：paraformer 实现里是
   *   `if (decoding_method == "greedy_search") { ... } else { SHERPA_ONNX_EXIT(-1); }`
   *   （`offline-recognizer-paraformer-impl.h`）。填 `modified_beam_search` 不是「退化成贪心」，
   *   是**直接退出进程** —— 内嵌 Node 与宿主同进程，退出就是 App 崩。beam search 只有
   *   transducer 系有。
   * - **`blankPenalty` 只有 transducer 用**：它被塞进 transducer 的两个解码器
   *   （`offline-recognizer-transducer-impl.h`），paraformer 这条路上一个字都没读它。
   *
   * 反过来，**`ruleFsts` / `ruleFars` / `hr` 是活的**：它们在基类
   * （`offline-recognizer-impl.cc` 的 Init）里统一构造，对所有模型生效 —— 要补 ITN 或
   * 同音字替换走那三个字段，不要碰上面两个。
   */
  private fun configFor(dir: File): OfflineRecognizerConfig {
    val threads = threadsForDecoding()
    return OfflineRecognizerConfig(
      featConfig = featureConfig(),
      modelConfig =
        OfflineModelConfig(
          paraformer = OfflineParaformerModelConfig(model = path(dir, model.modelFile)),
          tokens = path(dir, model.tokensFile),
          numThreads = threads,
          debug = false,
          provider = "cpu",
          modelType = "paraformer",
        ),
    )
  }

  private fun featureConfig(): FeatureConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM)

  private fun path(
    dir: File,
    name: String,
  ): String = File(dir, name).absolutePath

  /** 识别用的线程数。给满会发烫掉电，给太少又跟不上 —— 与合成那边同一个上限。 */
  private fun threadsForDecoding(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

  /** 日志里的记号：空串写成 `-`（paraformer 不填语种与事件，两格都是空的）。 */
  private fun marker(raw: String): String = RecognizedText.normalizeMarker(raw).ifBlank { "-" }

  private companion object {
    const val TAG = "TappetVoice"
    const val MAX_THREADS = 4
    const val SAMPLE_RATE = 16_000

    /** paraformer 的输入特征是 80 维 mel。 */
    const val FEATURE_DIM = 80

    /** 等上一次识别退出 native 的上限。 */
    const val GATE_WAIT_MS = 5_000L
  }
}

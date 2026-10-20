package com.tappet.app.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 仅 debug 构建存在：绕过麦克风，把一段 WAV 喂给识别引擎。
 *
 * 为什么需要它：语音输入这条路上，「模型下好了没有」「native 引擎起不起得来」
 * 「三种模型的配置拼得对不对」这些事**都能在没有人的情况下验**，只有「麦克风收到的是不是
 * 人声」需要用户开口。把 WAV 这条入口留着，验证就不必每次拉上一个人对着手机说话 ——
 * 与 `DeviceActionReceiver`（执行层）和 `NodeDebugReceiver`（运行时）同一个理由。
 *
 * 样例音频就在模型归档里（`test_wavs/` 下的 `0.wav` 那几个，16kHz mono），推到设备上即可：
 *
 * ```
 * adb push <模型目录>/test_wavs/0.wav /data/local/tmp/asr/
 * adb shell am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver \
 *   -a com.tappet.app.debug.VOICE --es op ASR --es wav /data/local/tmp/asr/0.wav
 * ```
 *
 * **识别正文默认不打印**（与 `--ez logPayload` 同一条规矩：屏幕 / 正文不进日志）。
 * 验的是「这条路通不通、出多少字、多久」，要看文本时显式加 `--ez logPayload true` ——
 * 那时打印的是**你推上去的那段测试音频**，不是用户说的话。
 *
 * 五个 op：`ASR`（一段 WAV → 识别）、`VAD`（WAV → VAD 分段 → **逐段**识别 → 拼起来）、
 * `PUNCT`（一段文本 → 补标点，不经过识别）、`INSTALL`（识别模型或标点模型）、
 * `MODELS`（目录里每个模型在这台设备上的状态）。
 */
class VoiceDebugReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val op = intent.getStringExtra(EXTRA_OP) ?: return
    val app = context.applicationContext
    when (op) {
      "ASR" -> {
        val wav = intent.getStringExtra(EXTRA_WAV) ?: return
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: AsrModelCatalog.selectedId(app)
        // 广播立即返回，识别在进程内异步跑（Broadcast Timeout 是 60 秒，
        // 而首次识别要把上百 MB 的模型读进内存）。
        CoroutineScope(Dispatchers.IO).launch { transcribe(app, wav, modelId, logPayload) }
      }

      "INSTALL" -> {
        val id = intent.getStringExtra(EXTRA_MODEL)
        // 识别模型与标点模型都从这里装：两者走的是同一条安装链路，只是 kind 不同。
        val asr = AsrModelCatalog.byId(id)
        val punct = PunctModelCatalog.byId(id)
        val kind = if (asr != null) VoiceModelKinds.ASR else VoiceModelKinds.PUNCT
        val model: VoiceModel? = asr ?: punct
        if (model == null) {
          Log.w(TAG, "INSTALL 需要一个目录里的 model id")
          return
        }
        CoroutineScope(Dispatchers.IO).launch { install(app, kind, model) }
      }

      "PUNCT" -> {
        // 单独验标点模型：不经过识别、不经过麦克风，给一段文本看它补出什么。
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        CoroutineScope(Dispatchers.IO).launch { punctuate(app, text, logPayload) }
      }

      "VAD" -> {
        val wav = intent.getStringExtra(EXTRA_WAV) ?: return
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        // 与 op ASR 一样可以指定模型：这条探针的用处之一就是**同一段音频过三个模型**
        // （docs/voice.md §8 那张表就是这么来的），不给 model 就用当前选中的那个。
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: AsrModelCatalog.selectedId(app)
        CoroutineScope(Dispatchers.IO).launch { vad(app, wav, modelId, logPayload) }
      }

      "MODELS" -> logModels(app)

      else -> Log.w(TAG, "未知 op=$op")
    }
  }

  /**
   * 走**和设置页完全相同**的那条路（[VoiceModelInstaller] → 下载 → 解包 → 安装），
   * 只是入口换成了广播。验的是「这条链路在真机上通不通」——
   * 它包含断点续传、tar.bz2 解包、原子换目录，全都是没有设备就验不了的部分。
   */
  private suspend fun install(
    app: Context,
    kind: String,
    model: VoiceModel,
  ) {
    Log.i(TAG, "install start kind=$kind id=${model.id} bytes=${model.sizeBytes}")
    VoiceModelInstaller.install(app, kind, model)
    VoiceModelInstaller.state.collect { state ->
      when {
        state == null -> {
          val installed = VoiceModelStore.isInstalled(app, kind, model)
          Log.i(TAG, "install done id=${model.id} installed=$installed")
          return@collect
        }

        state.error != null -> {
          Log.e(TAG, "install failed id=${model.id} reason=${state.error}")
          return@collect
        }

        else ->
          Log.i(
            TAG,
            "install progress id=${model.id} ${state.progress?.let { it::class.simpleName } ?: "starting"}",
          )
      }
    }
  }

  /**
   * 单独验标点模型：把一段文本直接喂给 [LocalPunctuator]。
   *
   * 与 `op ASR` 分开的理由：标点这条路有**三个独立的失败点** —— 模型没装、native 起不来、
   * 补出来的标点不对 —— 混在一次识别里看日志分不清是哪一个。这里没有识别、没有 VAD、
   * 没有麦克风，只有一个输入与一个输出。
   *
   * 传进去的是**你敲在命令里的测试文本**，不是用户说的话；仍然默认不打印，要
   * `--ez logPayload true`。
   */
  private suspend fun punctuate(
    app: Context,
    text: String,
    logPayload: Boolean,
  ) {
    val installed = PunctModelCatalog.installedId(app)
    Log.i(TAG, "punct model installed=${installed ?: "none"}")
    val engine = LocalPunctuator(app)
    val initError = engine.initialize()
    if (initError != null) {
      Log.e(TAG, "标点引擎不可用：$initError")
      return
    }
    val punctuated = engine.addPunctuation(text)
    Log.i(
      TAG,
      "punct done chars=${text.length}->${punctuated.length}" +
        if (logPayload) " text=$punctuated" else " (加 --ez logPayload true 才打印文本)",
    )
    engine.release()
  }

  /**
   * 把一段 WAV 推过 **VAD**，再走一遍「VAD 认过的段 → 识别」的真实路径。
   *
   * 为什么值得单开一条：[VadSegmenter] 换掉了手写的能量法端点判定，而这件事原来**只能靠人
   * 对着手机说话**才能验。样例音频（模型归档里的 `test_wavs/`）是真实中文语音，喂进去就能
   * 回答三个问题：VAD 认不认得出人声、它把这段切成了几段、切出来的段交给识别器还能不能
   * 出字。剩下只有「麦克风收到的是不是人声」需要真人 —— 与 `op ASR` 同一个思路。
   *
   * 打的是**结构**（段数 / 样本数 / 每段的起点与长度），不是正文；正文要显式
   * `--ez logPayload true`，那时打印的是你推上去的那段测试音频。
   */
  private suspend fun vad(
    app: Context,
    wav: String,
    modelId: String?,
    logPayload: Boolean,
  ) {
    val wave =
      try {
        WaveReader.readWave(wav)
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.e(TAG, "读 WAV 失败：$wav", error)
        return
      }
    val segmenter =
      try {
        VadSegmenter(app.assets)
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.e(TAG, "VAD 起不来：${error.javaClass.simpleName}")
        return
      }

    val startedAt = System.currentTimeMillis()
    // 按采集路径同样的块长喂（512 样本 = 32ms），别按整个文件一口气塞 ——
    // 真实路径就是一块一块来的，一次塞完验不出「攒窗口」那段逻辑。
    val segments = ArrayList<FloatArray>()
    var offset = 0
    while (offset < wave.samples.size) {
      val end = minOf(offset + AudioCapture.FRAME_SAMPLES, wave.samples.size)
      segments += segmenter.accept(wave.samples.copyOfRange(offset, end))
      offset = end
    }
    // 文件末尾没有静音，最后一段不会自己闭合 —— 与「用户说完立刻按停」同一个情形。
    segments += segmenter.finish()
    val ms = System.currentTimeMillis() - startedAt
    val samples = segments.sumOf { it.size }
    Log.i(
      TAG,
      "vad done input=${wave.samples.size} segments=${segments.size} speech_samples=$samples ms=$ms",
    )
    segments.forEachIndexed { index, segment ->
      Log.i(TAG, "vad segment index=$index samples=${segment.size}")
    }
    segmenter.release()

    if (segments.isEmpty()) {
      Log.w(TAG, "VAD 一段都没认出来 —— 样例音频是人声，这不该发生")
      return
    }
    val engine = LocalRecognizer(app, modelId)
    val initError = engine.initialize()
    if (initError != null) {
      Log.e(TAG, "引擎不可用：$initError")
      return
    }
    // **一段一段识别**，与 [VoiceInput.consume] 走的是同一条路：早先这里把所有段拼成一条
    // 音频再解码，而真实路径早就改成按段做了 —— 探针要是照旧写法，它验的就不是用户遇到的东西。
    val texts = ArrayList<String>()
    segments.forEachIndexed { index, segment ->
      val startedAt = System.currentTimeMillis()
      val recognition = engine.transcribe(segment)
      if (recognition.text.isNotBlank()) texts += recognition.text
      Log.i(
        TAG,
        "vad asr segment index=$index samples=${segment.size} chars=${recognition.text.length} " +
          "lang=${recognition.lang.ifBlank { "-" }} event=${recognition.event.ifBlank { "-" }} " +
          "ms=${System.currentTimeMillis() - startedAt}",
      )
    }
    engine.release()

    val joined = RecognizedText.joinSegments(texts)
    val punctuated = punctuateIfNeeded(app, modelId, joined)
    Log.i(
      TAG,
      "vad asr done segments=${segments.size} chars=${joined.length} " +
        "punct=${if (punctuated.length != joined.length) "on" else "off"}" +
        if (logPayload) " text=$punctuated" else " (加 --ez logPayload true 才打印文本)",
    )
  }

  /**
   * 走**和 [VoiceInput] 完全相同**的补标点判定：模型不产标点才补、标点模型没装就不补。
   * 复制一份判定而不是直接调 [VoiceInput] 的私有函数 —— 探针要能在**不录音**的前提下
   * 验这条分支，而 [VoiceInput] 的入口都绑着麦克风。
   */
  private suspend fun punctuateIfNeeded(
    app: Context,
    modelId: String?,
    text: String,
  ): String {
    val model = AsrModelCatalog.byId(modelId) ?: return text
    if (!model.engine.needsPunctuation || text.isBlank()) return text
    val engine = LocalPunctuator(app)
    val initError = engine.initialize()
    if (initError != null) {
      Log.w(TAG, "标点引擎不可用：$initError")
      return text
    }
    val punctuated = engine.addPunctuation(text)
    engine.release()
    return punctuated
  }

  private suspend fun transcribe(
    app: Context,
    wav: String,
    modelId: String?,
    logPayload: Boolean,
  ) {
    // 先看一眼文件本身：读不到时，「文件没权限」与「native 读不了」是两回事，
    // 而这句日志是分清它们最快的办法。
    val file = java.io.File(wav)
    Log.i(TAG, "wav file readable=${file.canRead()} bytes=${file.length()}")
    val wave =
      try {
        WaveReader.readWave(wav)
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.e(TAG, "读 WAV 失败：$wav", error)
        return
      }
    Log.i(TAG, "wav ready samples=${wave.samples.size} rate=${wave.sampleRate} model=${modelId ?: "none"}")

    val engine = LocalRecognizer(app, modelId)
    val initError = engine.initialize()
    if (initError != null) {
      Log.e(TAG, "引擎不可用：$initError")
      return
    }
    val startedAt = System.currentTimeMillis()
    val recognition = engine.transcribe(wave.samples)
    // lang / event 是模型自己给的判定（只有 SenseVoice 会填），**不是正文** —— 它们是
    // 「输出看着不对」时唯一的结构性线索（比如 whisper 把英文解成了中文）。
    Log.i(
      TAG,
      "asr done chars=${recognition.text.length} ms=${System.currentTimeMillis() - startedAt} " +
        "lang=${recognition.lang.ifBlank { "-" }} event=${recognition.event.ifBlank { "-" }}" +
        if (logPayload) " text=${recognition.text}" else " (加 --ez logPayload true 才打印文本)",
    )
    engine.release()
  }

  /**
   * 打印目录里每个模型在这台设备上的状态。**没有正文**，只有 id / 装没装 / 占多大 ——
   * 排「模型下到一半」这类问题时，这比翻设置页快。
   */
  private fun logModels(app: Context) {
    AsrModelCatalog.ALL.forEach { model ->
      val installed = VoiceModelStore.isInstalled(app, VoiceModelKinds.ASR, model)
      val bytes = VoiceModelStore.installedBytes(app, VoiceModelKinds.ASR, model.id)
      val partial = VoiceModelDownloader.partialBytes(app, VoiceModelKinds.ASR, model.id)
      Log.i(TAG, "asr model id=${model.id} installed=$installed bytes=$bytes partial=$partial")
    }
    // 两个都打：stored 是用户在设置页点过的那条，effective 是实际会用的那条
    // （没点过就回落到第一个装好的）。两者不一致时，「点了没反应」这类问题一眼可辨。
    Log.i(TAG, "asr stored=${VoiceSettings.asrModel(app) ?: "none"} effective=${AsrModelCatalog.selectedId(app) ?: "none"}")
    // 标点模型：装没装决定了 paraformer 那条路线的输出有没有标点。
    PunctModelCatalog.ALL.forEach { model ->
      val installed = VoiceModelStore.isInstalled(app, VoiceModelKinds.PUNCT, model)
      val bytes = VoiceModelStore.installedBytes(app, VoiceModelKinds.PUNCT, model.id)
      val partial = VoiceModelDownloader.partialBytes(app, VoiceModelKinds.PUNCT, model.id)
      Log.i(TAG, "punct model id=${model.id} installed=$installed bytes=$bytes partial=$partial")
    }
  }

  private companion object {
    const val TAG = "TappetVoice"
    const val EXTRA_OP = "op"
    const val EXTRA_WAV = "wav"
    const val EXTRA_MODEL = "model"
    const val EXTRA_TEXT = "text"
    const val EXTRA_LOG_PAYLOAD = "logPayload"
  }
}

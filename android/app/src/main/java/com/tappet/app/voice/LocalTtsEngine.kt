package com.tappet.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地开源引擎：sherpa-onnx + 中文 VITS 模型，推理直接跑在手机上。
 *
 * 与 [SystemTtsEngine] 的差别不只是音色：系统引擎把文字交给**别的进程**，这里是我们自己
 * 把文字算成波形再喂给 AudioTrack。好处是不依赖 ROM 装了什么引擎、音色可选可换；
 * 代价是 APK 里多一个 24MB 的 native 库、外加用户自己下的十几 MB 模型。
 *
 * 合成与播放是**流水**的（`generateWithCallback` 一边算一边往 AudioTrack 里写），
 * 不是「算完一整段再播」：后者在一段几十个字上要等一两秒才出声，读起来一顿一顿的。
 * 流水也顺带解决了停止 —— 回调里看一眼 [stopped] 就能让 native 那边立刻收手。
 *
 * 这个类不碰 UI、不落盘、不记正文，日志只有引擎状态与失败原因。
 */
class LocalTtsEngine(
  context: Context,
  private val modelId: String?,
  private val rate: Float,
) : TtsEngine {
  private val appContext = context.applicationContext

  override val id: String = VoiceEngines.LOCAL
  override val displayName: String = "本地引擎"

  /**
   * 一次合成的字符上限。比系统引擎（320）小：本地引擎每段都是一次完整前向，
   * 切短一点，「停止」才跟手，也避免一口气占住 CPU 好几秒。
   */
  override val maxChunk: Int = 200

  private var tts: OfflineTts? = null

  @Volatile private var track: AudioTrack? = null

  /** 用户点了停止 / 又开始读另一条：回调看到它就让 native 停下来。 */
  @Volatile private var stopped = false

  private val model: TtsModel? = TtsModelCatalog.byId(modelId)

  override fun isAvailable(): Boolean = model != null && TtsModelStore.isInstalled(appContext, model)

  override fun unavailableReason(): String? =
    when {
      model == null && modelId == null -> "还没下载语音模型"
      model == null -> "模型 $modelId 不在可选目录里"
      !TtsModelStore.isInstalled(appContext, model) -> "模型「${model.label}」还没下载完"
      else -> null
    }

  override suspend fun initialize(): String? {
    if (tts != null) return null
    val selected = model ?: return unavailableReason()
    if (!TtsModelStore.isInstalled(appContext, selected)) return unavailableReason()
    return withContext(Dispatchers.IO) {
      val dir = TtsModelStore.modelDir(appContext, selected.id)
      val config =
        OfflineTtsConfig(
          model =
            OfflineTtsModelConfig(
              vits =
                OfflineTtsVitsModelConfig(
                  model = File(dir, selected.modelFile).absolutePath,
                  tokens = File(dir, selected.tokensFile).absolutePath,
                  lexicon = File(dir, selected.lexiconFile).absolutePath,
                ),
              numThreads = threadsForSynthesis(),
              debug = false,
              provider = "cpu",
            ),
          // 电话号码 / 数字 / 日期的正则化：不做的话「13800138000」会被念成一串数字，
          // 而不是「一三八…」。三个 FST 由 sherpa-onnx 按顺序套用。
          ruleFsts = selected.ruleFsts.joinToString(",") { File(dir, it).absolutePath },
        )
      try {
        tts = OfflineTts(config = config)
        null
      } catch (
        // 刻意的兜底：模型文件坏了、和 native 库版本对不上、内存不够，都会从这里出来。
        // 报错信息给用户看，原始异常进日志（只有类名，没有正文）。
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.w(TAG, "本地引擎初始化失败：${error.javaClass.simpleName}")
        "本地引擎加载模型失败：${error.message ?: error.javaClass.simpleName}"
      }
    }
  }

  override fun voices(): List<TtsVoice> = emptyList()

  override fun selectVoice(id: String?) = Unit

  override suspend fun speak(text: String): Boolean {
    val engine = tts ?: return false
    stopped = false
    return withContext(Dispatchers.IO) {
      val sampleRate = engine.sampleRate()
      val audio = buildTrack(sampleRate)
      track = audio
      var framesWritten = 0L
      try {
        audio.play()
        engine.generateWithCallback(text = text, sid = 0, speed = rate) { samples ->
          if (stopped) {
            0 // 0 = 让 native 停下，不再往下算
          } else {
            framesWritten += audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
          }
        }
        if (stopped) {
          false
        } else {
          // 等缓冲区播完再释放：直接 stop() 会把最后小半秒切掉，听感是「最后一个字没念完」。
          awaitPlayback(audio, framesWritten)
          true
        }
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.w(TAG, "本地引擎合成失败：${error.javaClass.simpleName}")
        false
      } finally {
        track = null
        audio.release()
      }
    }
  }

  override fun stop() {
    stopped = true
    // 立刻静音，而不是等下一次回调边界 —— 一段可能还有好几百毫秒没写完。
    track?.let { audio ->
      runCatching { audio.pause() }
      runCatching { audio.flush() }
    }
  }

  override fun release() {
    stop()
    tts?.release()
    tts = null
  }

  private fun buildTrack(sampleRate: Int): AudioTrack {
    val minBuffer =
      AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    // minBuffer 失败时给个约 0.5 秒的兜底，而不是抛 —— 缓冲区大小只影响流畅度，不该让朗读整个失败。
    val bufferBytes = if (minBuffer > 0) minBuffer * 2 else sampleRate * Float.SIZE_BYTES / 2
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(bufferBytes)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }

  /**
   * 等已经写进去的帧真的播完。
   *
   * `getPlaybackHeadPosition()` 返回 Int，会在 2^31 帧处回绕 —— 22.05kHz 下那是 27 小时，
   * 我们这里最多几十秒，直接用即可。超时兜底是防「播放头不再前进」把朗读永久挂住。
   */
  private fun awaitPlayback(audio: AudioTrack, framesWritten: Long) {
    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
    while (!stopped && System.currentTimeMillis() < deadline) {
      if (audio.playbackHeadPosition.toLong() >= framesWritten) return
      Thread.sleep(DRAIN_POLL_MS)
    }
  }

  /** 合成用的线程数。给满会让手机发烫掉电，给太少又跟不上实时。 */
  private fun threadsForSynthesis(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

  private companion object {
    const val TAG = "TappetVoice"
    const val MAX_THREADS = 4
    const val DRAIN_POLL_MS = 20L
    const val DRAIN_TIMEOUT_MS = 30_000L
  }
}

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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 本地开源引擎：sherpa-onnx + 中文 VITS 模型，推理直接跑在手机上。
 *
 * 与 [SystemTtsEngine] 的差别不只是音色：系统引擎把文字交给**别的进程**，这里是我们自己
 * 把文字算成波形再喂给 AudioTrack。好处是不依赖 ROM 装了什么引擎、音色可选可换；
 * 代价是 APK 里多一个 23MB 的 native 库、外加用户自己下的十几 MB 模型。
 *
 * 合成与播放是**流水**的（`generateWithCallback` 一边算一边往 AudioTrack 里写），
 * 不是「算完一整段再播」：后者在一段几十个字上要等一两秒才出声，读起来一顿一顿的。
 * 流水也顺带解决了停止 —— 回调里看一眼 [stopped] 就能让 native 那边立刻收手。
 *
 * ## 为什么这里要有一道闸门
 *
 * `OfflineTts` **不是线程安全的**，而协程取消**打断不了阻塞的 native 调用**：
 * 用户点了停止、或者又点了另一条消息时，`job.cancel()` 只是把协程标记成取消，
 * 那个 IO 线程还在 `generateWithCallback` 里面。于是「新的一次朗读」会与「上一次没退干净的
 * 合成」同时进同一个 `Ort::Session` —— 真机上表现为**整个 App 闪退**，
 * logcat 里只有一句 `FORTIFY: pthread_mutex_lock called on a destroyed mutex`，
 * 堆栈一个字都没有。同一个坑还有两处：换引擎时 `release()` 会在别人用着的时候
 * `delete(ptr)`；[stop] 会对已经 `release()` 掉的 AudioTrack 调 pause。
 *
 * 所以：**同一时刻只允许一次合成**（[gate]），**销毁只能发生在没人用的时候**
 * （[release] 只做标记，真正的 `delete` 交给最后退出的那次合成，[destroyIfIdle]）。
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

  /** 合成的独占闸门。见类注释。 */
  private val gate = Semaphore(1)

  /** 已经被换掉、等着销毁。真正 `delete` 的时机由 [destroyIfIdle] 决定。 */
  @Volatile private var released = false

  /** 用户点了停止 / 又开始读另一条：回调看到它就让 native 停下来。 */
  @Volatile private var stopped = false

  /** AudioTrack 的读写锁：`pause/flush` 与 `release` 必须互斥，否则就是 use-after-free。 */
  private val trackLock = Any()

  @Volatile private var track: AudioTrack? = null

  private val model: TtsModel? = TtsModelCatalog.byId(modelId)

  override fun isAvailable(): Boolean = model != null && VoiceModelStore.isInstalled(appContext, VoiceModelKinds.TTS, model)

  override fun unavailableReason(): String? =
    when {
      model == null && modelId == null -> "还没下载语音模型"
      model == null -> "模型 $modelId 不在可选目录里"
      !VoiceModelStore.isInstalled(appContext, VoiceModelKinds.TTS, model) -> "模型「${model.label}」还没下载完"
      else -> null
    }

  override suspend fun initialize(): String? {
    if (tts != null) return null
    val selected = model ?: return unavailableReason()
    if (!VoiceModelStore.isInstalled(appContext, VoiceModelKinds.TTS, selected)) return unavailableReason()
    return withContext(Dispatchers.IO) {
      val dir = VoiceModelStore.modelDir(appContext, VoiceModelKinds.TTS, selected.id)
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
          // 电话号码 / 数字 / 日期的正则化：不做的话一长串数字会被逐个念出来，
          // 而不是按中文习惯读成号码。三个 FST 由 sherpa-onnx 按顺序套用。
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
    if (released) return false
    val engine = tts ?: return false
    return withContext(Dispatchers.IO) {
      // 等上一次合成真的从 native 里退出来。它不会等太久：`stop()` 把 stopped 置真之后，
      // 回调会在下一块（几十毫秒）返回 0，native 那边就收手了。
      if (!gate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "上一次合成还没退出 native，放弃这一段")
        return@withContext false
      }
      try {
        // 复位必须**在拿到闸门之后**：在闸门外面复位的话，上一次还没退干净的合成会看到
        // stopped=false 又继续跑，于是两个线程同时进同一个 Ort::Session。
        if (released) return@withContext false
        stopped = false
        synthesize(engine, text)
      } finally {
        gate.release()
        // 被换掉过、且现在没人在用 → 由这里收尾销毁。绝不在别人用着的时候 delete。
        destroyIfIdle()
      }
    }
  }

  private fun synthesize(engine: OfflineTts, text: String): Boolean {
    val sampleRate = engine.sampleRate()
    if (sampleRate <= 0) {
      Log.w(TAG, "本地引擎报了个不可用的采样率：$sampleRate")
      return false
    }
    val audio = buildTrack(sampleRate)
    synchronized(trackLock) { track = audio }
    var framesWritten = 0L
    val callback =
      samplesCallback { samples ->
        if (stopped) {
          0 // 0 = 让 native 停下，不再往下算
        } else {
          framesWritten += audio.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
          1
        }
      }
    return try {
      audio.play()
      // sid 是**角色**不是常量：这些音色是「一个游戏一份多说话人模型」，角色是里面的某一行。
      // 写死 0 不会报错，只会读出别人的声音（见 TtsModel.sid）。
      engine.generateWithCallback(text = text, sid = model?.sid ?: 0, speed = rate, callback = callback)
      if (stopped) {
        Log.i(TAG, "合成被停止")
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
      // 置空与释放要在同一把锁里完成：不然 [stop] 可能正好读到刚被释放掉的那个对象。
      synchronized(trackLock) {
        track = null
        audio.release()
      }
    }
  }

  override fun stop() {
    stopped = true
    // 立刻静音，而不是等下一次回调边界 —— 一段可能还有好几百毫秒没写完。
    // 整段在锁里做：`release()` 可能在另一个线程上跑，出了锁再调 pause 就是 use-after-free。
    synchronized(trackLock) {
      track?.let { audio ->
        runCatching { audio.pause() }
        runCatching { audio.flush() }
      }
    }
  }

  /**
   * 标记这个引擎作废，并**在安全的时候**销毁 native 对象。
   *
   * 不做「这里直接 `tts.release()`」：调用方（[VoiceReader] 换引擎时）跑在主线程上，
   * 而合成跑在 IO 线程上，取消打断不了它 —— 直接销毁就是拿一个正在被使用的
   * `Ort::Session` 去 `delete`，真机上是整个 App 闪退。
   */
  override fun release() {
    released = true
    stop()
    destroyIfIdle()
  }

  /**
   * **被换掉之后**、且没有合成在跑时，才真的销毁。
   *
   * `if (!released) return` 这一行不能省：没有它的话，每次合成结束（finally 里无条件调本函数）
   * 都会把 `tts` 释放掉 —— 表现是「第一段读完就停了，日志里一句『朗读中断』」。
   */
  private fun destroyIfIdle() {
    if (!released) return
    if (!gate.tryAcquire()) return
    try {
      tts?.release()
      tts = null
    } finally {
      gate.release()
    }
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

    /** 等上一次合成退出 native 的上限。它本来就该在几十毫秒内退出。 */
    const val GATE_WAIT_MS = 3_000L
  }
}

/**
 * sherpa-onnx 的采样回调。
 *
 * **必须是匿名对象，不能图省事写成 lambda 字面量。**
 *
 * JNI 那一侧是按**名字与签名**取方法的：`GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`
 * —— 参数是 `float[]`、返回是**装箱的** `Integer`。Kotlin 2.x 默认把 lambda 字面量编成
 * invokedynamic，D8 再落成一个 `Xxx$$ExternalSyntheticLambda0`，那个类里**没有**这个
 * 专用重载（只有擦除后的 `invoke(Object)Object`）。于是 `GetMethodID` 返回 null，
 * native 侧接着调 `NewFloatArray` 时撞上 pending exception，直接
 * `JNI DETECTED ERROR ... NoSuchMethodError` + SIGABRT —— **整个 App 闪退，
 * 而堆栈里只有 sherpa 与协程的帧，一个字都指不到这里**（一加 15T 实测，2026-10-20）。
 *
 * 匿名对象会被编成一个真类，`javap` 出来正好有 `Integer invoke(float[])`。
 * 这是编译产物层面的约束，读代码看不出来 —— 所以下面那条单测钉着它。
 */
internal fun samplesCallback(onSamples: (FloatArray) -> Int): (FloatArray) -> Int =
  object : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onSamples(samples)
  }

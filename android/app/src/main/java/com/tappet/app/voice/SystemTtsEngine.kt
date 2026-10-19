package com.tappet.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 设备自带的 TTS 引擎（`android.speech.tts`）。
 *
 * 这是默认引擎，理由是它**没有代价**：不用下模型、不用联网、不花钱，装完就能用，
 * 而且正文不出设备。代价是音色取决于 ROM 装了什么引擎 —— ColorOS 上通常是可用的中文女声，
 * 但也就那样。想要更好的音色就换 [LocalTtsEngine]（要下一个几十 MB 的模型）。
 *
 * 生命周期：构造是**廉价**的，真正的绑定发生在 [initialize]（要等 `OnInitListener` 回调，
 * 冷启动可能几百毫秒）。绑定之后一直留着 —— 每次朗读都重新绑定会让第一次出声慢半拍，
 * 而 TTS 服务本身很轻。
 */
class SystemTtsEngine(
  private val context: Context,
  private val rate: Float,
  private val voiceId: String?,
) : TtsEngine {
  override val id: String = VoiceEngines.SYSTEM
  override val displayName: String = "系统 TTS"

  /** 远低于 `getMaxSpeechInputLength()`（4000）：切得短才有得停，见 [SpeakableText.DEFAULT_MAX_CHUNK]。 */
  override val maxChunk: Int = 320

  private var tts: TextToSpeech? = null

  /**
   * 当前这一段读完时的回调。
   *
   * 跨线程访问（写在主线程、由 TTS 服务的 binder 线程回调）是**设计内**的：
   * [VoiceReader] 保证同一时刻只有一段在飞，所以这里永远只有一对「等待中的回调 + id」，
   * 用 `@Volatile` 保证可见性即可，不需要锁。
   */
  @Volatile private var pending: ((Boolean) -> Unit)? = null

  @Volatile private var pendingId: String? = null

  private val counter = AtomicInteger()

  override fun isAvailable(): Boolean = true

  override fun unavailableReason(): String? = null

  override suspend fun initialize(): String? {
    if (tts != null) return null
    // 超时是必须的：设备上一个 TTS 引擎都没有时（国产 ROM 常见 —— 引擎装了但要用户先去
    // 系统设置里选一次），`OnInitListener` 可能**永远不回调**，那样朗读会静默地卡在
    // 「准备中」，用户看到的是点了没反应。宁可超时报错，也不要挂住。
    val engine =
      withContext(Dispatchers.Main) { withTimeoutOrNull(INIT_TIMEOUT_MS) { awaitEngine() } }
        ?: return INIT_FAILED
    val language = pickLanguage(engine)
    if (language == null) {
      engine.shutdown()
      return "系统里没有可用的语音数据：设置 → 系统设置 → 语言与输入 → 文字转语音，装一个引擎或下载语音包"
    }
    engine.setSpeechRate(rate)
    engine.setOnUtteranceProgressListener(listener)
    // 选中的音色可能已经被卸载/换引擎了：找不到就退回语言默认，不是错误。
    voiceId?.let { id -> applyVoice(engine, id) }
    tts = engine
    return null
  }

  override fun voices(): List<TtsVoice> {
    val engine = tts ?: return emptyList()
    val all = runCatching { engine.voices }.getOrNull() ?: return emptyList()
    val language = engine.voice?.locale?.language ?: Locale.getDefault().language
    return all
      .asSequence()
      .filter { it.locale?.language == language }
      .map { voice ->
        val base = voice.locale?.displayName ?: voice.name
        TtsVoice(voice.name, if (voice.isNetworkConnectionRequired) "$base（需联网）" else base)
      }
      // 同一语言下常有多个音色共用同一个显示名（不同引擎 / 不同质量档），
      // 界面上重名会让用户以为点错了 —— 去重，只留第一个。
      .distinctBy { it.label }
      .sortedBy { it.label }
      .toList()
  }

  override fun selectVoice(id: String?) {
    val engine = tts ?: return
    if (id == null) return
    applyVoice(engine, id)
  }

  override suspend fun speak(text: String): Boolean {
    val engine = tts ?: return false
    val utteranceId = "tappet-$UTTERANCE_PREFIX-${counter.incrementAndGet()}"
    return suspendCancellableCoroutine { continuation ->
      pendingId = utteranceId
      pending = { ok -> if (continuation.isActive) continuation.resume(ok) }
      val started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
      if (started != TextToSpeech.SUCCESS) {
        pending = null
        pendingId = null
        if (continuation.isActive) continuation.resume(false)
      }
      // 协程被取消（用户点了停止 / 又点了另一条消息）时，引擎这边也要立刻闭嘴。
      continuation.invokeOnCancellation { engine.stop() }
    }
  }

  override fun stop() {
    val callback = pending
    pending = null
    pendingId = null
    tts?.stop()
    // 回调置空之前先取出来：`stop()` 触发的是 onStop，那条路会走 finish() 发现 pending 已经空了。
    // 这里主动回一次，保证 suspend 中的那段一定醒过来（协程没被取消时尤其重要）。
    callback?.invoke(false)
  }

  override fun release() {
    stop()
    tts?.shutdown()
    tts = null
  }

  /** 绑定引擎并等 `OnInitListener`。失败（系统里一个引擎都没有）时返回 null。 */
  private suspend fun awaitEngine(): TextToSpeech? =
    suspendCancellableCoroutine { continuation ->
      val holder = arrayOfNulls<TextToSpeech>(1)
      holder[0] =
        TextToSpeech(context.applicationContext) { status ->
          // 回调是异步投递的，正常情况下 holder[0] 此时一定已经赋值。
          val created = holder[0]
          if (continuation.isActive) {
            continuation.resume(if (status == TextToSpeech.SUCCESS) created else null)
          } else {
            created?.shutdown()
          }
        }
    }

  /**
   * 挑一个能用的语言。先简体中文（正文基本都是中文），再系统语言，最后美式英语兜底 ——
   * 一个都没装上时才失败，这时报错比「用错误的语言念中文」好。
   */
  private fun pickLanguage(engine: TextToSpeech): Locale? {
    for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.getDefault(), Locale.US)) {
      val result = engine.setLanguage(locale)
      if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return locale
    }
    return null
  }

  private fun applyVoice(engine: TextToSpeech, id: String) {
    // 个别引擎（尤其厂商自带的）在设置音色时会抛 —— 音色只是偏好，不值得让朗读整个失败。
    runCatching { engine.voices?.firstOrNull { it.name == id }?.let { engine.voice = it } }
      .onFailure { Log.w(TAG, "设置音色失败：${it.javaClass.simpleName}") }
  }

  private fun finish(utteranceId: String?, ok: Boolean) {
    if (utteranceId == null || utteranceId != pendingId) return
    val callback = pending ?: return
    pending = null
    pendingId = null
    callback(ok)
  }

  private val listener =
    object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit

      override fun onDone(utteranceId: String?) = finish(utteranceId, true)

      @Deprecated("被 onError(String, Int) 取代，但低版本引擎仍只会调这个")
      override fun onError(utteranceId: String?) = finish(utteranceId, false)

      override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)

      override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
    }

  private companion object {
    const val TAG = "TappetVoice"
    const val UTTERANCE_PREFIX = "tts"
    const val INIT_TIMEOUT_MS = 5_000L
    const val INIT_FAILED = "系统 TTS 引擎初始化失败：这台设备没有可用的文字转语音引擎，去系统设置里选一个（设置 → 无障碍 → 文字转语音）"
  }
}

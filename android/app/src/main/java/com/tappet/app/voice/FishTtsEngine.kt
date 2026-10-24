package com.tappet.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Fish Audio 的云端朗读（`POST https://api.fish.audio/v1/tts`）。
 *
 * 走的是官方的**免费模型** `s2.1-pro-free`（$0 / 百万字节，见 docs.fish.audio 的定价页），
 * 因此这里是 Tappet 里唯一一条把**助手正文发到设备之外**的路径 —— 与视觉路径同级：
 * 正文只作为请求体出现，不写日志、不落盘（合成的 mp3 只在播放期间存在于 cacheDir，
 * 播完立即删除）。这条边界与理由记在 `docs/voice.md`。
 *
 * 一次 [speak] 是「一次 HTTP 同步请求 + 一次本地播放」：Fish 的同步接口会把整段音频
 * 一次性返回，所以 [VoiceReader] 的逐段循环天然就是逐句合成、逐句播放 —— 用户听到第一句
 * 的时间只取决于第一段的长度。
 *
 * 密钥由 [VoiceSecretStore] 提供（Keystore 封装），不进 BuildConfig、不进日志。
 */
class FishTtsEngine(
  private val context: Context,
  private val rate: Float,
  private val apiKey: String?,
) : TtsEngine {
  override val id: String = VoiceEngines.FISH
  override val displayName: String = "Fish Audio"

  /** 与旧引擎同一个数字：切得短才有得停（见 [SpeakableText.DEFAULT_MAX_CHUNK]）。 */
  override val maxChunk: Int = 320

  override var lastError: String? = null
    private set

  private val lock = Any()
  private var player: MediaPlayer? = null

  override fun isAvailable(): Boolean = !apiKey.isNullOrBlank()

  override fun unavailableReason(): String? =
    if (isAvailable()) null else "还没有配置 Fish Audio API key（设置 → 语音 → Fish Audio）"

  override suspend fun initialize(): String? = unavailableReason()

  /** 音色由 Fish 侧决定（默认音色）。没有可选项时返回空表，界面据此不画选择器。 */
  override fun voices(): List<TtsVoice> = emptyList()

  override fun selectVoice(id: String?) = Unit

  override suspend fun speak(text: String): Boolean {
    lastError = null
    val key = apiKey?.takeIf { it.isNotBlank() }
    if (key == null) {
      lastError = unavailableReason()
      return false
    }
    val audio = download(key, text) ?: return false
    return play(audio)
  }

  override fun stop() {
    val current = synchronized(lock) { player.also { player = null } } ?: return
    runCatching { current.stop() }
    runCatching { current.release() }
  }

  override fun release() = stop()

  /** 合成一段，返回 mp3 字节。失败时把原因留在 [lastError] 里。 */
  private suspend fun download(key: String, text: String): ByteArray? =
    withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url(FISH_ENDPOINT)
          .header("Authorization", "Bearer $key")
          // model 走 **header**：放进 JSON 会被忽略，静默退回默认的付费模型。
          .header("model", FISH_MODEL)
          .post(fishRequestBody(text, rate).toRequestBody(JSON))
          .build()
      try {
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            lastError = fishErrorText(response.code, response.body?.string())
            Log.w(TAG, "tts http=${response.code}")
            null
          } else {
            response.body?.bytes()
          }
        }
      } catch (
        // 网络失败的种类很多（超时、DNS、TLS…），统一翻成一句人话；这里只记类名。
        @Suppress("TooGenericExceptionCaught") error: Exception,
      ) {
        lastError = "连不上 Fish Audio：${error.javaClass.simpleName}"
        Log.w(TAG, "tts 请求失败：${error.javaClass.simpleName}")
        null
      }
    }

  private suspend fun play(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) {
      lastError = "Fish Audio 返回了空音频"
      return false
    }
    // 合成结果就是这段正文本身：只在播放期间落到 cacheDir，播完立即删（不留下聊天内容）。
    val file = File.createTempFile("fish-tts-", ".mp3", context.cacheDir)
    return try {
      file.writeBytes(bytes)
      playFile(file)
    } finally {
      file.delete()
    }
  }

  private suspend fun playFile(file: File): Boolean {
    val current =
      withContext(Dispatchers.IO) {
        try {
          MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            )
            setDataSource(file.path)
            // prepare() 会读文件：留在主线程上会卡界面，所以在 IO 里做完再交给播放。
            prepare()
          }
        } catch (
          @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
          lastError = "音频播放失败：${error.javaClass.simpleName}"
          null
        }
      } ?: return false

    synchronized(lock) { player = current }
    return suspendCancellableCoroutine { continuation ->
      fun finish(ok: Boolean) {
        synchronized(lock) { if (player === current) player = null }
        runCatching { current.release() }
        if (continuation.isActive) continuation.resume(ok)
      }
      current.setOnCompletionListener { finish(true) }
      current.setOnErrorListener { _, what, extra ->
        lastError = "音频播放失败（what=$what extra=$extra）"
        finish(false)
        true
      }
      runCatching { current.start() }.onFailure {
        lastError = "音频播放失败：${it.javaClass.simpleName}"
        finish(false)
      }
      // 用户点停止 / 又点了另一条消息：立刻闭嘴，并把挂起的那一段放掉。
      continuation.invokeOnCancellation {
        runCatching { current.stop() }
        finish(false)
      }
    }
  }

  private companion object {
    const val TAG = "TappetVoice"
    val JSON = "application/json".toMediaType()
    val client: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 长段文本的合成是同步的：60s 足够，且比默认的 10s 更接近真实等待。
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
  }
}

/**
 * 免费模型。改这里 = 改计费与额度，所以单独一个常量，并且只有这一个地方写它。
 * `s2.1-pro` / `s2-pro` / `s1` 都是付费模型，别手滑。
 */
internal const val FISH_MODEL = "s2.1-pro-free"

internal const val FISH_ENDPOINT = "https://api.fish.audio/v1/tts"

/** `/v1/tts` 的请求体。抽成纯函数，JVM 单测才能钉住「model 不进 body」之外的每个字段。 */
internal fun fishRequestBody(text: String, rate: Float): String =
  buildJsonObject {
    put("text", text)
    put("format", "mp3")
    put("mp3_bitrate", 128)
    putJsonObject("prosody") { put("speed", rate) }
  }.toString()

/**
 * Fish 的错误码翻成用户能看懂的一句话。未知状态码把服务端 `message` 带出来 ——
 * 只说「朗读失败」等于什么都没说。
 */
internal fun fishErrorText(status: Int, body: String?): String {
  val detail =
    body?.let { raw ->
      runCatching {
        Json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.contentOrNull
      }.getOrNull()
    }
  return when (status) {
    401 -> "Fish Audio API key 无效或已过期"
    402 -> "Fish Audio 余额不足"
    429 -> "Fish Audio 限流（并发达上限），请稍后重试"
    503 -> "Fish Audio 服务暂时不可用"
    else -> detail?.let { "Fish Audio 返回 HTTP $status：$it" } ?: "Fish Audio 返回 HTTP $status"
  }
}

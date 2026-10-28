package com.zoneu.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 朗读的**唯一状态源**：谁在读、读到第几段、为什么没读成。
 *
 * 做成单例（而不是挂在某个 ViewModel 上）的理由与 `AgentProgressCenter` / `UpdateManager`
 * 一样：朗读的发起方有两个（聊天页那条消息的喇叭按钮、回复结束时自动读），而消费方
 * 只有一个界面状态 —— 分散成两份状态机，就会出现「按钮显示在播、其实已经停了」这类
 * 只有用户能发现的不一致。
 *
 * 同时只有一条消息在读：点另一条 = 停掉当前的、开始新的。这也是 TTS 引擎本身的约束
 * （一个引擎一个输出流），与其在下面排队，不如在上面就定义成「一次一条」。
 *
 * **不落日志、不持久化**：正文只在本进程内从 Markdown 变成音频，和读屏 payload 同级
 * （见 AGENTS.md「屏幕与聊天正文不落日志」）。这里记的日志只有长度与失败原因。
 */
object VoiceReader {
  /** 正在朗读的进度。`chunk` 从 1 开始，供界面显示「第 2/5 段」。 */
  data class State(
    val messageId: String,
    val chunk: Int,
    val chunks: Int,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _state = MutableStateFlow<State?>(null)
  val state: StateFlow<State?> = _state.asStateFlow()

  /** 上一次失败的原因（用户可见的一句话）。界面显示后应当调 [clearError]。 */
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private var job: Job? = null
  private var engine: TtsEngine? = null

  /** 建引擎时用的配置快照。用户改了语速 / 换引擎 / 换音色，key 就变，下次朗读重建。 */
  private var engineKey: String? = null

  private var focusRequest: AudioFocusRequest? = null
  private var appContext: Context? = null

  /**
   * 建引擎 + 初始化串行化。
   *
   * 两个入口会碰到同一件事：聊天页的朗读、设置页拉音色列表。系统引擎的绑定是异步的，
   * 并发进入会绑两次、留一个没人 shutdown 的实例（表现为服务连接泄漏）。
   */
  private val engineLock = Mutex()

  /** 点喇叭：正在读这条就停，否则从头读。 */
  fun toggle(context: Context, messageId: String, markdown: String) {
    if (_state.value?.messageId == messageId) stop() else speak(context, messageId, markdown)
  }

  /** 读一条消息。已经在读别的会先停下 —— 不排队，用户点的永远是最后点的那条。 */
  fun speak(context: Context, messageId: String, markdown: String) {
    val text = SpeakableText.of(markdown)
    stop()
    if (text.isBlank()) {
      _error.value = "这条消息没有可朗读的正文"
      return
    }
    _error.value = null
    val app = context.applicationContext
    appContext = app
    Log.i(TAG, "朗读开始 chars=${text.length}")
    job = scope.launch { run(app, messageId, text) }
  }

  /** 停止朗读并放开音频焦点。没在读时是空操作。 */
  fun stop() {
    job?.cancel()
    job = null
    engine?.stop()
    _state.value = null
    abandonFocus()
  }

  fun clearError() {
    _error.value = null
  }

  /** 当前配置下的引擎实例。构造是廉价的；真正的绑定在 [TtsEngine.initialize]。 */
  fun currentEngine(context: Context): TtsEngine = engineFor(context)

  /**
   * 列出当前引擎的音色（会先把引擎初始化好）。设置页用。
   *
   * 引擎起不来 / 本地模型还没下时返回空表 —— 界面据此说「没有可选音色」，
   * 而不是给一个空下拉框让用户以为坏了。
   */
  suspend fun availableVoices(context: Context): List<TtsVoice> = engineLock.withLock {
    val engine = engineFor(context)
    if (engine.initialize() != null) emptyList() else engine.voices()
  }

  /** 试听：走的是与朗读**完全相同**的那条路，所以听到的就是实际效果。 */
  fun preview(context: Context, text: String) {
    speak(context, PREVIEW_MESSAGE_ID, text)
  }

  private suspend fun run(context: Context, messageId: String, text: String) {
    val engine = engineFor(context)
    val initError = engineLock.withLock { engine.initialize() }
    if (initError != null) {
      // 失败原因要落日志：界面上那句话几秒后就消失了，事后只能靠日志查。
      // 这里只有引擎名与原因，没有正文。
      Log.w(TAG, "引擎 ${engine.id} 初始化失败：$initError")
      _error.value = initError
      return
    }
    val chunks = SpeakableText.chunks(text, engine.maxChunk)
    Log.i(TAG, "开始朗读：引擎=${engine.id} 段数=${chunks.size}")
    if (chunks.isEmpty()) {
      _error.value = "这条消息没有可朗读的正文"
      return
    }
    // 焦点在开口**之前**要：等第一段已经出声再抢，音乐已经放出来了，听起来像卡了一下。
    requestFocus(context)
    try {
      chunks.forEachIndexed { index, chunk ->
        _state.value = State(messageId, index + 1, chunks.size)
        // 一边播这一段、一边合成下一段：否则每句之间要空等一次网络往返 + 合成。
        chunks.getOrNull(index + 1)?.let { next -> scope.launch { engine.prefetch(next) } }
        if (!engine.speak(chunk)) {
          // 被 stop() 打断是正常路径（协程已经不活跃），只有还活着才值得报错。
          if (currentCoroutineContext().isActive) {
            // 云端引擎的失败有具体原因（key / 余额 / 限流），优先用它；没有才是「中断」。
            Log.w(TAG, "引擎 ${engine.id} 朗读失败：${engine.lastError ?: "中断"}")
            _error.value = engine.lastError ?: "朗读中断：${engine.displayName}"
          }
          return
        }
      }
    } finally {
      _state.value = null
      abandonFocus()
    }
  }

  /**
   * 按当前偏好取引擎。配置没变就复用 —— 系统引擎的绑定要几百毫秒，每次点喇叭都重来
   * 会让第一次出声慢半拍。
   */
  private fun engineFor(context: Context): TtsEngine {
    val engineId = VoiceSettings.engineId(context)
    val rate = VoiceSettings.rate(context)
    val voice = VoiceSettings.fishVoice(context)
    // key 的版本号也在缓存键里：换了 API key 之后下次朗读必须重建引擎，否则还在用旧的。
    val key = "$engineId|$rate|$voice|${VoiceSecretStore.revision(context)}"
    engine?.let { if (key == engineKey) return it }
    engine?.release()
    val created = build(context, rate, voice)
    engine = created
    engineKey = key
    return created
  }

  /**
   * 指定引擎是否可用（不初始化、不碰当前实例）。设置页要**同时**显示两个引擎的状态：
   * 「本地引擎」是不是能用，与用户当前选了谁无关 —— 否则得先切过去才知道它没装模型。
   */
  fun reasonFor(context: Context): String? = build(context).unavailableReason()

  private fun build(
    context: Context,
    rate: Float = VoiceSettings.rate(context),
    voice: String = VoiceSettings.fishVoice(context),
  ): TtsEngine =
    FishTtsEngine(
      context = context,
      rate = rate,
      voiceId = voice,
      apiKey = VoiceSecretStore.apiKey(context),
    )

  /**
   * 抢音频焦点：朗读期间让音乐暂停，读完自动恢复。
   *
   * 用 `TRANSIENT` 而不是 `TRANSIENT_MAY_DUCK`：朗读一条回答是十几秒到一分钟的事，
   * 让音乐以极低音量在下面继续放，听感比暂停更差。
   */
  private fun requestFocus(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    val request =
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
          // 来电 / 别的 App 抢走焦点：让路。回来后不自动续读 —— 从半句话中间接上去
          // 比不读更让人困惑，用户再点一次喇叭即可。
          if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()
    if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      focusRequest = request
    }
  }

  private fun abandonFocus() {
    val request = focusRequest ?: return
    focusRequest = null
    appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
  }

  private const val TAG = "ZoneuVoice"

  /** 试听用的哨兵 id：没有任何一条消息是它，所以界面上的喇叭按钮不会被它点亮。 */
  const val PREVIEW_MESSAGE_ID = "zoneu-preview"
}

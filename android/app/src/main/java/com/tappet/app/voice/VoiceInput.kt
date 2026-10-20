package com.tappet.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音输入的**唯一状态源**：在不在听、听到什么、为什么没听成。
 *
 * 做成单例的理由与 [VoiceReader] 一样：发起方是聊天页的麦克风按钮，消费方是同一个界面的
 * 状态 —— 两份状态机会出现「按钮显示在录、其实已经停了」这种只有用户能发现的不一致。
 *
 * ## 一次交互长什么样
 *
 * 点麦克风 → 开始录（界面显示电平与秒数）→ 用户停下说话，**自动**在尾静音后结束
 * （见 [EndpointDetector]），或者用户自己点停止 → 识别 → 文本回到输入框。
 * 一次只处理一段，不做连续听写：连续听写要把「这句话什么时候算说完」交给用户去管，
 * 而输入框里装的是**一条指令**，不是聊天记录。
 *
 * ## 为什么识别引擎要缓存
 *
 * 建一次识别器要**把几十上百 MB 的模型读进内存**（sense-voice 是 239MB），
 * 每次说话都重建就是每次等好几秒。所以按模型 id 缓存（[engineKey]），
 * 换模型 / 删模型时才换实例 —— 与 [VoiceReader] 的 `engineKey` 同一个形状。
 *
 * **音频不落日志、不落盘**：它只在本进程的内存里存在到识别完，之后就被丢掉。
 * 日志只有样本长度、字符数与失败原因（见 AGENTS.md 的隐私红线）。
 */
object VoiceInput {
  /** 一次识别结果。[id] 递增，界面据此判断「这是一条新结果」而不是重复渲染。 */
  data class Result(
    val id: Long,
    val text: String,
  )

  /** 语音输入的状态机。界面按它决定麦克风按钮长什么样。 */
  sealed interface State {
    data object Idle : State

    /** 正在听。[level] 是 0..1 的电平（画电平条），[elapsedMs] 是已经录了多久。 */
    data class Listening(
      val level: Float,
      val elapsedMs: Long,
    ) : State

    /** 第一次用这个模型，正在把它读进内存。 */
    data object Preparing : State

    /** 音频已经在引擎里，正在出文本。 */
    data object Transcribing : State

    /** 没听成。[reason] 是给用户看的一句话。 */
    data class Failed(val reason: String) : State
  }

  /**
   * 会话协程跑在 [Dispatchers.Default] 而不是主线程上：收尾时要 `join` 采集线程
   * （最长几百毫秒），在主线程上做就是一次可见的卡顿。状态是 `StateFlow`，
   * 从哪个线程写都行。
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state.asStateFlow()

  private val _result = MutableStateFlow<Result?>(null)
  val result: StateFlow<Result?> = _result.asStateFlow()

  private var job: Job? = null

  private var capture: AudioCapture? = null

  /** 这一段的结束信号：采集回调与 [stopAndTranscribe] 都能把它点亮。 */
  private var stopSignal: CompletableDeferred<EndpointDetector.Decision.End>? = null

  private var engine: LocalRecognizer? = null

  /** 建引擎时用的模型 id。用户换了模型，下次识别重建。 */
  private var engineKey: String? = null

  private var resultId = 0L

  /**
   * 上一次估出来的环境噪声底，带进下一段（[EndpointDetector] 的「快降慢升」意味着
   * 第一段在嘈杂环境里可能不准，第二段开始就准了）。进程内保留即可 ——
   * 它只描述「刚才那个房间有多吵」，不值得落盘。
   */
  private var noiseFloor: Float? = null

  /**
   * 现在能不能用。null = 可以；否则是给用户看的一句话（没权限 / 没下模型 / 模型没了）。
   *
   * 界面在**点之前**就要问一次：麦克风按钮不该是一个「点了才知道不能用」的按钮。
   */
  fun unavailableReason(context: Context): String? {
    val app = context.applicationContext
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return "还没有麦克风权限"
    }
    return engineFor(app).unavailableReason()
  }

  fun isListening(): Boolean = _state.value is State.Listening

  /** 点麦克风：在听就停下并识别，不在听就开始听。 */
  fun toggle(context: Context) {
    if (isListening()) stopAndTranscribe() else start(context)
  }

  fun start(context: Context) {
    val app = context.applicationContext
    cancel()
    val reason = unavailableReason(app)
    if (reason != null) {
      _state.value = State.Failed(reason)
      return
    }
    val detector = EndpointDetector(sampleRate = SAMPLE_RATE, initialNoiseFloor = noiseFloor)
    val buffer = SampleBuffer()
    val finished = CompletableDeferred<EndpointDetector.Decision.End>()
    stopSignal = finished
    var lastLevelUpdate = 0L

    val recorder =
      AudioCapture { samples, rms ->
        buffer.add(samples)
        val decision = detector.feed(rms, samples.size)
        if (decision is EndpointDetector.Decision.End) {
          // **不在这里 stop()**：本回调就跑在采集线程上，而 stop() 会 join 它自己。
          // 收尾交给下面的会话协程。
          finished.complete(decision)
          return@AudioCapture
        }
        // 电平每 100ms 刷一次：每帧（32ms）都刷会让 Compose 一直重组，而人眼看不出来。
        val now = System.currentTimeMillis()
        if (now - lastLevelUpdate >= LEVEL_UPDATE_MS && _state.value is State.Listening) {
          lastLevelUpdate = now
          _state.value = State.Listening(level = levelOf(rms), elapsedMs = buffer.durationMs)
        }
      }
    val startError = recorder.start()
    if (startError != null) {
      stopSignal = null
      _state.value = State.Failed(startError)
      return
    }
    capture = recorder
    _state.value = State.Listening(level = 0f, elapsedMs = 0L)
    Log.i(TAG, "开始录音")

    job =
      scope.launch {
        // 采集线程自己死了（麦克风被抢走）不会再有人点亮信号，所以给一个宽于
        // EndpointDetector 上限（60 秒）的兜底。
        val end = withTimeoutOrNull(SESSION_TIMEOUT_MS) { finished.await() }
        noiseFloor = detector.noiseFloorEstimate()
        recorder.stop()
        capture = null
        stopSignal = null
        if (end == null) Log.w(TAG, "录音异常结束，按超时处理")
        transcribe(app, buffer)
      }
  }

  /** 用户点停止：不等到尾静音，立刻收尾。没在听时是空操作。 */
  fun stopAndTranscribe() {
    if (!isListening()) return
    stopSignal?.complete(EndpointDetector.Decision.End.Silence)
  }

  /** 放弃这一段（退出页面、权限被撤）。识别结果与音频都不留。 */
  fun cancel() {
    stopSignal?.complete(EndpointDetector.Decision.End.NoSpeech)
    stopSignal = null
    capture?.stop()
    capture = null
    job?.cancel()
    job = null
    if (_state.value !is State.Failed) _state.value = State.Idle
  }

  fun clearResult() {
    _result.value = null
  }

  fun clearError() {
    if (_state.value is State.Failed) _state.value = State.Idle
  }

  private suspend fun transcribe(
    app: Context,
    buffer: SampleBuffer,
  ) {
    val samples = buffer.toFloatArray()
    if (samples.size < MIN_SAMPLES) {
      _state.value = State.Failed("没听到说话")
      return
    }
    val engine = engineFor(app)
    _state.value = if (engine.isReady()) State.Transcribing else State.Preparing
    val initError = engine.initialize()
    if (initError != null) {
      Log.w(TAG, "识别引擎不可用：$initError")
      _state.value = State.Failed(initError)
      return
    }
    _state.value = State.Transcribing
    val text = engine.transcribe(samples)
    if (text.isBlank()) {
      _state.value = State.Failed("没听清，再说一次")
      return
    }
    resultId++
    _result.value = Result(resultId, text)
    _state.value = State.Idle
  }

  /**
   * 按当前偏好取引擎。配置没变就复用 —— 建一次识别器要把模型读进内存，
   * 每次说话都重来会让第一次识别慢好几秒。
   */
  private fun engineFor(context: Context): LocalRecognizer {
    val modelId = VoiceSettings.asrModel(context)
    engine?.let { if (modelId == engineKey) return it }
    engine?.release()
    val created = LocalRecognizer(context, modelId)
    engine = created
    engineKey = modelId
    return created
  }

  /** 电平：把 RMS 映射到 0..1 给界面画条。0.15 已经是很响的说话声。 */
  private fun levelOf(rms: Float): Float = (rms / LEVEL_FULL_SCALE).coerceIn(0f, 1f)

  private const val TAG = "TappetVoice"
  private const val SAMPLE_RATE = 16_000
  private const val LEVEL_FULL_SCALE = 0.15f
  private const val LEVEL_UPDATE_MS = 100L

  /** 会话上限。比 [EndpointDetector] 的 60 秒上限宽一点，正常轮不到它。 */
  private const val SESSION_TIMEOUT_MS = 90_000L

  /** 短于这个样本数（0.2 秒）不值得送进模型。 */
  private const val MIN_SAMPLES = 3_200

  /** 把一帧一帧的样本攒成一段。用块表避免每次追加都复制整段音频。 */
  private class SampleBuffer {
    private val chunks = ArrayList<FloatArray>()

    private var samples: Int = 0

    val durationMs: Long get() = samples * 1000L / SAMPLE_RATE

    fun add(chunk: FloatArray) {
      chunks.add(chunk)
      samples += chunk.size
    }

    fun toFloatArray(): FloatArray {
      val out = FloatArray(samples)
      var offset = 0
      chunks.forEach { chunk ->
        chunk.copyInto(out, offset)
        offset += chunk.size
      }
      return out
    }
  }
}

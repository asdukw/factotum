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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
 * 点麦克风 → 开始录（界面显示电平与秒数）→ 用户停下说话，**自动**在说完之后结束
 * （判定交给 silero VAD，见 [VadSegmenter] 与 [dictationEnd]），或者用户自己点停止 →
 * 识别 → 文本回到输入框。
 * 一次只处理一段，不做连续听写：连续听写要把「这句话什么时候算说完」交给用户去管，
 * 而输入框里装的是**一条指令**，不是聊天记录。
 *
 * ## 识别是**按段**做的，不是把整场音频拼起来
 *
 * VAD 每吐出一段（说完一句、或者说到 `maxSpeechDuration` 被强制切开），就立刻送去识别，
 * 边录边出结果（[consume]）。早先的写法是把整场会话的所有段攒成一条音频、收尾时一次
 * `decode` —— 正常情况（说完即停）只有一段，看不出差别；但**连续说话超过 10 秒就会被切段**，
 * 一路说到 90 秒上限时就是一次 decode 90 秒音频：延迟与内存全压在最后一下。
 * 按段做之后，收尾只需等最后那一段。
 *
 * 代价是**段与段之间没有上下文**：一句话被 `maxSpeechDuration` 从中间切开时，两半各自
 * 解码（[RecognizedText.joinSegments] 再把文本接起来），边界上可能多一个断句。
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
  private var stopSignal: CompletableDeferred<DictationEnd>? = null

  private var engine: LocalRecognizer? = null

  /** 建引擎时用的模型 id。用户换了模型，下次识别重建。 */
  private var engineKey: String? = null

  private var punctuator: LocalPunctuator? = null

  /** 建标点引擎时用的模型 id。同上。 */
  private var punctuatorKey: String? = null

  private var resultId = 0L

  /**
   * 会话序号。每次 [start] 递增，会话协程记下自己那一个，收尾时**只清理自己这一场**的共享状态。
   *
   * 没有它的话，「点麦克风 → 立刻再点一次」这条路径上，上一场协程的 `finally` 会跑在新一场
   * 开始**之后**：`capture` 被清成 null（这一场的麦克风再也没人关），`stopSignal` 被清成 null
   * （点停止没反应，只能等 VAD 或 90 秒上限）。`job.cancel()` 只是把协程标记成取消，
   * **不会等它退出** —— 而收尾里有 `join` 采集线程这一步，它一定会慢半拍。
   *
   * 只从主线程写（[start] / [cancel]），读它的地方都拿得到自己的那份拷贝。
   */
  private var session = 0L

  /**
   * 麦克风还开着吗。识别是**边录边做**的，但界面此刻该显示的是「正在听 + 电平条」——
   * 中途换成「正在识别」会让麦克风按钮丢掉在录的样子，而那才是用户此刻在做的事。
   * 所以识别只在 [capturing] 为假时才动界面状态（见 [recognize]）。
   */
  @Volatile private var capturing = false

  /**
   * 这次会话里引擎建不起来的原因。**记住而不是当场报**：识别在采集回调的旁边跑，
   * 而界面那时正显示着「正在听」，弹一个错误出来会被下一帧的电平刷新盖掉。
   */
  @Volatile private var sessionError: String? = null

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
    val segmenter =
      try {
        VadSegmenter(app.assets)
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        // 打包时漏了 silero_vad.onnx（跑 tools/fetch-sherpa.ps1 就有了）。这是构建问题，
        // 但对用户来说就是「这个功能现在不能用」，如实说，别把它说成设备的问题。
        Log.e(TAG, "VAD 起不来：${error.javaClass.simpleName}")
        _state.value = State.Failed("语音输入暂时不可用（端点检测没起来）")
        return
      }
    sessionError = null
    val mine = ++session
    // 段从采集线程流到识别协程。**无界**：识别第一段时要先把模型读进内存（几秒），
    // 那期间段会攒在这里 —— 上限就是一场会话的音频（90 秒 ≈ 5.7MB），不值得为此加背压
    // 把采集线程堵住。
    val segments = Channel<FloatArray>(Channel.UNLIMITED)
    val finished = CompletableDeferred<DictationEnd>()
    stopSignal = finished
    val startedAt = System.currentTimeMillis()
    var heardSpeech = false
    var lastLevelUpdate = 0L

    val recorder =
      AudioCapture { samples, rms ->
        // **只把 VAD 认过的段放进队列**，原始音频不直接进：环境噪声根本不会出现在
        // 段里，所以「静音喂出幻觉文本」结构上不可能（这是换掉手写能量法的全部理由）。
        val ready = segmenter.accept(samples)
        ready.forEach { segments.trySend(it) }
        if (ready.isNotEmpty()) heardSpeech = true
        val speaking = segmenter.isSpeechDetected()
        val elapsed = System.currentTimeMillis() - startedAt
        val end = dictationEnd(elapsed, speaking, heardSpeech, ready.isNotEmpty())
        if (end != null) {
          // **不在这里 stop()**：本回调就跑在采集线程上，而 stop() 会 join 它自己。
          // 收尾交给下面的会话协程。
          finished.complete(end)
          return@AudioCapture
        }
        // 电平每 100ms 刷一次：每帧（32ms）都刷会让 Compose 一直重组，而人眼看不出来。
        // 这里的 RMS **只用来画电平条**，判定说话与否是 VAD 的事。
        val now = System.currentTimeMillis()
        if (now - lastLevelUpdate >= LEVEL_UPDATE_MS && _state.value is State.Listening) {
          lastLevelUpdate = now
          _state.value = State.Listening(level = levelOf(rms), elapsedMs = elapsed)
        }
      }
    val startError = recorder.start()
    if (startError != null) {
      stopSignal = null
      segments.close()
      segmenter.release()
      _state.value = State.Failed(startError)
      return
    }
    capture = recorder
    capturing = true
    _state.value = State.Listening(level = 0f, elapsedMs = 0L)
    Log.i(TAG, "开始录音")

    job = scope.launch { runSession(app, mine, segmenter, segments, recorder, finished) }
  }

  /**
   * 一场会话的收尾：等结束信号 → 停采集 → 收尾 VAD → 等识别消费完 → 出文本。
   *
   * 从 [start] 里抽出来的：两件事本来就不是一件 —— [start] 管「把设备起起来」，
   * 这里管「把这一场收干净」。挤在一起时 `start` 会同时越过 detekt 的行数与圈复杂度两条线。
   *
   * 是 [CoroutineScope] 的扩展而不是普通 suspend 函数：消费者协程要挂在**这一场**的 job 下
   * （[cancel] 取消 job 时它跟着停），而 `launch` 需要一个 scope 接收者。
   */
  private suspend fun CoroutineScope.runSession(
    app: Context,
    mine: Long,
    segmenter: VadSegmenter,
    segments: Channel<FloatArray>,
    recorder: AudioCapture,
    finished: CompletableDeferred<DictationEnd>,
  ) {
    val texts = mutableListOf<String>()
    var decoded = 0
    val consumer = launch { consume(app, segments, texts, mine) { decoded++ } }
    try {
      // 采集线程自己死了（麦克风被抢走）不会再有人点亮信号，所以给一个宽于
      // [MAX_SESSION_MS] 的兜底。
      val end = withTimeoutOrNull(SESSION_WATCHDOG_MS) { finished.await() }
      if (end == null) Log.w(TAG, "录音异常结束，按超时处理")
      // 收尾顺序**不能反**：`stop()` → `finish()` → `release()`。`Vad` 不是线程安全的，
      // 而 stop() 已经把采集线程 join 掉了；finish() 之前 release() 会撞上已销毁的对象，
      // 而少了 finish() 就丢最后一句（段要等 [VadSegmenter.MIN_SILENCE_MS] 的静音才闭合，
      // 「说完立刻按停」正是最常见的情形）。
      recorder.stop()
      segmenter.finish().forEach { segments.trySend(it) }
    } finally {
      // 共享状态**只清自己这一场**：见 [session]。
      if (session == mine) {
        capturing = false
        capture = null
        stopSignal = null
      }
      // 这两个是本场独有的对象，无论谁接手都要关掉。
      segments.close()
      segmenter.release()
    }
    // 已经被下一场接手了：这一场的识别结果与状态都不该再往界面上写。
    if (session != mine) return
    // 采集停了，但尾巴那一段可能还在识别（尤其是模型第一次要读进内存）：
    // 界面要给「正在识别」的反馈，否则点完停止像没反应。
    if (_state.value is State.Listening) {
      _state.value = if (engine?.isReady() == true) State.Transcribing else State.Preparing
    }
    consumer.join()
    finish(app, texts, decoded, mine)
  }

  /** 用户点停止：不等 VAD 判完，立刻收尾。没在听时是空操作。 */
  fun stopAndTranscribe() {
    if (!isListening()) return
    stopSignal?.complete(DictationEnd.Silence)
  }

  /** 放弃这一段（退出页面、权限被撤）。识别结果与音频都不留。 */
  fun cancel() {
    stopSignal?.complete(DictationEnd.NoSpeech)
    stopSignal = null
    capture?.stop()
    capture = null
    capturing = false
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

  /**
   * 段一到就识别，把文本攒进 [into]。
   *
   * **顺序是有保证的**：`Channel` 是队列，协程串行取 —— 识别结果因此天然按说话顺序拼，
   * 不需要排序也不需要时间戳（`SpeechSegment.start` 那条路是给「要时间轴」的场景留的，
   * 这里要的只是一句指令）。
   */
  private suspend fun consume(
    app: Context,
    segments: ReceiveChannel<FloatArray>,
    into: MutableList<String>,
    mine: Long,
    onDecoded: () -> Unit,
  ) {
    for (segment in segments) {
      // 短于 [MIN_SAMPLES] 的不值得送进模型：VAD 最短的段是 250ms，正常轮不到这里。
      if (segment.size < MIN_SAMPLES) continue
      onDecoded()
      val recognition = recognize(app, segment, mine)
      if (recognition.text.isNotBlank()) into += recognition.text
    }
  }

  private suspend fun recognize(
    app: Context,
    samples: FloatArray,
    mine: Long,
  ): Recognition {
    // 这一场已经被下一场接手了：不再动界面状态、也不再把错误记进共享字段（见 [session]）。
    if (session != mine) return Recognition.EMPTY
    // 引擎建不起来时不再逐段重试：每段都会重新走一遍 native 加载，而结果是同一个。
    if (sessionError != null) return Recognition.EMPTY
    val engine = engineFor(app)
    // 还在录的时候**不动界面状态**，理由见 [capturing]。
    if (!capturing) _state.value = if (engine.isReady()) State.Transcribing else State.Preparing
    val initError = engine.initialize()
    if (initError != null) {
      Log.w(TAG, "识别引擎不可用：$initError")
      sessionError = initError
      return Recognition.EMPTY
    }
    return engine.transcribe(samples)
  }

  private suspend fun finish(
    app: Context,
    texts: List<String>,
    decoded: Int,
    mine: Long,
  ) {
    if (session != mine) return
    sessionError?.let { reason ->
      _state.value = State.Failed(reason)
      return
    }
    // 先拼段再补标点：标点模型要看到完整的句子才知道该在哪断 —— 逐段补会在段边界上
    // 塞进句号，而那里往往正是一句话的中间。
    val joined = RecognizedText.joinSegments(texts)
    if (joined.isBlank()) {
      _state.value = State.Failed(if (decoded == 0) "没听到说话" else "没听清，再说一次")
      return
    }
    resultId++
    _result.value = Result(resultId, punctuate(app, joined))
    _state.value = State.Idle
  }

  /**
   * 按需补标点。**三层都能单独放行**：模型本身产标点（sense-voice / whisper）、标点模型没装、
   * 标点引擎起不来 —— 任何一种都只是原样返回，语音输入照常可用。
   */
  private suspend fun punctuate(
    app: Context,
    text: String,
  ): String {
    val model = AsrModelCatalog.byId(AsrModelCatalog.selectedId(app)) ?: return text
    if (!model.engine.needsPunctuation) return text
    val engine = punctuatorFor(app) ?: return text
    val initError = engine.initialize()
    if (initError != null) {
      Log.w(TAG, "标点引擎不可用：$initError")
      return text
    }
    return engine.addPunctuation(text)
  }

  /**
   * 按当前偏好取识别引擎。配置没变就复用 —— 建一次识别器要把模型读进内存，
   * 每次说话都重来会让第一次识别慢好几秒。
   */
  private fun engineFor(context: Context): LocalRecognizer {
    val modelId = AsrModelCatalog.selectedId(context)
    engine?.let { if (modelId == engineKey) return it }
    engine?.release()
    val created = LocalRecognizer(context, modelId)
    engine = created
    engineKey = modelId
    return created
  }

  /** 取标点引擎；模型没装就回 null（不是失败，是「这次不补标点」）。 */
  private fun punctuatorFor(context: Context): LocalPunctuator? {
    val modelId = PunctModelCatalog.installedId(context)
    punctuator?.let { if (modelId == punctuatorKey) return it }
    punctuator?.release()
    punctuatorKey = modelId
    punctuator = modelId?.let { LocalPunctuator(context) }
    return punctuator
  }

  /** 电平：把 RMS 映射到 0..1 给界面画条。0.15 已经是很响的说话声。 */
  private fun levelOf(rms: Float): Float = (rms / LEVEL_FULL_SCALE).coerceIn(0f, 1f)

  private const val TAG = "TappetVoice"
  private const val LEVEL_FULL_SCALE = 0.15f
  private const val LEVEL_UPDATE_MS = 100L

  /** 会话兜底。比 [MAX_SESSION_MS] 宽，正常轮不到它 —— 它挡的是「采集线程自己死了」。 */
  private const val SESSION_WATCHDOG_MS = MAX_SESSION_MS + 10_000L

  /** 短于这个样本数（0.2 秒）不值得送进模型。VAD 最短的段是 0.25 秒，正常轮不到它。 */
  private const val MIN_SAMPLES = 3_200
}

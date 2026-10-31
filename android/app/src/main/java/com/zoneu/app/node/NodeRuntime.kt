package com.zoneu.app.node

import android.content.Context
import android.system.Os
import android.util.Log
import com.zoneu.app.agent.AgentConfig
import com.zoneu.app.agent.AgentConfigStore
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.agent.ModelModalities
import com.zoneu.app.agent.PersonaStore
import com.zoneu.app.agent.WorldRuntime
import com.zoneu.app.agent.llmModelsJson
import com.zoneu.app.agent.requestApiKey
import com.zoneu.app.bridge.ApprovalBridgeServer
import com.zoneu.app.bridge.ConnectorBridgeServer
import com.zoneu.app.bridge.DeviceBridgeServer
import com.zoneu.app.bridge.UserQuestionBridgeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** JNI stdout 逐行回调（native 线程调用，禁止阻塞）。 */
interface NodeLineListener {
  fun onLine(line: String)
}

/**
 * 内嵌 libnode 的常驻宿主（P3）。
 *
 * - 一个 App 进程只调一次 `node::Start`，Node 常驻跑 launcher.mjs；
 * - 通过 profile 里的 `@deepseek-ai/dsh-sdk-jsonrpc-server` 走 stdio JSON-RPC：
 *   Kotlin 是 client —— `initialize` 握手、`session/prompt` 提交任务，
 *   `session.event` / `session.status` 通知回流；
 * - stdin 由 native 管道写入，stdout 逐行回调进来（**不进日志**）。
 */
object NodeRuntime : NodeLineListener {
  private const val TAG = "ZoneuNode"
  private const val ASSET_DSH = "dsh-runtime.zip"
  private const val DIR_DSH = "dsh-runtime"
  private const val PREFS = "zoneu_node"
  private const val KEY_DSH_UPDATE = "dsh_last_update"
  private const val KEY_SESSION = "llm_session"

  /**
   * profile 里 llm-pi-ai 注册的 provider 路由名。
   *
   * **必须与 tools/dsh-phone/profile/cordis.patch.yml 里的 provider 键逐字相同** —— 对不上时
   * dsh 报的是 `no adapter registered for provider "xxx"`，看着像运行时缺插件，其实是这里没跟着改。
   */
  internal const val PROVIDER = "zoneu"

  private const val INITIALIZE_TIMEOUT_MS = 180_000L
  private const val PROMPT_TIMEOUT_MS = 30_000L
  private const val RELOAD_TIMEOUT_MS = 60_000L
  private const val EMPTY_KEY_ENV = "ZONEU_LLM_KEY_EMPTY"

  sealed interface Status {
    data object Idle : Status

    data class Preparing(val detail: String) : Status

    /** node::Start 已进入，树还在 mount。 */
    data object Running : Status

    /** initialize 握手完成，可以提交任务。 */
    data object Ready : Status

    data class Exited(val code: Int) : Status

    data class Failed(val reason: String) : Status
  }

  data class TaskOutcome(
    val assistantText: String,
    val toolCalls: Int,
    val turnErrors: List<String>,
  ) {
    val ok: Boolean get() = turnErrors.isEmpty() || assistantText.isNotBlank()
  }

  private val _status = MutableStateFlow<Status>(Status.Idle)
  val status: StateFlow<Status> = _status.asStateFlow()

  /** JSON-RPC 通知帧（method + params），例如 session.event / session.status。 */
  private val _notifications =
    MutableSharedFlow<JsonObject>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val notifications: SharedFlow<JsonObject> = _notifications.asSharedFlow()

  /** 当前通知订阅者数量；配合 [awaitMoreSubscribers] 等**这一个**收集器订阅上。 */
  internal fun notificationSubscribers(): Int = _notifications.subscriptionCount.value

  /** 等到订阅者数量超过 [baseline]，即 launch 之后新起的收集器已经接上。 */
  internal suspend fun awaitMoreSubscribers(baseline: Int) {
    _notifications.subscriptionCount.first { it > baseline }
  }

  /**
   * 解析后的 dsh 领域事件流（UI 与调试可以各自收集）。
   *
   * dsh 侧会话 id 是「zoneu- + 调用方 id」（见 [dshSessionId]），这里映射回调用方传入的 id，
   * 收集方仍按自己的 sessionId 过滤。
   */
  val dshEvents: Flow<DshEvent> =
    notifications.mapNotNull { frame ->
      DshProtocol.parse(frame)?.let { event ->
        val caller = callerSessionIds[event.sessionId]
        if (caller == null) event else event.withSessionId(caller)
      }
    }

  /**
   * 调用方 sessionId → 磁盘上稳定的 dsh sessionId。
   *
   * 不再带运行时随机后缀：重启/重载后先 `session/resume` 把磁盘会话接回来，接不上才让
   * `session/prompt` 新建。dsh 的 resume 会补写中断 turn 的结束事件，所以卡住的轮次不会
   * 继续堵住后续消息。
   */
  private fun dshSessionId(sessionId: String): String =
    dshSessionIds.computeIfAbsent(sessionId) { caller ->
      "zoneu-$caller".also { callerSessionIds[it] = caller }
    }

  private val started = AtomicBoolean(false)
  private val nextId = AtomicLong(1)

  /**
   * 本次运行时实例里已经尝试过 resume 的 dsh 会话；重载时清空。
   * 只影响「是否再发一次 session/resume」，不影响会话 id 本身。
   */
  private val ensuredSessions = ConcurrentHashMap.newKeySet<String>()

  /** 只用于 resume 失败时的兜底 id，不参与正常路径。 */
  private fun newRuntimeTag(): String = UUID.randomUUID().toString().take(8)

  private val dshSessionIds = ConcurrentHashMap<String, String>()
  private val callerSessionIds = ConcurrentHashMap<String, String>()
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
  private val initMutex = Mutex()
  private val reloadMutex = Mutex()
  private val operationGate = Any()
  private var taskActive = false
  private var reloadActive = false
  private var taskPreparation: TaskPreparation? = null
  private val unsettledWorldRequests = ConcurrentHashMap.newKeySet<String>()

  @Volatile private var appContext: Context? = null

  @Volatile private var initResult: JsonObject? = null

  init {
    System.loadLibrary("native-lib")
  }

  /** 幂等启动常驻运行时；重复调用只保证启动一次。 */
  fun ensureStarted(context: Context) {
    if (!started.compareAndSet(false, true)) return
    val app = context.applicationContext
    Thread {
      try {
        _status.value = Status.Preparing("解压 dsh 运行时")
        val dir = installDshRuntime(app)
        applyEnvironment(app)
        applyDshEnvironment(app)
        setLineListener(this)
        if (!prepareStdio()) error("stdio 管道创建失败")
        Log.i(TAG, "stdio ready")
        _status.value = Status.Running
        Log.i(TAG, "node::Start launcher.mjs")
        val code = startNodeWithArguments(arrayOf("node", File(dir, "launcher.mjs").path))
        Log.i(TAG, "node exited code=$code")
        _status.value = Status.Exited(code)
      } catch (
        // 宿主线程的兜底：这里的「太宽」是刻意的 —— 让异常逃出这个线程会带走整个 App
        // （内嵌 Node 与宿主同进程），而这里只想把失败记进 status。
        @Suppress("TooGenericExceptionCaught") throwable: Throwable,
      ) {
        Log.e(TAG, "node start failed", throwable)
        _status.value = Status.Failed(throwable.message ?: throwable.javaClass.simpleName)
      }
    }
      .apply { name = "zoneu-node" }
      .start()
  }

  /** SDK 握手；成功后 status 变 Ready，之后可反复 prompt。 */
  suspend fun initialize(context: Context): JsonObject {
    appContext = context.applicationContext
    initResult?.let { return it }
    ensureStarted(context)
    // 等 native 管道就绪（Running 在 prepareStdio 之后才置位），避免早写 stdin。
    when (val current = status.first { it !is Status.Idle && it !is Status.Preparing }) {
      is Status.Failed -> error("Node 启动失败：${current.reason}")
      is Status.Exited -> error("Node 已退出 code=${current.code}")
      else -> Unit
    }
    return initMutex.withLock {
      initResult ?: run {
        val params =
          buildJsonObject {
            put("cwd", File(context.applicationContext.filesDir, "dsh-home").absolutePath)
            put("provider", PROVIDER)
            put("model", AgentConfigStore.current(context.applicationContext).model)
            // 暂不传 reasoningEffort：pi-ai 路由会校验 effort 白名单，低值会被拒；
            // 省略即用模型默认（与 headless 行为一致）。设置页化后再暴露。
          }
        val result = request("initialize", params, INITIALIZE_TIMEOUT_MS)
        initResult = result
        _status.value = Status.Ready
        Log.i(TAG, "sdk initialize ok: ${result["serverInfo"]}")
        result
      }
    }
  }

  /**
   * 按需重载 dsh 运行时（不重启 Node 进程、不重启 App）。
   *
   * 宿主把**非密配置**写进 `dsh-home/runtime-config.json`，再写 `runtime.reload` 信号；
   * launcher 卸载整棵 cordis 根后重新 mount，并在 mount 前把配置覆盖进 `process.env`。
   * 密钥不落盘：启动时每个提供商各自的 key 已预置成 `ZONEU_LLM_KEY_<ID>`，
   * reload 前会从 Keystore 重新读取并覆盖这些 env，配置里的 `ZONEU_LLM_KEY_FROM` 只做一次 env 拷贝。
   *
   * 重载后必须重新 initialize（SDK 服务是新建的），并换 session 命名空间，
   * 否则会撞上磁盘上已有的同名 dsh 会话。
   */
  suspend fun reload(context: Context): JsonObject = reloadForPreparation(context, null)

  internal suspend fun reloadForPreparation(context: Context, preparation: TaskPreparation?): JsonObject =
    reloadMutex.withLock {
      synchronized(operationGate) {
        check(!taskActive && (taskPreparation == null || taskPreparation === preparation)) { "运行中任务未完成，暂不能应用设置" }
        reloadActive = true
      }
      try {
        reloadLocked(context)
      } finally {
        synchronized(operationGate) { reloadActive = false }
      }
    }

  private suspend fun reloadLocked(context: Context): JsonObject {
    val app = context.applicationContext
    if (!started.get()) {
      ensureStarted(app)
      return initialize(app)
    }
    val config = AgentConfigStore.current(app)
    refreshKeyEnvironment(app, config)
    writeRuntimeConfig(app, config)
    val home = File(app.filesDir, "dsh-home").apply { mkdirs() }
    val ready = File(home, "runtime.ready")
    val before = runCatching { ready.readText() }.getOrDefault("")

    pending.values.forEach {
      it.completeExceptionally(IllegalStateException("runtime reloading"))
    }
    pending.clear()
    WorldRuntime.clearBindings()
    unsettledWorldRequests.clear()
    dshSessionIds.clear()
    callerSessionIds.clear()
    ensuredSessions.clear()
    initResult = null
    _status.value = Status.Preparing("重载运行时")
    Log.i(TAG, "reload requested (provider=${config.preset.label}, model=${config.model})")
    File(home, "runtime.reload").writeText(System.currentTimeMillis().toString())

    try {
      withTimeout(RELOAD_TIMEOUT_MS) {
        while (runCatching { ready.readText() }.getOrDefault("") == before) delay(100)
      }
    } catch (timeout: TimeoutCancellationException) {
      _status.value = Status.Failed("重载超时：${timeout.message ?: "未知错误"}")
      throw timeout
    }
    _status.value = Status.Running
    Log.i(TAG, "runtime reloaded")
    return initialize(app)
  }

  /**
   * 应用设置页改动，但不因保存设置而冷启动 Node。返回 false 表示运行时尚未启动，
   * 下一次正常启动会直接读取最新的 Keystore 配置。
   */
  suspend fun reloadIfRunning(context: Context): Boolean {
    synchronized(operationGate) {
      if (!started.get()) return false
    }
    while (true) {
      synchronized(operationGate) {
        if (!admissionBusy() && taskPreparation == null && status.value is Status.Ready) break
        if (status.value is Status.Failed || status.value is Status.Exited) return false
      }
      delay(100)
    }
    reload(context)
    return true
  }

  /**
   * 等待当前任务完成后应用最新配置。设置页的连续保存都读同一份 current()，所以即便多个
   * 保存操作排队，最后一次 reload 也不会重新使用旧配置。
   */
  suspend fun applyWhenIdle(context: Context): Boolean = reloadIfRunning(context)

  internal class TaskPreparation

  internal class TaskLease

  private var activeTaskLease: TaskLease? = null

  private fun admissionBusy(): Boolean = taskActive || reloadActive || unsettledWorldRequests.isNotEmpty()

  /** Reserves persona/config preparation without holding a task lease across reload. */
  internal fun beginTaskPreparation(): TaskPreparation? = synchronized(operationGate) {
    if (admissionBusy() || taskPreparation != null) return null
    TaskPreparation().also { taskPreparation = it }
  }

  /** Explicit recovery may reserve admission while an unknown world request blocks ordinary tasks. */
  internal fun beginWorldRecovery(): TaskPreparation? = synchronized(operationGate) {
    if (taskActive || reloadActive || taskPreparation != null) return null
    TaskPreparation().also { taskPreparation = it }
  }

  internal fun endTaskPreparation(preparation: TaskPreparation) = synchronized(operationGate) {
    if (taskPreparation === preparation) taskPreparation = null
  }

  internal fun beginTask(preparation: TaskPreparation? = null): TaskLease? =
    synchronized(operationGate) {
      if (admissionBusy() || (taskPreparation != null && taskPreparation !== preparation)) return null
      if (preparation != null && taskPreparation !== preparation) return null
      taskPreparation = null
      TaskLease().also {
        taskActive = true
        activeTaskLease = it
      }
    }

  internal fun endTask(lease: TaskLease) {
    synchronized(operationGate) {
      if (activeTaskLease === lease) {
        taskActive = false
        activeTaskLease = null
      }
    }
  }

  suspend fun prompt(sessionId: String, text: String): String = submitPrompt(sessionId, text)

  /** Roleplay streams register their exact pending identity before the SDK creates the user message. */
  suspend fun worldPrompt(context: Context, sessionId: String, text: String, onRegistered: (String?) -> Unit): String {
    val dshId = ensureSession(sessionId)
    val requestId = WorldRuntime.register(context, sessionId, dshId, text)
    requestId?.let(unsettledWorldRequests::add)
    onRegistered(requestId)
    return submitPrompt(sessionId, text, requestId)
  }

  suspend fun worldControl(context: Context, requestId: String, cancel: Boolean): Boolean {
    if (cancel) WorldRuntime.cancel(requestId)
    val binding = WorldRuntime.binding(requestId) ?: return unsettledWorldRequests.remove(requestId).let { true }
    val trigger = binding.trigger ?: return false
    val params = buildJsonObject {
      put("op", if (cancel) "cancel" else "settle")
      put("sessionId", binding.dshSessionId)
      put("requestId", requestId)
      put("trigger", trigger)
    }
    val result = request("zoneu/world-request", params, PROMPT_TIMEOUT_MS)
    val terminal = WorldRuntime.converge(context, requestId, result)
    if (terminal) unsettledWorldRequests.remove(requestId)
    return terminal
  }

  /**
   * 新运行时实例里第一次用到某个会话时，先把磁盘上的 dsh 会话接回来。
   *
   * dsh 的 `session/resume` 会加载持久化事件、补写中断 turn 的结束事件，并丢掉上一个
   * 进程留下的排队输入；磁盘上没有这个会话时返回 resumed=false，由 `session/prompt` 新建。
   * resume 本身失败（会话损坏等）就换一个一次性 id 新建，保证本轮还能跑。
   */
  private suspend fun ensureSession(sessionId: String): String {
    val dshId = dshSessionId(sessionId)
    if (!ensuredSessions.add(dshId)) return dshId
    val params = buildJsonObject { put("sessionId", dshId) }
    return try {
      val resumed = request("session/resume", params, PROMPT_TIMEOUT_MS)["resumed"]?.jsonPrimitive?.booleanOrNull == true
      if (!resumed) Log.i(TAG, "session resume: no persisted log, will create")
      dshId
    } catch (timeout: TimeoutCancellationException) {
      freshSession(sessionId, timeout)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Exception,
    ) {
      freshSession(sessionId, error)
    }
  }

  /** resume 失败时换一个一次性 dsh id，至少让这一轮还能新建会话。 */
  private fun freshSession(sessionId: String, cause: Throwable): String {
    Log.w(TAG, "session resume failed; using a fresh dsh session", cause)
    val fresh = "zoneu-$sessionId-${newRuntimeTag()}"
    dshSessionIds[sessionId] = fresh
    callerSessionIds[fresh] = sessionId
    ensuredSessions.add(fresh)
    return fresh
  }

  /**
   * 中断某会话正在跑的 turn 并清掉排队输入，用于 30 秒内没等到 running 的卡死恢复：
   * 不换会话 id，下一轮还能沿用已有上下文。
   */
  suspend fun cancelSession(sessionId: String): Boolean {
    if (!started.get() || initResult == null) return false
    val dshId = dshSessionIds[sessionId] ?: return false
    return try {
      val params = buildJsonObject { put("sessionId", dshId) }
      request("session/cancel", params, PROMPT_TIMEOUT_MS)["cancelled"]?.jsonPrimitive?.booleanOrNull == true
    } catch (timeout: TimeoutCancellationException) {
      Log.w(TAG, "session cancel timed out", timeout)
      false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Exception,
    ) {
      Log.w(TAG, "session cancel failed", error)
      false
    }
  }

  private suspend fun submitPrompt(sessionId: String, text: String, worldRequestId: String? = null): String {
    val dshId = ensureSession(sessionId)
    val params =
      buildJsonObject {
        put("sessionId", dshId)
        if (worldRequestId != null) put("world_request_id", worldRequestId)
        put(
          "contentBlocks",
          buildJsonArray {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", text)
              },
            )
          },
        )
      }
    val result = try {
      request("session/prompt", params, PROMPT_TIMEOUT_MS)
    } catch (
      // Any failed SDK submission must retain cancellation identity, including transport failures.
      @Suppress("TooGenericExceptionCaught") error: Exception,
    ) {
      worldRequestId?.let { WorldRuntime.cancel(it) }
      throw error
    }
    val trigger = result["messageId"]?.jsonPrimitive?.contentOrNull ?: error("session/prompt 未返回 messageId")
    worldRequestId?.let { WorldRuntime.confirmPrompt(it, dshId, trigger) }
    return trigger
  }

  /**
   * 提交任务并等到该会话回到 idle；返回助手正文与工具调用计数。
   * 会话事件可能含屏幕/聊天正文，调用方自行决定是否落日志。
   */
  suspend fun runTask(sessionId: String, text: String, timeoutMs: Long = 15 * 60_000L): TaskOutcome =
    withTimeout(timeoutMs) {
      val preparing = checkNotNull(beginTaskPreparation()) { "运行时正忙，无法提交任务" }
      val lease = try {
        appContext?.let { app ->
          if (PersonaStore.activateWorldCast(app, null)) reloadForPreparation(app, preparing)
        }
        checkNotNull(beginTask(preparing)) { "运行时正忙，无法提交任务" }
      } finally {
        endTaskPreparation(preparing)
      }
      try {
        val assistant = StringBuilder()
        var toolCalls = 0
        var sawRunning = false
        val turnErrors = mutableListOf<String>()
        val completed = CompletableDeferred<TaskOutcome>()

        // 只等「有任意订阅者」会在别的收集器已订阅时过早放行，把第一个 running 状态漏掉；
        // 记下 baseline，等这个收集器真正订阅上再提交。
        val subscribersBefore = notificationSubscribers()
        val collector =
          CoroutineScope(Dispatchers.Default).launch {
            dshEvents.collect { event ->
              if (event.sessionId != sessionId) return@collect
              when (event) {
                // 增量只给界面看；调试任务的字符数统计以步进成品为准，免得重复计数
                is DshEvent.StreamDelta -> Unit

                is DshEvent.AssistantMessage -> assistant.append(event.text)

                is DshEvent.ToolCall -> toolCalls++

                is DshEvent.TurnEnded -> event.error?.let(turnErrors::add)

                is DshEvent.Status ->
                  if (event.running) {
                    sawRunning = true
                  } else if (sawRunning && !completed.isCompleted) {
                    completed.complete(TaskOutcome(assistant.toString(), toolCalls, turnErrors.toList()))
                  }

                is DshEvent.ToolResult -> Unit
              }
            }
          }

        try {
          // 等这个收集器真正订阅上再提交，避免丢掉第一个状态通知。
          awaitMoreSubscribers(subscribersBefore)
          prompt(sessionId, text)
          completed.await()
        } finally {
          collector.cancel()
        }
      } finally {
        endTask(lease)
      }
    }

  /** 给 UI/调试用的稳定会话 id（同一 App 会话复用）。 */
  fun persistentSessionId(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_SESSION, null)
    if (existing != null) return existing
    val created = "zoneu-" + UUID.randomUUID()
    prefs.edit().putString(KEY_SESSION, created).apply()
    return created
  }

  // --- JSON-RPC over stdio ---

  override fun onLine(line: String) {
    val frame = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
    val method = frame["method"]?.jsonPrimitive?.contentOrNull
    val id = (frame["id"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    if (method == null && id != null) {
      Log.i(TAG, "rpc <- response #$id")
      pending.remove(id)?.complete(frame)
      return
    }
    if (method != null) {
      // token 流是**数据**不是协议事件：逐帧打 I 日志会在一个 30 秒任务里产生几千行，
      // 把 logcat 环形缓冲区冲掉（审计标记 ZoneuAudit 就是这么丢的）。
      if (method != "session.event" && method != "session.stream") Log.i(TAG, "rpc <- $method")
      _notifications.tryEmit(frame)
    }
  }

  private suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
    val id = nextId.getAndIncrement()
    val deferred = CompletableDeferred<JsonObject>()
    pending[id] = deferred
    val frame =
      buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
      }
    val payload = (frame.toString() + "\n").toByteArray(Charsets.UTF_8)
    if (!writeStdin(payload)) {
      pending.remove(id)
      error("Node stdin 不可写（运行时未启动？）")
    }
    Log.i(TAG, "rpc -> $method #$id")
    return try {
      val response = withTimeout(timeoutMs) { deferred.await() }
      val errorField = response["error"]
      if (errorField != null && errorField !is JsonNull) error("JSON-RPC error: $errorField")
      response["result"]?.jsonObject ?: JsonObject(emptyMap())
    } finally {
      pending.remove(id)
    }
  }

  private fun installDshRuntime(app: Context): File {
    val dir = File(app.filesDir, DIR_DSH)
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val lastUpdate = apkLastUpdate(app)
    if (prefs.getLong(KEY_DSH_UPDATE, -1L) == lastUpdate && File(dir, "launcher.mjs").isFile) return dir

    if (dir.exists()) dir.deleteRecursively()
    dir.mkdirs()
    val startedAt = System.currentTimeMillis()
    var entries = 0L
    app.assets.open(ASSET_DSH).use { raw ->
      ZipInputStream(BufferedInputStream(raw, 1 shl 16)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          val target = zipEntryTarget(dir, entry.name)
          if (entry.isDirectory) {
            target.mkdirs()
          } else {
            target.parentFile?.mkdirs()
            target.outputStream().use { zip.copyTo(it) }
          }
          zip.closeEntry()
          entry = zip.nextEntry
          entries++
        }
      }
    }
    prefs.edit().putLong(KEY_DSH_UPDATE, lastUpdate).apply()
    Log.i(TAG, "dsh runtime extracted: $entries entries in ${System.currentTimeMillis() - startedAt}ms")
    return dir
  }

  private fun apkLastUpdate(app: Context): Long =
    runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)

  /** Android 上 Node 必需的环境（见 docs/dsh-android.md 的启动约束）。 */
  private fun applyEnvironment(app: Context) {
    val files = app.filesDir.absolutePath
    val cache = app.cacheDir.absolutePath
    File(app.cacheDir, "node-compile-cache").mkdirs()
    Os.setenv("TMPDIR", cache, true)
    Os.setenv("HOME", files, true)
    Os.setenv("NODE_COMPILE_CACHE", "$cache/node-compile-cache", true)
    Os.setenv("NODE_COMPILE_CACHE_PORTABLE", "1", true)
    Os.setenv("NODE_OPTIONS", "--max-old-space-size-percentage=25", true)
  }

  /** 每个提供商各自的 key env 名；重载时按它切换当前生效的 key。 */
  private fun keyEnvName(preset: LlmPreset): String =
    "ZONEU_LLM_KEY_" + preset.id.uppercase().replace('-', '_')

  /**
   * 搜索 provider 该从哪个 env 读密钥。
   *
   * 没有可用搜索端点的提供商（`LlmPreset.Endpoint.search == null`）返回一个**解析不到的
   * 变量名**：seam 判断提供方可用性的办法就是「密钥在不在」，于是 `web_search` 会以
   * `WEB_PROVIDER_UNAVAILABLE` 失败 —— 与连接器「没配就回 NOT_CONNECTED」同一条路数。
   * 不这么做的话，它会拿聊天那把 key 去打 profile 里的兜底端点（api.deepseek.com），
   * 报出来是 401，看着像密钥填错了。
   */
  private fun webSearchKeyEnv(preset: LlmPreset): String =
    if (preset.endpoint.search == null) "ZONEU_WEB_SEARCH_UNSUPPORTED" else "ZONEU_LLM_KEY"

  /**
   * 写重载用的**非密配置**（env 名 → 值）。密钥不进来：只写一个指向预置 key env 的引用。
   * launcher 在每次 mount 前读它并覆盖 process.env；人格与端点配置在同一次重载中生效。
   */
  private fun writeRuntimeConfig(app: Context, config: AgentConfig) {
    val home = File(app.filesDir, "dsh-home").apply { mkdirs() }
    val payload =
      buildJsonObject {
        put("ZONEU_LLM_API", config.api)
        put("ZONEU_LLM_BASE_URL", config.baseUrl)
        put("ZONEU_LLM_MODEL", config.model)
        put(
          "ZONEU_LLM_MODELS",
          llmModelsJson(
            modelId = config.model,
            supportsImage = ModelModalities.supportsImage(app, config.preset.id, config.model),
          ),
        )
        put(
          "ZONEU_LLM_THINKING_FORMAT",
          if (config.api == "openai-completions") config.preset.thinkingFormat.orEmpty() else "",
        )
        put("ZONEU_LLM_ZEN", if (config.preset.zenSessionHeader) "1" else "")
        put("ZONEU_LLM_KEY_FROM", if (config.apiKey.isBlank()) EMPTY_KEY_ENV else keyEnvName(config.preset))
        put("ZONEU_WEB_SEARCH_BASE_URL", config.preset.endpoint.search?.baseUrl.orEmpty())
        put("ZONEU_WEB_SEARCH_MODEL", config.preset.endpoint.search?.model.orEmpty())
        put("ZONEU_WEB_SEARCH_KEY_ENV", webSearchKeyEnv(config.preset))
        put("ZONEU_PERSONA_PREFIX", PersonaStore.compose(app))
      }
    File(home, "runtime-config.json").writeText(payload.toString())
  }

  /** 同步所有 provider 的密钥槽；空值也必须覆盖旧环境，确保清除/停用立即生效。 */
  private fun refreshKeyEnvironment(app: Context, config: AgentConfig) {
    LlmPreset.entries.forEach { preset ->
      Os.setenv(keyEnvName(preset), requestApiKey(app, preset), true)
    }
    Os.setenv(EMPTY_KEY_ENV, "", true)
    Os.setenv("ZONEU_LLM_KEY", config.apiKey, true)
  }

  /**
   * dsh 专用 env：DSH_HOME、LLM 端点与设备桥（密钥不落日志）。
   *
   * 这些 env 在启动和 reload 前都从 Keystore 刷新。之后的配置切换用 [reload]：launcher 会重新 mount，
   * 并在 mount 前用 `runtime-config.json` 覆盖 process.env（非密配置）。密钥不落盘 ——
   * 每个提供商的 key 在这里预置成 `ZONEU_LLM_KEY_<ID>`，重载时只换引用。
   */
  private fun applyDshEnvironment(app: Context) {
    val home = File(app.filesDir, "dsh-home").apply { mkdirs() }
    val config = AgentConfigStore.current(app)
    Os.setenv("DSH_HOME", home.absolutePath, true)
    Os.setenv("ZONEU_WORKDIR", home.absolutePath, true)
    // 预置每个提供商的 key：启动和 reload 都会从 Keystore 重新读取，避免旧值残留。
    refreshKeyEnvironment(app, config)
    // 端点、调用风格、模型三者必须成套送过去：profile 那边的 provider 就是按这三个字段
    // 组出来的（见 tools/dsh-phone/profile/cordis.patch.yml）。缺一个就会拿 zen 的端点配
    // OpenAI 的模型，报出来的错还像是网络问题。
    Os.setenv("ZONEU_LLM_API", config.api, true)
    Os.setenv("ZONEU_LLM_MODEL", config.model, true)
    // 模型清单只声明当前选中的那一个：路由键 zoneu 不是 pi-ai 目录里的 provider，
    // 未声明的 id 会以 UNKNOWN_MODEL 失败（不是退化成纯文本）。
    // 模态与设置页徽章共用同一份索引（pi-ai 目录 → 跨 provider 一致条目 → 界面目录 → text）。
    Os.setenv(
      "ZONEU_LLM_MODELS",
      llmModelsJson(
        modelId = config.model,
        supportsImage = ModelModalities.supportsImage(app, config.preset.id, config.model),
      ),
      true,
    )
    // thinkingFormat 只对 openai-completions 有意义；openai-responses 自带推理块，
    // 带着它会触发 pi-ai 的 compat 协议校验（整条路由 catalogError）。
    Os.setenv(
      "ZONEU_LLM_THINKING_FORMAT",
      if (config.api == "openai-completions") config.preset.thinkingFormat.orEmpty() else "",
      true,
    )
    // 只有 opencode zen 需要这个头（zen 的硬要求）；别的服务商带上它没有意义。
    Os.setenv("ZONEU_LLM_ZEN", if (config.preset.zenSessionHeader) "1" else "", true)
    // profile 里的 baseURL 读它（见 tools/dsh-phone/profile/cordis.patch.yml）。
    Os.setenv("ZONEU_LLM_BASE_URL", config.baseUrl, true)
    Os.setenv("ZONEU_LLM_SESSION", persistentSessionId(app), true)
    Os.setenv("ZONEU_PERSONA_PREFIX", PersonaStore.compose(app), true)
    // 联网搜索（模型可见的 web_search 工具）：dsh 自带的 web seam + dsh-web-search-deepseek
    // provider（Anthropic 兼容 Messages + 原生 web_search 服务器工具）。端点与模型按当前
    // 提供商注入 —— 注意这与聊天用的 api / baseUrl 是**两套**，不能互相推（zen 聊天走
    // openai-completions，搜索走 /messages）。见 docs/web-search.md。
    Os.setenv("ZONEU_WEB_SEARCH_BASE_URL", config.preset.endpoint.search?.baseUrl.orEmpty(), true)
    Os.setenv("ZONEU_WEB_SEARCH_MODEL", config.preset.endpoint.search?.model.orEmpty(), true)
    Os.setenv("ZONEU_WEB_SEARCH_KEY_ENV", webSearchKeyEnv(config.preset), true)
    // 模型的 glob / grep（dsh-tool-fs-search）要 exec 的 ripgrep。它没有 android 产物，
    // 我们交叉编译了一份（.github/workflows/rg.yml），经 tools/fetch-rg.ps1 与
    // bundle-dsh.ps1 打进运行时 zip，解压后在 files/dsh-runtime/rg 上加了可执行位。
    // 顶掉真 @vscode/ripgrep 的桩包读这个变量（tools/dsh-phone/ripgrep-stub）。
    //
    // **不要改用 jniLibs / nativeLibraryDir**：现代 AGP 默认 extractNativeLibs=false，
    // 原生库直接从 APK mmap，那个目录下没有文件可 exec（实测 `lib/arm64/` 是空的）。
    Os.setenv("ZONEU_RG_PATH", File(app.applicationInfo.nativeLibraryDir, "librg.so").absolutePath, true)
    // 这个变量同时是 dsh 的沙箱模式。手机 profile 没有挂沙箱，所以它在这里的实际作用是
    // 让 dsh-base 的默认表达式把审批策略读成 `never` —— 手机上的 `ask` 由 profile 里
    // `- id: approval` + `config.policy: ask` 单独覆盖（见 docs/approvals.md）。
    Os.setenv("DSH_PERMISSION_MODE", "danger-full-access", true)
    Os.setenv("ANDROID_BRIDGE_URL", DeviceBridgeServer.start(app), true)
    Os.setenv("ANDROID_BRIDGE_TOKEN", DeviceBridgeServer.authToken(), true)
    // 内嵌 proot + Debian 就绪：插件看到这个变量才把 run_command 注册进工具表。
    // 命令走同一个本地桥（`POST /action` + `run_command`），不另开端口、不另发 token ——
    // 那条桥本来就只监听 127.0.0.1 且 token 每进程随机（见 DeviceBridgeServer）。
    Os.setenv("ZONEU_PROOT", "1", true)
    // 连接器桥是**另一个**本地服务（理由见 ConnectorBridgeServer 的类注释）：它只发短命的
    // access token，不代理云端 API 请求。没配连接器时它照样起着，只是每次调用都回
    // NOT_CONNECTED —— 「工具在、但会如实报错」比「工具随配置忽隐忽现」对模型更可预测。
    Os.setenv("ZONEU_CONNECTOR_BRIDGE_URL", ConnectorBridgeServer.start(app), true)
    Os.setenv("ZONEU_CONNECTOR_BRIDGE_TOKEN", ConnectorBridgeServer.authToken(), true)
    // 第三个桥：`ask_user_question` 的问题进 UI、答案回插件（见 UserQuestionBridgeServer 的
    // 类注释）。它同样只监听 127.0.0.1 + 每进程随机 token。**没配这个桥时插件会让路**，
    // 问题交给下一个 answerer —— 而不是编一个答案。
    Os.setenv("ZONEU_QUESTION_BRIDGE_URL", UserQuestionBridgeServer.start(), true)
    Os.setenv("ZONEU_QUESTION_BRIDGE_TOKEN", UserQuestionBridgeServer.authToken(), true)
    // 第四个桥：审批（dsh 的 `approval/request`）进 UI、决定回插件（见 ApprovalBridgeServer
    // 的类注释）。与提问桥同构：只监听 127.0.0.1 + 每进程随机 token。
    // **桥不可用时插件回 `unavailable`，不 `next()`**：api-remotes 也在同一个 waterfall 上，
    // 它等的是浏览器回答；让路会把整轮任务挂住。fail closed 比挂起诚实（docs/approvals.md）。
    Os.setenv("ZONEU_APPROVAL_BRIDGE_URL", ApprovalBridgeServer.start(app), true)
    Os.setenv("ZONEU_APPROVAL_BRIDGE_TOKEN", ApprovalBridgeServer.authToken(), true)
    // 第一次 mount 用的非密配置；重载时会重写这个文件。
    writeRuntimeConfig(app, config)
  }

  private external fun prepareStdio(): Boolean

  private external fun startNodeWithArguments(arguments: Array<String>): Int

  private external fun setLineListener(listener: NodeLineListener)

  private external fun writeStdin(data: ByteArray): Boolean
}

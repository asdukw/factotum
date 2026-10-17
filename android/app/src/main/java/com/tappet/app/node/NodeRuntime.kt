package com.tappet.app.node

import android.content.Context
import android.system.Os
import android.util.Log
import com.tappet.app.agent.AgentConfigStore
import com.tappet.app.bridge.DeviceBridgeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
  private const val TAG = "TappetNode"
  private const val ASSET_DSH = "dsh-runtime.zip"
  private const val DIR_DSH = "dsh-runtime"
  private const val PREFS = "tappet_node"
  private const val KEY_DSH_UPDATE = "dsh_last_update"
  private const val KEY_SESSION = "llm_session"

  /**
   * profile 里 llm-pi-ai 注册的 provider 路由名。
   *
   * **必须与 tools/dsh-phone/profile/cordis.patch.yml 里的 provider 键逐字相同** —— 对不上时
   * dsh 报的是 `no adapter registered for provider "xxx"`，看着像运行时缺插件，其实是这里没跟着改。
   */
  internal const val PROVIDER = "tappet"

  private const val INITIALIZE_TIMEOUT_MS = 180_000L
  private const val PROMPT_TIMEOUT_MS = 30_000L

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

  /**
   * 解析后的 dsh 领域事件流（UI 与调试可以各自收集）。
   *
   * dsh 侧会话 id 是「调用方 id + 本次运行时标记」（见 [dshSessionId]），这里映射回调用方传入的 id，
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
   * 调用方 sessionId → 本次进程内稳定的 dsh sessionId。
   *
   * 同一进程内复用（保持多轮上下文），进程重启后自动换新，避免撞上 DSH_HOME 里已存在的同名会话。
   */
  private fun dshSessionId(sessionId: String): String =
    dshSessionIds.computeIfAbsent(sessionId) { caller ->
      "$caller-$runtimeTag".also { callerSessionIds[it] = caller }
    }

  /** 等到至少有一个事件收集者订阅（避免提交任务时丢掉第一个 running 通知）。 */
  suspend fun awaitEventSubscribers() {
    _notifications.subscriptionCount.first { it > 0 }
  }

  private val started = AtomicBoolean(false)
  private val nextId = AtomicLong(1)

  /** 本次进程的 dsh 会话命名空间：dsh 会话持久化在磁盘上，进程重启后再用同名 id create 会 already exists。 */
  private val runtimeTag = UUID.randomUUID().toString().take(8)
  private val dshSessionIds = ConcurrentHashMap<String, String>()
  private val callerSessionIds = ConcurrentHashMap<String, String>()
  private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
  private val initMutex = Mutex()

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
      .apply { name = "tappet-node" }
      .start()
  }

  /** SDK 握手；成功后 status 变 Ready，之后可反复 prompt。 */
  suspend fun initialize(context: Context): JsonObject {
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

  /** 提交一条用户任务（同一 sessionId 复用会话）；返回入队消息 id。 */
  suspend fun prompt(sessionId: String, text: String): String {
    val dshId = dshSessionId(sessionId)
    val params =
      buildJsonObject {
        put("sessionId", dshId)
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
    val result = request("session/prompt", params, PROMPT_TIMEOUT_MS)
    return result["messageId"]?.jsonPrimitive?.contentOrNull ?: error("session/prompt 未返回 messageId")
  }

  /**
   * 提交任务并等到该会话回到 idle；返回助手正文与工具调用计数。
   * 会话事件可能含屏幕/聊天正文，调用方自行决定是否落日志。
   */
  suspend fun runTask(sessionId: String, text: String, timeoutMs: Long = 15 * 60_000L): TaskOutcome =
    withTimeout(timeoutMs) {
      val assistant = StringBuilder()
      var toolCalls = 0
      var sawRunning = false
      val turnErrors = mutableListOf<String>()
      val completed = CompletableDeferred<TaskOutcome>()

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
        // 等收集器真正订阅上再提交，避免丢掉第一个状态通知。
        awaitEventSubscribers()
        prompt(sessionId, text)
        completed.await()
      } finally {
        collector.cancel()
      }
    }

  /** 给 UI/调试用的稳定会话 id（同一 App 会话复用）。 */
  fun persistentSessionId(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_SESSION, null)
    if (existing != null) return existing
    val created = "tappet-" + UUID.randomUUID()
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
      if (method != "session.event") Log.i(TAG, "rpc <- $method")
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
          val target = File(dir, entry.name)
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

  /** Android 上 Node 必需的环境（见 docs/dsh-android.md §1 EMBEDDING 约定）。 */
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

  /**
   * dsh 专用 env：DSH_HOME、LLM 端点与设备桥（密钥不落日志）。
   *
   * 这里是**一次性**的：env 只在 Node 进程启动时设一遍，dsh profile 也只在 mount 时读
   * `process.env`。所以设置页改完配置要**下次启动**才生效 —— 不在这里重拉 Node 进程，
   * 因为内嵌 Node 是宿主进程内的单实例，`process.exit()` / SDK `shutdown` 会把整个 App
   * 带崩（见 AGENTS.md「常见坑」）。
   */
  private fun applyDshEnvironment(app: Context) {
    val home = File(app.filesDir, "dsh-home").apply { mkdirs() }
    val config = AgentConfigStore.current(app)
    Os.setenv("DSH_HOME", home.absolutePath, true)
    Os.setenv("TAPPET_WORKDIR", home.absolutePath, true)
    Os.setenv("TAPPET_LLM_KEY", config.apiKey, true)
    // 端点、调用风格、模型三者必须成套送过去：profile 那边的 provider 就是按这三个字段
    // 组出来的（见 tools/dsh-phone/profile/cordis.patch.yml）。缺一个就会拿 zen 的端点配
    // OpenAI 的模型，报出来的错还像是网络问题。
    Os.setenv("TAPPET_LLM_API", config.api, true)
    Os.setenv("TAPPET_LLM_MODEL", config.model, true)
    Os.setenv("TAPPET_LLM_THINKING_FORMAT", config.preset.thinkingFormat.orEmpty(), true)
    // 只有 opencode zen 需要这个头（zen 的硬要求）；别的服务商带上它没有意义。
    Os.setenv("TAPPET_LLM_ZEN", if (config.preset.zenSessionHeader) "1" else "", true)
    // profile 里的 baseURL 读它（见 tools/dsh-phone/profile/cordis.patch.yml）。
    Os.setenv("TAPPET_LLM_BASE_URL", config.baseUrl, true)
    Os.setenv("TAPPET_LLM_SESSION", persistentSessionId(app), true)
    Os.setenv("DSH_PERMISSION_MODE", "danger-full-access", true)
    Os.setenv("ANDROID_BRIDGE_URL", DeviceBridgeServer.start(), true)
    Os.setenv("ANDROID_BRIDGE_TOKEN", DeviceBridgeServer.authToken(), true)
  }

  private external fun prepareStdio(): Boolean

  private external fun startNodeWithArguments(arguments: Array<String>): Int

  private external fun setLineListener(listener: NodeLineListener)

  private external fun writeStdin(data: ByteArray): Boolean
}

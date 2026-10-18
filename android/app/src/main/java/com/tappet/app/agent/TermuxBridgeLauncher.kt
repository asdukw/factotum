package com.tappet.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 让手机上的 Linux 桥自己起来：Tappet 生成 token，经 Termux 的 RUN_COMMAND 把 `bridge.py` 拉起来。
 *
 * **为什么值得多这一段**：桥的 token 是凭据（拿到它就能在 Debian 里以 root 执行命令），
 * 但 Tappet 读不到 Termux 的私有目录，没法自动发现 setup.sh 生成的那个 —— 让用户手抄
 * 48 个字符，是把一个能用代码解决的同步问题推给人。改成「Tappet 自己生成、自己送过去」，
 * 闸门保住了，手填这一步也没了。
 *
 * 前提（由 `tools/termux/setup.sh` 一次装好）：Termux 已安装、`allow-external-apps=true`、
 * `~/bridge.py` 在位。权限 `com.termux.permission.RUN_COMMAND` 是 Termux 定义的 **dangerous**
 * 权限，必须在界面上让用户点一次同意 —— 这一条绕不过去，也**不该**绕：它等于「允许 Tappet
 * 在 Termux 里执行命令」，是个真实的授权决定。
 */
object TermuxBridgeLauncher {
  private const val TAG = "TappetTermux"

  const val TERMUX_PACKAGE = "com.termux"
  const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

  private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
  private const val SERVICE_CLASS = "$TERMUX_PACKAGE.app.RunCommandService"
  private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
  private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
  private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
  private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

  private const val TERMUX_HOME = "/data/data/com.termux/files/home"
  private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
  private const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"

  /**
   * 启动脚本。[startScript] 里 `$1` 是 token（经 argv 传，不拼进脚本文本）。
   *
   * 四件必须自己做的事，都是实测踩出来的：
   * 1. **摆正 PATH**。RUN_COMMAND 起的是**非交互 shell**，环境里不保证有 Termux 的
   *    `$PREFIX/bin`（实测：连 `PREFIX` 都是空的）。
   * 2. **输出重定向到文件**。RUN_COMMAND 的 stdout/stderr 是没人读的管道，任何 traceback
   *    都会消失，现象只剩「桥没起来」——这一条害我们查了三轮。
   * 3. **用绝对路径调 python**，并显式 export HOME（同一个理由）。
   * 4. **不要用 `pkill -f`**。脚本是被 `bash -lc <脚本文本>` 拉起来的，脚本文本**就在 argv 里** ——
   *    于是任何写在正文里的模式都会匹配到脚本自己，pkill 把脚本自己杀掉：token 写完了、
   *    后面的行一行没跑、日志也没被碰过，外面只看到「桥没起来」。实测就是这么被坑的。
   *    改成扫 `/proc` 比命令行（并跳过自己与父进程），语义精确且不会自杀。
   */
  internal fun startScript(): String =
    """
    umask 077
    export HOME=$TERMUX_HOME
    export PATH=$TERMUX_PREFIX/bin:/system/bin
    printf %s "${'$'}1" > "${'$'}HOME/.tappet-bridge-token"
    if [ -f "${'$'}HOME/.tappet-bridge.pid" ]; then
      kill "$(cat "${'$'}HOME/.tappet-bridge.pid")" 2>/dev/null
      sleep 1
    fi
    for proc in /proc/[0-9]*; do
      case "${'$'}proc" in "/proc/${'$'}${'$'}"|"/proc/${'$'}PPID") continue ;; esac
      case "$(cat "${'$'}proc/cmdline" 2>/dev/null | tr '\0' ' ')" in
        "python ${'$'}HOME/bridge.py "*) kill "${'$'}{proc#/proc/}" 2>/dev/null ;;
      esac
    done
    sleep 1
    exec $TERMUX_PREFIX/bin/python "${'$'}HOME/bridge.py" >> "${'$'}HOME/bridge.log" 2>&1
    """.trimIndent()

  /** 启动命令的参数表：`bash -lc <script> <$0> <$1=token>`。 */
  internal fun startArguments(token: String): Array<String> =
    arrayOf("-lc", startScript(), "tappet-bridge", token)

  enum class Status {
    /** 桥已经在跑，而且认的就是我们这把 token。 */
    RUNNING,

    /** 刚刚把它拉起来了。 */
    STARTED,

    /** 手机上没有 Termux。 */
    NO_TERMUX,

    /** Termux 在，但没拿到 RUN_COMMAND 权限（用户没点同意）。 */
    NO_PERMISSION,

    /**
     * 端口在监听、但连不上（连接超时）—— ColorOS 把后台的 Termux 连同桥一起冻了。
     *
     * 这一条要和 [FAILED] 分开：它**不是配置问题**，重试多少次都一样，用户该做的是把 Termux
     * 拉到前台（或放开后台运行权限），而不是去查 allow-external-apps。
     */
    FROZEN,

    /** 发了启动请求但没等到桥起来（Termux 没开 allow-external-apps、bridge.py 不在、或起服务被系统拦）。 */
    FAILED,
  }

  fun isTermuxInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) }.isSuccess

  fun hasPermission(context: Context): Boolean =
    context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

  /**
   * 保证桥可用。**可以重复调用**：已经在跑（且 token 对）就什么都不做。
   *
   * 三种「已经在跑」要分开：端口没人 → 启动；`/health` 通但 `/ping` 401 → 是**别人**起的桥
   * （比如用户手动跑过 setup.sh，token 不一样），此时必须按 pidfile 换掉它 —— 否则 Tappet 的
   * 每条命令都 401，现象看起来像「桥坏了」。
   */
  suspend fun ensureStarted(context: Context, url: String, token: String): Status = withContext(Dispatchers.IO) {
    if (probe("$url/ping", token) == Probe.OK) return@withContext Status.RUNNING
    if (!isTermuxInstalled(context)) return@withContext Status.NO_TERMUX
    if (!hasPermission(context)) return@withContext Status.NO_PERMISSION
    if (!sendStartCommand(context, token)) return@withContext Status.FAILED
    // 轮询等它起来。窗口给到 ~15 秒：Termux 被冻住时，投递这次 startService 本身就要先把它唤醒。
    repeat(POLL_ATTEMPTS) {
      delay(POLL_INTERVAL_MS)
      if (probe("$url/ping", token) == Probe.OK) return@withContext Status.STARTED
    }
    // 没起来，但**要分清是哪一种**：连接超时 = 端口在、进程被冻；连不上 = 根本没人监听。
    // 前者重试无用，用户该去放开 Termux 的后台运行；后者才是配置问题。
    if (probe("$url/ping", token) == Probe.TIMEOUT) Status.FROZEN else Status.FAILED
  }

  private fun sendStartCommand(context: Context, token: String): Boolean =
    try {
      val intent =
        Intent(ACTION_RUN_COMMAND).apply {
          setClassName(TERMUX_PACKAGE, SERVICE_CLASS)
          putExtra(EXTRA_PATH, TERMUX_BASH)
          putExtra(EXTRA_ARGUMENTS, startArguments(token))
          putExtra(EXTRA_WORKDIR, TERMUX_HOME)
          putExtra(EXTRA_BACKGROUND, true)
        }
      context.startService(intent)
      true
    } catch (error: SecurityException) {
      // 权限声明没生效 / Termux 的 exported 校验没过。
      Log.w(TAG, "拉起 Termux 桥被拒：${error.javaClass.simpleName}: ${error.message}")
      false
    } catch (error: IllegalStateException) {
      // Android 12+ 的后台起服务限制：Tappet 不在前台时走到这里。
      Log.w(TAG, "拉起 Termux 桥失败（后台起服务被拦）：${error.message}")
      false
    } catch (error: IllegalArgumentException) {
      // Termux 版本不认这个 action / 组件名。
      Log.w(TAG, "拉起 Termux 桥失败（组件不匹配）：${error.message}")
      false
    }

  private enum class Probe { OK, UNAUTHORIZED, TIMEOUT, DOWN }

  /** `/ping` 要带 token：它同时回答「有没有人」和「是不是我的人」。 */
  private fun probe(target: String, token: String): Probe =
    try {
      val connection = (URL(target).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("X-Tappet-Token", token)
        connectTimeout = PROBE_TIMEOUT_MS
        readTimeout = PROBE_TIMEOUT_MS
      }
      try {
        when (connection.responseCode) {
          200 -> Probe.OK
          401 -> Probe.UNAUTHORIZED
          else -> Probe.DOWN
        }
      } finally {
        connection.disconnect()
      }
    } catch (_: SocketTimeoutException) {
      // **连不上但端口在**：进程被冻结时，内核的 accept 队列不再被消费，SYN 都收不到回音。
      // 这个区别很重要 —— 它把「被系统冻了」和「没起来」分成两种不同的处置。
      Probe.TIMEOUT
    } catch (_: IOException) {
      // 连接被拒（没人监听）/ 解析失败 —— 对调用方都是「桥没在跑」。
      Probe.DOWN
    } catch (_: IllegalArgumentException) {
      // 地址本身不合法（用户手填的 URL 写坏了）。
      Probe.DOWN
    }

  private const val PROBE_TIMEOUT_MS = 1500

  // 窗口给到 ~45 秒：ColorOS 会把后台的 Termux **整个杀掉**（不只是冻结），此时 startService
  // 要先冷启动 Termux（实测光是 `am start` 拉起它就要 ~6 秒，还要加上投递与 bootstrap）。
  private const val POLL_ATTEMPTS = 45
  private const val POLL_INTERVAL_MS = 1000L
}

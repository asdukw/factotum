package com.zoneu.app.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zoneu.app.accessibility.ZoneuAccessibilityService
import com.zoneu.app.proot.ProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 仅 debug 构建存在：绕过 UI 触发内嵌 dsh 运行时，便于真机自动化。
 *
 * 广播本身立即返回（任务在进程内异步跑）；先拉起 App/常驻运行时再广播，
 * 任务过程中不要让系统杀掉进程。
 *
 * ```
 * # 启动运行时 + SDK initialize
 * adb shell am broadcast -n com.zoneu.app/.node.NodeDebugReceiver \
 *   -a com.zoneu.app.debug.NODE --es op START
 *
 * # 跑一次任务（可连续多次，无需 force-stop）；--ez logPayload true 才打印助手正文
 * adb shell "am broadcast -n com.zoneu.app/.node.NodeDebugReceiver \
 *   -a com.zoneu.app.debug.NODE --es op TASK --es task '任务文本'"
 * # 需要干净会话时加 --es session <id>
 * ```
 */
class NodeDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val op = intent.getStringExtra(EXTRA_OP) ?: return
    val app = context.applicationContext
    when (op) {
      "START" -> {
        CoroutineScope(Dispatchers.IO).launch {
          try {
            bestEffortForegroundService(app)
            val info = NodeRuntime.initialize(app)
            Log.i(TAG, "runtime ready: ${info["serverInfo"]}")
          } catch (throwable: Throwable) {
            Log.e(TAG, "runtime start failed: ${throwable.message}")
          }
        }
      }

      "RELOAD" -> {
        CoroutineScope(Dispatchers.IO).launch {
          try {
            val info = NodeRuntime.reload(app)
            Log.i(TAG, "runtime reloaded: ${info["serverInfo"]}")
          } catch (throwable: Throwable) {
            Log.e(TAG, "runtime reload failed: ${throwable.message}")
          }
        }
      }

      // 内嵌 proot + Debian 的冒烟测试：解 first-run rootfs，跑一条 guest 命令。
      // 默认打印 debian 版本 / 架构 / 身份，证明「可写目录里的 ELF 能不能 exec」这个假设。
      // 输出是 guest 的 stdout，和 TASK 一样只在 --ez logPayload true 时打印。
      "PROOT" -> {
        val command = intent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
          ?: "cat /etc/debian_version; uname -m; id; ls -ld /bin/sh"
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        CoroutineScope(Dispatchers.IO).launch {
          try {
            bestEffortForegroundService(app)
            val result = ProotRuntime.run(app, command)
            Log.i(TAG, "proot done ok=${result.ok} exit=${result.exitCode} timedOut=${result.timedOut}")
            if (logPayload) {
              Log.i(TAG, "proot stdout: ${result.stdout.take(MAX_LOGGED_CHARS)}")
              Log.i(TAG, "proot stderr: ${result.stderr.take(MAX_LOGGED_CHARS)}")
            }
          } catch (throwable: Throwable) {
            Log.e(TAG, "proot failed: ${throwable.message}")
          }
        }
      }

      "TASK" -> {
        val task = intent.getStringExtra(EXTRA_TASK)
        if (task.isNullOrBlank()) {
          Log.w(TAG, "TASK without text")
          return
        }
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        val sessionId = intent.getStringExtra(EXTRA_SESSION)?.takeIf { it.isNotBlank() }
          ?: NodeRuntime.persistentSessionId(app)
        CoroutineScope(Dispatchers.IO).launch {
          try {
            Log.i(TAG, "debug task start (${task.length} chars) session=$sessionId")
            bestEffortForegroundService(app)
            // 新任务的第一次读屏必须是全量：跨任务沿用上一个任务的快照会回一句
            // 「unchanged」，而新会话里模型看不到上一屏的内容。
            ZoneuAccessibilityService.get()?.resetScreenState()
            NodeRuntime.initialize(app)
            val outcome = NodeRuntime.runTask(sessionId, task)
            Log.i(
              TAG,
              "task done ok=${outcome.ok} tools=${outcome.toolCalls} chars=${outcome.assistantText.length} " +
                "errors=${outcome.turnErrors.size}",
            )
            if (logPayload) {
              Log.i(TAG, "assistant: ${outcome.assistantText.take(MAX_LOGGED_CHARS)}")
            }
          } catch (throwable: Throwable) {
            Log.e(TAG, "task failed: ${throwable.message}")
          } finally {
            NodeTaskService.stop(app)
          }
        }
      }

      else -> Log.w(TAG, "unknown op=$op")
    }
  }

  /**
   * 尽量先把前台服务拉起来，再干长活。
   *
   * 广播进来的进程是 cached 状态，Android 11+ 的 cached app freezer 约十秒就把它冻住；
   * 而首次 `initialize` 要解压 dsh 运行时（模拟器实测 **122 秒**），顺序反了就会冻在解压
   * 中途 —— 表现是「广播没反应」，日志只停在 `debug task start`。界面路径（`DshStreamer`）
   * 本来就是先起服务后 initialize。
   *
   * **但广播不在前台的 FGS 白名单里**（AlarmManager 那种才有），App 不在前台时
   * `startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException` ——
   * 初版没包这一层，`START` 直接死在第一行。所以是尽力而为：起得来就受保护，
   * 起不来就退回原来的行为，不把整条任务带崩。
   */
  private fun bestEffortForegroundService(app: Context) {
    runCatching { NodeTaskService.start(app) }
      .onFailure { Log.i(TAG, "foreground service not allowed from broadcast: ${it.javaClass.simpleName}") }
  }

  companion object {
    private const val TAG = "ZoneuNode"
    private const val MAX_LOGGED_CHARS = 800

    const val ACTION = "com.zoneu.app.debug.NODE"
    const val EXTRA_OP = "op"
    const val EXTRA_TASK = "task"
    const val EXTRA_SESSION = "session"
    const val EXTRA_COMMAND = "cmd"
    const val EXTRA_LOG_PAYLOAD = "logPayload"
  }
}

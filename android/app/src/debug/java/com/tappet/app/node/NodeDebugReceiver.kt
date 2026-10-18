package com.tappet.app.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tappet.app.agent.TermuxBridgeLauncher
import com.tappet.app.agent.TermuxBridgeStore
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
 * adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver \
 *   -a com.tappet.app.debug.NODE --es op START
 *
 * # 跑一次任务（可连续多次，无需 force-stop）；--ez logPayload true 才打印助手正文
 * adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver \
 *   -a com.tappet.app.debug.NODE --es op TASK --es task '任务文本'"
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

      // 等价于设置页那个「启用」按钮：生成 token + 把 Termux 里的桥拉起来。
      // 留着它是因为**授权弹窗之外的部分都可以无 UI 验证** —— 真机上排「桥为什么没起来」时，
      // 不必每次都去点界面（授权本身仍必须用户点一次）。
      "TERMUX" -> {
        CoroutineScope(Dispatchers.IO).launch {
          val token = TermuxBridgeStore.ensureToken(app)
          val url = TermuxBridgeStore.url(app)
          val status = TermuxBridgeLauncher.ensureStarted(app, url, token)
          // 只记状态与地址，绝不记 token。
          Log.i(TAG, "termux bridge: $status url=$url")
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
            NodeRuntime.initialize(app)
            NodeTaskService.start(app)
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

  companion object {
    private const val TAG = "TappetNode"
    private const val MAX_LOGGED_CHARS = 800

    const val ACTION = "com.tappet.app.debug.NODE"
    const val EXTRA_OP = "op"
    const val EXTRA_TASK = "task"
    const val EXTRA_SESSION = "session"
    const val EXTRA_LOG_PAYLOAD = "logPayload"
  }
}

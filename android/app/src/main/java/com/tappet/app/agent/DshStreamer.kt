package com.tappet.app.agent

import android.content.Context
import android.util.Log
import com.tappet.app.accessibility.TappetAccessibilityService
import com.tappet.app.node.DshEvent
import com.tappet.app.node.NodeRuntime
import com.tappet.app.node.NodeTaskService
import com.tappet.app.progress.AgentProgressCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把 dsh 会话事件映射成聊天流事件：任务提交 →（推理）→（工具调用状态）→ 助手正文 → 完成。
 *
 * dsh 不发 token 级增量（assistant/message 是 step 级完整消息，reasoning 与 text 一起到达）；
 * 推理文本发 [AgentStreamEvent.ReasoningDelta] 供 UI 折叠展示，工具调用期间用
 * [AgentStreamEvent.Status] 给用户进度，并发出 [AgentStreamEvent.ToolCallStarted] /
 * [AgentStreamEvent.ToolCallFinished] 供 UI 画工具卡。任务期间起前台服务保活，idle 后停止。
 *
 * 注意：SDK 协议没有取消方法；用户点“停止”只是停止 UI 收集，dsh 那一轮还会在
 * 后台继续跑完。
 */
class DshStreamer(private val context: Context) : ChatStreamer {
  override fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent> = channelFlow {
    val app = context.applicationContext
    Log.i(TAG, "dsh task session=$sessionId chars=${prompt.length}")
    // 冷启动（首次解压运行时）可能要几十秒，先让用户看到一句人话，而不是空气泡。
    send(AgentStreamEvent.Status("正在启动 dsh 运行时…"))
    AgentProgressCenter.start(sessionId, prompt)
    AgentProgressCenter.step(sessionId, "启动 dsh 运行时")
    // 保活要在 initialize 之前：ColorOS 会在后台冻结进程，mount 到一半被冻就白等了。
    NodeTaskService.start(app)
    // 任务期触摸探索：隐藏控件树的 App（实测微信）只在探索开启时
    // 暴露节点；代价是这期间注入手势失效，点击/长按/滚动改走 performAction 语义动作。
    // 任务结束必须关掉，否则用户手动点击会变成「先探索再双击」。
    TappetAccessibilityService.get()?.setTouchExplorationForTask(true)
    try {
      NodeRuntime.initialize(app)
      var sawRunning = false
      val toolNames = mutableMapOf<String, String>()
      // dsh 每个 step 各发一条完整助手消息；第二次起补空行，避免多步回答粘成一句。
      var emittedText = false
      var emittedReasoning = false
      val finished = CompletableDeferred<Unit>()
      val started = CompletableDeferred<Unit>()
      val collector =
        launch(Dispatchers.Default) {
          NodeRuntime.dshEvents.collect { event ->
            if (event.sessionId != sessionId) return@collect
            when (event) {
              is DshEvent.Status -> {
                if (event.running) {
                  sawRunning = true
                  if (!started.isCompleted) started.complete(Unit)
                  AgentProgressCenter.step(sessionId, "模型正在规划")
                } else if (sawRunning && !finished.isCompleted) {
                  finished.complete(Unit)
                }
              }
              is DshEvent.ToolCall -> {
                send(AgentStreamEvent.Status("正在调用 ${event.name}…"))
                send(AgentStreamEvent.ToolCallStarted(event.callId, event.name, event.arguments))
                AgentProgressCenter.toolStarted(sessionId, event.name, event.arguments)
                event.callId?.let { toolNames[it] = event.name }
              }
              is DshEvent.ToolResult -> {
                send(AgentStreamEvent.ToolCallFinished(event.callId, ok = !event.isError, result = event.text))
                AgentProgressCenter.toolFinished(
                  sessionId = sessionId,
                  name = event.callId?.let { toolNames.remove(it) },
                  ok = !event.isError,
                  result = event.text,
                )
              }
              is DshEvent.AssistantMessage -> {
                event.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                  if (emittedReasoning) send(AgentStreamEvent.ReasoningDelta("\n\n"))
                  send(AgentStreamEvent.ReasoningDelta(reasoning))
                  emittedReasoning = true
                }
                if (event.text.isNotBlank()) {
                  AgentProgressCenter.modelOutput(sessionId, event.text)
                  if (emittedText) send(AgentStreamEvent.TextDelta("\n\n"))
                  send(AgentStreamEvent.TextDelta(event.text))
                  emittedText = true
                }
              }
              is DshEvent.TurnEnded -> Unit
            }
          }
        }

      send(AgentStreamEvent.Status("已提交，等待模型…"))
      AgentProgressCenter.step(sessionId, "正在思考", "等待模型返回下一步")
      NodeRuntime.awaitEventSubscribers()
      NodeRuntime.prompt(sessionId, prompt)
      // 会话若卡在上一轮（进程被杀留下未闭合的 turn），prompt 会一直排队不启动。
      withTimeoutOrNull(RUNNING_TIMEOUT_MS) { started.await() }
        ?: throw AgentException("任务未能启动（会话可能卡在上一轮，请新建会话或清除会话数据）")
      finished.await()
      collector.cancel()
      AgentProgressCenter.complete(sessionId)
      send(AgentStreamEvent.Completed)
    } catch (cancelled: CancellationException) {
      AgentProgressCenter.fail(sessionId, "任务已停止")
      throw cancelled
    } catch (error: Throwable) {
      AgentProgressCenter.fail(sessionId, error.message ?: error.javaClass.simpleName)
      throw error
    } finally {
      TappetAccessibilityService.get()?.setTouchExplorationForTask(false)
      NodeTaskService.stop(app)
    }
  }

  private companion object {
    private const val TAG = "TappetNode"
    private const val RUNNING_TIMEOUT_MS = 30_000L
  }
}

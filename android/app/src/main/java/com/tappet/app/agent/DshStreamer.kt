package com.tappet.app.agent

import android.content.Context
import android.util.Log
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
    // 任务期**不**开触摸探索。理由见 docs/android-accessibility.md §9：
    // ① 它一开，dispatchGesture 注入的手势就被系统当探索事件吞掉 —— 树不可读时
    //    没有节点可走 performAction，等于「只能看不能点」，而树不可读正是要走视觉的时候；
    // ② 它也救不了已经在跑的 App：那个状态是目标 App 进程启动时采样一次并缓存的。
    // 机制与 debug 广播仍留着（TappetAccessibilityService.setTouchExplorationForTask），
    // 但任务路径不再自动开 —— 手势始终可用，用户手动点击也不受影响。
    try {
      NodeRuntime.initialize(app)
      var sawRunning = false
      val toolNames = mutableMapOf<String, String>()
      // dsh 每个 step 各发一条完整助手消息；第二次起补空行，避免多步回答粘成一句。
      var emittedText = false
      var emittedReasoning = false
      // 本步是否收到过增量：收到了说明运行时补丁在生效，这一步的成品要用替换语义交给界面。
      var streamedTextThisStep = false
      var streamedReasoningThisStep = false
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

              // token 级增量：先给眼睛看。每一步的第一个增量前补空行（与成品路径一致）。
              is DshEvent.StreamDelta -> {
                event.reasoning?.let { delta ->
                  if (!streamedReasoningThisStep) {
                    if (emittedReasoning) send(AgentStreamEvent.ReasoningDelta("\n\n"))
                    streamedReasoningThisStep = true
                    emittedReasoning = true
                    send(AgentStreamEvent.ReasoningDelta(delta, startsStep = true))
                  } else {
                    send(AgentStreamEvent.ReasoningDelta(delta))
                  }
                }
                event.text?.let { delta ->
                  if (!streamedTextThisStep) {
                    if (emittedText) send(AgentStreamEvent.TextDelta("\n\n"))
                    streamedTextThisStep = true
                    emittedText = true
                    AgentProgressCenter.step(sessionId, "模型正在输出")
                    send(AgentStreamEvent.TextDelta(delta, startsStep = true))
                  } else {
                    send(AgentStreamEvent.TextDelta(delta))
                  }
                }
              }

              is DshEvent.AssistantMessage -> {
                if (streamedTextThisStep || streamedReasoningThisStep) {
                  // 增量已经显示过：用成品**替换本步**（只替换本步追加的部分，见 ChatViewModel）
                  AgentProgressCenter.modelOutput(sessionId, event.text)
                  send(
                    AgentStreamEvent.StepFinal(
                      text = event.text.takeIf { it.isNotBlank() },
                      reasoning = event.reasoning?.takeIf { it.isNotBlank() },
                    ),
                  )
                  streamedTextThisStep = false
                  streamedReasoningThisStep = false
                  return@collect
                }
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

              // 一轮以错误收尾时必须说话。以前这里是 `Unit`：运行时在模型调用之前就失败
              // （配置错、提供商 400、插件起不来）时，气泡会永远停在「正在启动 dsh 运行时…」，
              // 用户看到的是「它不理我」。错误原文交给界面，比静默有用。
              is DshEvent.TurnEnded ->
                event.error?.takeIf { it.isNotBlank() }?.let { message ->
                  Log.w(TAG, "turn ended with error: $message")
                  if (!emittedText) {
                    send(AgentStreamEvent.TextDelta("这一轮没跑起来：$message", startsStep = true))
                    emittedText = true
                  }
                }
            }
          }
        }

      // 不在气泡里塞「已提交，等待模型…」：等待期间留空（界面显示省略号）比一句会立刻被顶掉的
      // 状态文案安静；进度在系统悬浮层（AgentProgressCenter）里看。
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
    } catch (
      // 刻意的兜底：任务流里任何异常都要先落进 AgentProgressCenter 再抛，
      // 否则界面只会停在「运行中」。收窄类型等于给漏掉的异常留后门。
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      AgentProgressCenter.fail(sessionId, error.message ?: error.javaClass.simpleName)
      throw error
    } finally {
      NodeTaskService.stop(app)
    }
  }

  private companion object {
    private const val TAG = "TappetNode"
    private const val RUNNING_TIMEOUT_MS = 30_000L
  }
}

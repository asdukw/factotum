package com.tappet.app.agent

import kotlinx.coroutines.flow.Flow

/**
 * 直连 OpenAI 兼容接口的酒馆通道。
 *
 * 只做一件事：把 [TavernRequest.messages] 原样发出去。配置在**每次调用时**现读 ——
 * 用户在设置里换了提供商或 key，下一条消息就该用新的，不必重载运行时，也不必重启 App。
 *
 * 为什么不让 dsh 代发：它的会话只接受「一段话」，消息数组由它的模板决定；酒馆要的深度注入、
 * 后置指令、真重写都要求数组由我们自己排。
 */
class HttpTavernChannel(private val config: () -> AgentConfig?) : TavernChannel {
  override fun stream(request: TavernRequest): Flow<AgentStreamEvent> {
    val config = config()
      ?: return kotlinx.coroutines.flow.flow {
        throw AgentException("还没有配置模型：设置里填一个 OpenAI 兼容提供商的 key 再试")
      }
    return LlmClient(config = config, sessionId = request.sessionId).streamMessages(request.messages)
  }
}

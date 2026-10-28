package com.zoneu.app.agent

import kotlinx.coroutines.flow.Flow

/**
 * 酒馆对话通道：收一整条 messages 数组，回一串流式事件。
 *
 * 与 [ChatStreamer]（只收一段话，上下文由 dsh 定）并列存在，是「两条通道」里的第二条。
 * 抽成接口是为了单测能塞假实现，也让「这家提供商不支持直连」时能干脆地退回 dsh。
 */
fun interface TavernChannel {
  fun stream(request: TavernRequest): Flow<AgentStreamEvent>
}

/** 一次酒馆请求。会话 id 只用于日志与排查，不进请求体。 */
data class TavernRequest(val sessionId: String, val messages: List<TavernMessage>)

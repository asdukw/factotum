package com.tappet.app.agent

enum class ChatRole {
  User,
  Assistant,
}

/** 工具卡的三态：执行中 / 成功 / 失败。 */
enum class ToolCallState {
  Running,
  Ok,
  Failed,
}

/**
 * 一条工具调用的展示模型（对应 dsh 的 tool/call + tool/result 两个事件）。
 *
 * [arguments] 是模型产出的原始 JSON 字符串，界面上折叠展示；[result] 是工具回给模型的
 * 结果正文，可能很长，同样只在展开时显示。
 */
data class ToolCallView(
  val id: String,
  val name: String,
  val arguments: String,
  val result: String? = null,
  val state: ToolCallState = ToolCallState.Running,
)

/**
 * 一条聊天消息。
 *
 * 消息 [id] 与库里的 `message.id` 相同（UUIDv7），这样 UI 与库里的行是一一对应的。
 * [reasoning] 是模型的推理原文（dsh 的 reasoning block），UI 折叠展示；
 * [thinking] 表示推理还在进行中（此时没有正文）。[toolCalls] 是这一轮里模型发起的工具调用，
 * 按发生顺序排列。
 */
data class ChatMessage(
  val id: String,
  val role: ChatRole,
  val content: String = "",
  val streaming: Boolean = false,
  val thinking: Boolean = false,
  val reasoning: String? = null,
  /** 这一轮思考了多久（毫秒）。只在内存里：库里没有这一列，重载后为 null（界面显示「思考过程」）。 */
  val thinkingMs: Long? = null,
  /**
   * 这一轮思考开始的时间戳（毫秒，`System.currentTimeMillis`）。只在内存里。
   *
   * 界面用它把「思考中」的时长**实时往上走**；结算时换成 [thinkingMs] 并清空。
   * 与 [thinkingMs] 一样不入库 —— 重载后没有起点，只能显示「思考过程」。
   */
  val thinkingStartedAt: Long? = null,
  val error: String? = null,
  val toolCalls: List<ToolCallView> = emptyList(),
)

package com.tappet.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次待确认的审批请求。字段来自 dsh 的 `ApprovalRequestEvent` —— **不要加协议里没有的东西**：
 * 这个结构跨进程（插件 ↔ 桥 ↔ UI）传递，多一个字段就多一处要跟着改。
 *
 * [displayReason] 是本地化文案（dsh 给的是 `{ en, zh, ... }`）。**它可能带用户内容**
 * （例如收件人、主题）：只用于显示，绝不写日志。
 */
data class PendingApproval(
  val toolName: String,
  val callId: String? = null,
  val reason: String? = null,
  val displayReason: Map<String, String> = emptyMap(),
)

/** 封闭的审批结果，与 dsh 的 `ApprovalOutcome` 对齐。 */
enum class ApprovalDecision {
  ALLOW_ONCE,
  REJECT,
  CANCEL,
}

/**
 * 待确认审批的唯一持有者：桥登记请求并挂起，UI 给出结果后唤醒。
 *
 * 与 [UserQuestionHub] 同构（单例、进程级、挂起的协程不属于任何一次 UI 组合），
 * 但有一条不同：**同时只允许一个待审批**，第二个请求立刻按 [ApprovalDecision.CANCEL] 关闭。
 * 这是 fail-closed —— 并行工具调用同时要权限时，宁可让后一个被拒绝，也不能把两个请求
 * 搅在一起、或者让用户以为一次点击批准了两件事。
 */
object ApprovalHub {
  private val lock = Any()
  private val _pending = MutableStateFlow<PendingApproval?>(null)
  val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()

  private var waiter: CompletableDeferred<ApprovalDecision>? = null

  /** 登记一次审批并挂起，直到用户选择、关闭面板、或桥那边取消。 */
  suspend fun request(request: PendingApproval): ApprovalDecision {
    val deferred = CompletableDeferred<ApprovalDecision>()
    synchronized(lock) {
      if (waiter != null) return ApprovalDecision.CANCEL
      waiter = deferred
      _pending.value = request
    }
    return try {
      deferred.await()
    } finally {
      synchronized(lock) { waiter = null }
      _pending.value = null
    }
  }

  /** UI 给出决定。[ApprovalDecision.CANCEL] 与 [dismiss] 等价。 */
  fun decide(decision: ApprovalDecision) {
    synchronized(lock) { waiter }?.complete(decision)
  }

  /** 用户关掉面板 —— 这是取消，不是拒绝；但两者都不授权。 */
  fun dismiss() {
    decide(ApprovalDecision.CANCEL)
  }
}

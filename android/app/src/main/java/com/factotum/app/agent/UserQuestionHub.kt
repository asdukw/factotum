package com.factotum.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一个选项。与 dsh 的 `AskUserQuestionOption` 逐字对应。 */
data class UserQuestionOption(val label: String, val description: String?)

/**
 * 一道题。字段与 dsh 的 `AskUserQuestionItem` 一一对应 —— **不要在这里加协议里没有的东西**：
 * 这个结构是跨进程（插件 ↔ 桥 ↔ UI）传的，多一个字段就多一处要对齐的地方。
 */
data class UserQuestion(
  val id: String,
  val question: String,
  val detail: String? = null,
  val header: String? = null,
  val options: List<UserQuestionOption> = emptyList(),
  val multiSelect: Boolean = false,
)

/**
 * 一条回答。三条语义照抄 dsh（见 `docs/user-questions.md`）：
 * - 单选时 [custom] **覆盖** [selected]（selected 留空）；多选时 [custom] **补充** [selected]；
 * - **跳过 = `selected` 为空** —— 是一个空答案，不是「没答」；
 * - 一批答案必须**每题一条**，少一条宿主会以 BAD_ANSWER 拒绝。
 */
data class UserQuestionAnswer(val id: String, val selected: List<String>, val custom: String? = null)

/** 一批待答的问题（dsh 一次只问一批）。 */
data class PendingUserQuestions(
  val callId: String?,
  val timed: Boolean,
  val questions: List<UserQuestion>,
)

/**
 * 待答问题的唯一持有者：桥把问题登记进来并挂起，UI 提交答案后唤醒。
 *
 * 为什么是单例而不是塞进 ViewModel：桥是进程级的（跟设备桥 / 连接器桥一样），
 * 而 UI 会随导航重建。挂起的那个协程属于桥，不属于任何一次 UI 组合 ——
 * 放进 ViewModel 会让「用户切走再切回来」变成「答案丢了、插件那边还在等」。
 *
 * **同时只允许一批**：dsh 的模型就是一次一批；第二批发过来时直接拒掉（返回 null），
 * 插件据此 `next()` 让路，而不是把两批搅在一起。
 */
object UserQuestionHub {
  private val _pending = MutableStateFlow<PendingUserQuestions?>(null)
  val pending: StateFlow<PendingUserQuestions?> = _pending.asStateFlow()

  private var waiter: CompletableDeferred<List<UserQuestionAnswer>?>? = null

  /**
   * 登记一批问题并挂起，直到用户作答（返回答案）、关掉面板、或调用方取消（返回 null）。
   *
   * 返回 null 时**调用方必须让路**（插件的 `next()`），绝不能当成「用户全跳过了」——
   * 那会让模型拿着一个编出来的答案继续做事。
   */
  suspend fun ask(request: PendingUserQuestions): List<UserQuestionAnswer>? {
    if (waiter != null) return null
    val deferred = CompletableDeferred<List<UserQuestionAnswer>?>()
    waiter = deferred
    _pending.value = request
    return try {
      deferred.await()
    } finally {
      waiter = null
      _pending.value = null
    }
  }

  /** UI 提交答案。没有待答问题时是空操作（用户可能在面板关掉后才点到）。 */
  fun answer(answers: List<UserQuestionAnswer>) {
    waiter?.complete(answers)
  }

  /** UI 关闭面板 —— **不是回答**：让插件让路，问题留给下一个 answerer（或按 dsh 的语义超时）。 */
  fun dismiss() {
    waiter?.complete(null)
  }
}

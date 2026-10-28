package com.zoneu.app.agent

import java.util.concurrent.atomic.AtomicReference

/**
 * 从「角色卡页面」回到聊天页时要打开哪段会话。
 *
 * 为什么需要一个进程级的小信箱：角色页与聊天页是两个导航条目，各自持有自己的
 * ViewModel —— 角色页建了新会话，没法直接叫聊天页去打开它。点「开始对话」后
 * 聊天页回到前台，在这里取一次即走。
 *
 * 只传**意图**不传状态：取走就清空，界面重建、旋转屏幕都不会重复切换。
 */
object SessionHandoff {
  sealed interface Target {
    /** 打开指定会话（角色页刚建的那段）。 */
    data class Session(val id: String) : Target

    /** 回到最近一段日常会话。 */
    data object Normal : Target
  }

  private val pending = AtomicReference<Target?>(null)

  fun open(sessionId: String) {
    pending.set(Target.Session(sessionId))
  }

  fun openNormal() {
    pending.set(Target.Normal)
  }

  /** 取一次即消费；没有待处理的事情时返回 null。 */
  fun take(): Target? = pending.getAndSet(null)
}

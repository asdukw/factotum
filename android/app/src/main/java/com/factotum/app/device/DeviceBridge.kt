package com.factotum.app.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备操作能力的统一入口。
 *
 * 工具循环只依赖这个接口，不认具体实现。Android 侧的当前实现是
 * `FactotumAccessibilityService`；换 ROM 或换执行器时只换实现。
 */
interface DeviceBridge {
  /** 执行一个原子动作；所有工具都经这里落地到设备。 */
  suspend fun execute(action: DeviceAction): DeviceActionResult
}

/**
 * 桥最近一次失败的原因码（成功时清空）。
 *
 * 为什么需要它：工具结果是**模型侧**的东西（经插件转成给模型看的文本），界面拿不到结构化字段；
 * 而「无障碍没开」这件事必须让界面知道（要提示用户去系统设置里开）。让界面去匹配 summary 里的
 * 中文是脆的 —— 文案一改就静默失效，所以桥在进程内直接给一个码。
 *
 * 进程内单例：桥和 UI 在同一个 App 进程里（内嵌 Node 也是），这是最省的一条路。
 */
object BridgeHealth {
  private val _lastErrorCode = MutableStateFlow<String?>(null)
  val lastErrorCode: StateFlow<String?> = _lastErrorCode.asStateFlow()

  private val _lastErrorAction = MutableStateFlow<String?>(null)
  val lastErrorAction: StateFlow<String?> = _lastErrorAction.asStateFlow()

  internal fun record(code: String?, action: String? = null) {
    _lastErrorCode.value = code
    _lastErrorAction.value = action
  }

  /** 用户处理完（去开启 / 稍后）时清掉：对话框不该挂在那里，也不该下次重组又弹出来。 */
  fun dismiss() {
    _lastErrorCode.value = null
    _lastErrorAction.value = null
  }
}

package com.tappet.app.device

/**
 * 设备操作能力的统一入口。
 *
 * 工具循环只依赖这个接口，不认具体实现。Android 侧的当前实现是
 * `TappetAccessibilityService`；换 ROM 或换执行器时只换实现。
 */
interface DeviceBridge {
  /** 执行一个原子动作；所有工具都经这里落地到设备。 */
  suspend fun execute(action: DeviceAction): DeviceActionResult
}

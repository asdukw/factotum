package com.tappet.app.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设备操作能力的统一入口。
 *
 * 现在只有探针（四个能力实测）；正式执行层会在这里长出 readScreen / tap / setText /
 * screenshot / startApp / back 等操作，工具循环只依赖这个接口，不认具体实现。
 * Android 侧的当前实现是 `TappetAccessibilityService`；换 ROM 或换执行器时只换实现。
 */
interface DeviceBridge {
  fun probe(capability: DeviceCapability)

  /** 执行一个原子动作；所有工具都经这里落地到设备。 */
  suspend fun execute(action: DeviceAction): DeviceActionResult
}

/**
 * 结果的全局状态。
 *
 * 服务未连接时界面也要能观察「未连接」与旧结果，所以状态不放在服务实例里。
 * 只有本模块的实现方可写（internal）。
 */
object DeviceBridgeState {
  private val _connected = MutableStateFlow(false)
  val connected: StateFlow<Boolean> = _connected.asStateFlow()

  private val _results = MutableStateFlow<Map<DeviceCapability, CapabilityResult>>(emptyMap())
  val results: StateFlow<Map<DeviceCapability, CapabilityResult>> = _results.asStateFlow()

  internal fun setConnected(value: Boolean) {
    _connected.value = value
  }

  internal fun publish(result: CapabilityResult) {
    _results.update { it + (result.capability to result) }
  }
}

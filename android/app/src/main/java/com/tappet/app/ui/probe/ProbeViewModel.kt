package com.tappet.app.ui.probe

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.accessibility.TappetAccessibilityService
import com.tappet.app.device.CapabilityResult
import com.tappet.app.device.CapabilityStatus
import com.tappet.app.device.DeviceBridge
import com.tappet.app.device.DeviceBridgeState
import com.tappet.app.device.DeviceCapability
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 探针页状态机：按按钮 → 倒计时（留时间切到目标 App）→ 调用 [DeviceBridge] 执行。
 *
 * 只依赖设备能力接口，不认具体实现 —— 换 ROM / 换执行器时这里不动。
 * 结果由 [DeviceBridgeState] 推送，切出去再回来也能看到（进程存活期内）。
 */
class ProbeViewModel(
  private val bridgeProvider: () -> DeviceBridge? = { TappetAccessibilityService.get() },
) : ViewModel() {
  data class Countdown(
    val capability: DeviceCapability,
    val secondsLeft: Int,
  )

  data class UiState(
    val connected: Boolean = false,
    val results: Map<DeviceCapability, CapabilityResult> = emptyMap(),
    val countdown: Countdown? = null,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var countdownJob: Job? = null

  init {
    viewModelScope.launch {
      DeviceBridgeState.connected.collect { connected ->
        Log.i(TAG, "ui connected=$connected")
        _state.update { it.copy(connected = connected) }
      }
    }
    viewModelScope.launch {
      DeviceBridgeState.results.collect { results -> _state.update { it.copy(results = results) } }
    }
  }

  fun run(capability: DeviceCapability) {
    if (_state.value.countdown != null) return
    val bridge = bridgeProvider()
    Log.i(TAG, "run ${capability.name} connected=${_state.value.connected} bridge=${bridge != null}")
    if (bridge == null) {
      _state.update { current ->
        current.copy(
          results =
            current.results +
              (
                capability to
                  CapabilityResult(
                    capability = capability,
                    status = CapabilityStatus.FAILED,
                    summary = "服务未连接：先在系统设置里启用，或用 adb 命令启用",
                    at = System.currentTimeMillis(),
                  )
              ),
        )
      }
      return
    }

    countdownJob?.cancel()
    countdownJob =
      viewModelScope.launch {
        for (seconds in COUNTDOWN_SECONDS downTo 1) {
          _state.update { it.copy(countdown = Countdown(capability, seconds)) }
          delay(1_000)
        }
        _state.update { it.copy(countdown = null) }
        bridge.probe(capability)
      }
  }

  fun cancel() {
    countdownJob?.cancel()
    countdownJob = null
    _state.update { it.copy(countdown = null) }
  }

  companion object {
    private const val TAG = "TappetProbe"

    /** 留给用户切到目标 App 并停稳的时间。 */
    const val COUNTDOWN_SECONDS = 8
  }
}

package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.agent.TermuxBridgeLauncher
import com.tappet.app.agent.TermuxBridgeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Linux 环境（Termux 桥）设置页状态。
 *
 * 这一页只读「有没有 token」，不读 token 本体 —— 会进截图与读屏。token 由 Tappet 自己生成，
 * 用户在这里做的是三件事：确认 Termux 装了、点一次授权、把桥拉起来。
 */
class TermuxBridgeViewModel(private val appContext: Context) : ViewModel() {
  /** 一次操作的结论。**每条都要能对应到「用户下一步该做什么」**，否则等于没说。 */
  enum class Status(val message: String) {
    IDLE(""),
    WORKING("正在启动…"),
    RUNNING("已连接：桥在跑，命令可以直接下发。"),
    STARTED("已启动并连接。"),
    NO_TERMUX("手机上没有 Termux —— 先装它，再跑一次仓库里的 tools/termux/setup.sh。"),
    NO_PERMISSION("需要授权：Termux 的 RUN_COMMAND 权限要你点一次同意（ColorOS 拦 adb 代授）。"),
    FROZEN(
      "桥的端口在，但连不上 —— Termux 被系统冻结了（ColorOS 会冻后台应用）。" +
        "把 Termux 拉到前台再点「重新连接」，并在系统设置里放开 Termux 的后台运行权限。",
    ),
    FAILED("桥没起来：Termux 里确认 allow-external-apps=true、~/bridge.py 在位，再看 ~/bridge.log。"),
  }

  data class UiState(
    val urlInput: String = TermuxBridgeStore.DEFAULT_URL,
    /** 已经生成过 token = 已启用（宿主会把 env 注入插件，工具表里才有 run_command）。 */
    val enabled: Boolean = false,
    val termuxInstalled: Boolean = false,
    val permissionGranted: Boolean = false,
    val status: Status = Status.IDLE,
  ) {
    /** 主按钮的文案：每一步都写清「点了会发生什么」。 */
    val actionLabel: String
      get() =
        when {
          !termuxInstalled -> "未检测到 Termux"
          !permissionGranted -> "授权并启用"
          !enabled -> "启用"
          else -> "重新连接"
        }

    val actionEnabled: Boolean get() = termuxInstalled && status != Status.WORKING
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    _state.value =
      _state.value.copy(
        urlInput = TermuxBridgeStore.url(appContext),
        enabled = TermuxBridgeStore.token(appContext) != null,
        termuxInstalled = TermuxBridgeLauncher.isTermuxInstalled(appContext),
        permissionGranted = TermuxBridgeLauncher.hasPermission(appContext),
      )
  }

  fun onUrlChange(value: String) {
    _state.value = _state.value.copy(urlInput = value)
  }

  /** 用户点了主按钮：先记下地址，再按当前状态决定是「去要权限」还是「直接连」。 */
  fun onPrimaryAction() {
    TermuxBridgeStore.saveUrl(appContext, _state.value.urlInput)
    if (!TermuxBridgeLauncher.hasPermission(appContext)) {
      // 权限弹窗只能从界面发起，交给 composable 去 launch；这里只把状态摆正。
      _state.value = _state.value.copy(status = Status.NO_PERMISSION)
      return
    }
    connect()
  }

  /** 权限弹窗的结果回来了。同意就继续连，不同意就如实说清后果。 */
  fun onPermissionResult(granted: Boolean) {
    _state.value = _state.value.copy(permissionGranted = granted)
    if (granted) connect() else _state.value = _state.value.copy(status = Status.NO_PERMISSION)
  }

  private fun connect() {
    _state.value = _state.value.copy(status = Status.WORKING)
    viewModelScope.launch {
      // 生成 token 是「启用」的实质：有了它宿主才会把 env 注入插件。
      val token = TermuxBridgeStore.ensureToken(appContext)
      val url = TermuxBridgeStore.url(appContext)
      val status = TermuxBridgeLauncher.ensureStarted(appContext, url, token)
      _state.value =
        _state.value.copy(
          enabled = true,
          status =
            when (status) {
              TermuxBridgeLauncher.Status.RUNNING -> Status.RUNNING
              TermuxBridgeLauncher.Status.STARTED -> Status.STARTED
              TermuxBridgeLauncher.Status.NO_TERMUX -> Status.NO_TERMUX
              TermuxBridgeLauncher.Status.NO_PERMISSION -> Status.NO_PERMISSION
              TermuxBridgeLauncher.Status.FROZEN -> Status.FROZEN
              TermuxBridgeLauncher.Status.FAILED -> Status.FAILED
            },
        )
    }
  }

  /** 停用：清掉 token（工具表里随之不再出现 run_command），并顺带停掉桥。 */
  fun disable() {
    TermuxBridgeStore.clear(appContext)
    _state.value = _state.value.copy(enabled = false, status = Status.IDLE)
  }
}

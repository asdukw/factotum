package com.factotum.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无障碍的**文本注入通道**（`FLAG_INPUT_METHOD_EDITOR`，API 33+）。
 *
 * ## 它解决什么
 *
 * `type_text` 原先只有一条路：在控件树里找 `isEditable` 的节点、对它 `ACTION_SET_TEXT`。
 * 控件树不可读的 App（微信一类）上这条路直接断了 —— 没有节点可找；自绘输入框还会出现
 * 「有节点但被拒绝」。结果是视觉路径**能点不能打字**，「给某人发一条消息」这类任务做不到。
 *
 * 系统为此给无障碍服务留了一条侧门：声明 `flagInputMethodEditor` 之后，
 * `AccessibilityService.getInputMethod()` 会给出一个 [InputMethod]，它的
 * `currentInputConnection` 是**当前聚焦输入框**的连接，可以 `commitText` /
 * `performEditorAction` / `sendKeyEvent`。连接是系统按「谁拿着焦点」发的，
 * **与控件树可不可读无关**。
 *
 * ## 为什么不是「自建一个输入法」
 *
 * 自建 IME 得让用户把它切成当前输入法（只有当前输入法能切回自己），而一个无键盘的输入法
 * 等于把用户的键盘拿走 —— 每做一个需要输入的任务切一次，不能接受。这条通道**不需要切换**：
 * 用户已经开着的那个无障碍服务就是通道，键盘仍是用户自己那个。
 *
 * ## 边界
 *
 * - 输入框必须**已经获得焦点**（先点它一下，或走视觉路径 `tap_target`）。没焦点就没有连接 ——
 *   这是这条通道唯一的前置条件，也是 `text_no_channel` 那条码的来由。
 * - 只写不读：`getSurroundingText` 用不到，正文既不进日志也不回传（AGENTS.md 隐私红线）。
 * - API 33 以下没有这条通道，退回原来的行为（见 `com.factotum.app.device.TextInputCode`）。
 * - `flagInputMethodEditor` 是**服务配置**的一部分，启用无障碍时才解析：升级 APK 之后
 *   没重新启用过的话，[flagActive] 会是 false（`text_flag_missing` 那条码就是为它准备的）。
 */
object TextChannel {
  /** 与 `EditorInfo` 的约定一致：正数表示光标落在提交文本的末尾之后。 */
  private const val CURSOR_AT_END = 1

  /** 这台设备有没有这条通道（只看系统版本）。 */
  fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  /**
   * 无障碍服务**当前生效的**配置里有没有 `flagInputMethodEditor`。
   *
   * 读的是系统缓存的那份 [AccessibilityServiceInfo]（启用时从服务配置 XML 解析来的），
   * 不是仓库里的 XML —— 所以它回答的是「此刻真的生效了吗」，而不是「我们写了没有」。
   */
  fun flagActive(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) false else flagActiveApi33()

  /** 此刻拿不拿得到输入框连接（= 有没有输入框正拿着焦点）。 */
  suspend fun isReady(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) false else isReadyApi33()

  /** 把文本写进当前聚焦的输入框。返回 false = 没有连接或写入被拒。 */
  suspend fun commit(text: String): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) false else commitApi33(text)

  /**
   * 触发输入框自己的「发送 / 搜索」动作（等价于用户按软键盘上的那个键）。
   *
   * 输入框用 `imeOptions` 声明它要哪个动作；没声明（聊天输入框常是这种，发送键由 App 自己画）
   * 就退回一个回车键事件 —— 与 `press(enter)` 在控件树那条路上做的事一致。
   */
  suspend fun enter(): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) false else enterApi33()

  /**
   * 一行状态，给 debug 广播用（无 UI 时验证这条通道）。
   *
   * **只报结构**：系统版本、配置是否生效、有没有连接、输入框的类型与声明的动作 ——
   * 不报输入框里的内容（隐私红线）。
   */
  suspend fun describe(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return "supported=false（Android 13 以下没有这条通道）"
    }
    return "supported=true flagActive=${flagActiveApi33()} connection=${isReadyApi33()} " +
      "editor=${editorDescApi33()}"
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun flagActiveApi33(): Boolean {
    val flags = FactotumAccessibilityService.get()?.serviceInfo?.flags ?: return false
    return flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun isReadyApi33(): Boolean = withContext(Dispatchers.Main) { connectionApi33() != null }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun commitApi33(text: String): Boolean = withContext(Dispatchers.Main) {
    val connection = connectionApi33() ?: return@withContext false
    runCatching { connection.commitText(text, CURSOR_AT_END, null) }.isSuccess
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun enterApi33(): Boolean = withContext(Dispatchers.Main) {
    val connection = connectionApi33() ?: return@withContext false
    val action = editorInfoApi33()?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
    runCatching {
      if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
        connection.performEditorAction(action)
      } else {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
      }
    }
      .isSuccess
  }

  /** 输入框类型 + 它声明的动作；没有输入框时是 `none`。只报结构，不报正文。 */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private suspend fun editorDescApi33(): String = withContext(Dispatchers.Main) {
    val info = editorInfoApi33() ?: return@withContext "none"
    "type=${info.inputType and EditorInfo.TYPE_MASK_CLASS} action=${info.imeOptions and EditorInfo.IME_MASK_ACTION}"
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun connectionApi33(): InputMethod.AccessibilityInputConnection? =
    FactotumAccessibilityService.get()?.inputMethod?.currentInputConnection

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun editorInfoApi33(): EditorInfo? =
    FactotumAccessibilityService.get()?.inputMethod?.currentInputEditorInfo
}

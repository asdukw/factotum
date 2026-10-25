package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.agent.PersonaStore
import com.tappet.app.agent.Soul
import com.tappet.app.node.NodeRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 人格设置页状态：可选人格、当前选中的那份、自定义正文、语气契约开关，以及**当前实际生效的
 * 整份系统提示**（预览）。
 *
 * 切换即生效：写偏好 → [NodeRuntime.reload]（dsh 的 cordis remount，不重启 App / Node）。
 * 重载会换掉会话命名空间 —— 与切换模型同一条路径，也是同一份代价：**当前对话会开新上下文**。
 * 这一点必须写在界面上，否则用户会以为是「切换人格把历史吃掉了」。
 */
class PersonaViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val souls: List<Soul> = emptyList(),
    val selectedId: String = PersonaStore.DEFAULT_ID,
    val customText: String = "",
    val toneEnabled: Boolean = true,
    /** 正在重载运行时。 */
    val applying: Boolean = false,
    /** 一次性的结果提示（成功或失败），由界面消费后清掉。 */
    val message: String? = null,
    /** 当前生效的整份系统提示（soul + 操作契约 + 语气契约）。 */
    val preview: String = "",
  ) {
    val selected: Soul? get() = souls.firstOrNull { it.id == selectedId }
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refresh()
  }

  /** 进页面 / 保存自定义后重读一遍（这条路径没有 prefs 观察，重读最直接）。 */
  fun refresh() {
    _state.update {
      it.copy(
        souls = PersonaStore.souls(appContext),
        selectedId = PersonaStore.selectedId(appContext),
        customText = PersonaStore.customText(appContext),
        toneEnabled = PersonaStore.toneContractEnabled(appContext),
        preview = PersonaStore.compose(appContext),
      )
    }
  }

  /** 选中一份人格并立即生效。 */
  fun select(id: String) {
    if (id == _state.value.selectedId) return
    PersonaStore.select(appContext, id)
    refresh()
    apply()
  }

  /** 保存自定义正文；非空时顺手切过去 —— 用户写它的目的就是用它。 */
  fun saveCustom(text: String) {
    val trimmed = text.trim()
    PersonaStore.setCustomText(appContext, trimmed)
    // 清空自定义时把选择退回默认：否则 selectedId 指着一个已经不存在的条目，
    // 界面上「一个都没选中」，而实际生效的是默认人格 —— 用户看到的不等于在用的。
    PersonaStore.select(appContext, if (trimmed.isNotBlank()) PersonaStore.CUSTOM_ID else PersonaStore.DEFAULT_ID)
    refresh()
    apply()
  }

  /** 语气契约：同一段话的「怎么写」，与人格正交，所以单独一个开关。 */
  fun setToneContract(enabled: Boolean) {
    PersonaStore.setToneContractEnabled(appContext, enabled)
    refresh()
    apply()
  }

  /** 消费提示，避免重组时重复弹。 */
  fun consumeMessage() {
    _state.update { it.copy(message = null) }
  }

  private fun apply() {
    if (_state.value.applying) return
    _state.update { it.copy(applying = true) }
    viewModelScope.launch {
      val outcome =
        runCatching { NodeRuntime.reload(appContext) }
          .fold(
            onSuccess = { "已切换，下一句起生效" },
            // 失败时把原因原样给出：重载超时 / 宿主没启动，用户至少知道该重试还是该重启 App。
            onFailure = { error -> "切换失败：${error.message ?: error::class.simpleName}" },
          )
      _state.update { it.copy(applying = false, message = outcome) }
    }
  }
}

package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.CharacterStore
import com.tappet.app.agent.PersonaStore
import com.tappet.app.node.NodeRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色库状态。
 *
 * 选中即生效（写偏好 → 重载运行时），与人格切换同一条路；代价也一样：**当前对话会开新上下文**。
 * 这条代价在界面上明说，并且「开始对话」按钮点完就直接回聊天页 —— 用户马上能看到开场。
 */
class CharacterViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val cards: List<CharacterCard> = emptyList(),
    /** 正在扮演的卡 id；null = 不在角色扮演。 */
    val activeId: String? = null,
    val applying: Boolean = false,
    val message: String? = null,
  ) {
    val active: CharacterCard? get() = cards.firstOrNull { it.id == activeId }
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    _state.update {
      it.copy(
        cards = CharacterStore.cards(appContext),
        activeId = PersonaStore.selectedCharacter(appContext)?.id,
      )
    }
  }

  /** 开始扮演：选中这张卡并重载运行时。 */
  fun start(id: String) {
    if (_state.value.activeId == id) return
    PersonaStore.selectCharacter(appContext, id)
    refresh()
    apply("已进入角色，下一句起生效")
  }

  /** 退出角色扮演，回到默认人格。 */
  fun stop() {
    if (_state.value.activeId == null) return
    PersonaStore.clearCharacter(appContext)
    refresh()
    apply("已退出角色")
  }

  fun consumeMessage() {
    _state.update { it.copy(message = null) }
  }

  private fun apply(success: String) {
    if (_state.value.applying) return
    _state.update { it.copy(applying = true) }
    viewModelScope.launch {
      val outcome =
        runCatching { NodeRuntime.reload(appContext) }
          .fold(onSuccess = { success }, onFailure = { "切换失败：${it.message ?: it::class.simpleName}" })
      _state.update { it.copy(applying = false, message = outcome) }
    }
  }
}

package com.tappet.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.CharacterImporter
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
    val importing: Boolean = false,
    val message: String? = null,
    /** 导入时被丢掉的字段说明（system_prompt 之类），给用户看一次就够。 */
    val notes: List<String> = emptyList(),
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
    _state.update { it.copy(message = null, notes = emptyList()) }
  }

  /**
   * 导入一张卡（PNG 或 JSON）。
   *
   * 存下来再刷新，而不是把解析结果直接塞进内存列表：卡最终是磁盘上的数据，
   * 只放内存会让「重启 App 后卡不见了」这种问题晚一步才被发现。
   */
  fun import(uri: Uri) {
    if (_state.value.importing) return
    _state.update { it.copy(importing = true, notes = emptyList()) }
    viewModelScope.launch {
      val outcome =
        runCatching {
          val bytes =
            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
              ?: throw CharacterImporter.ImportException("读不到这个文件")
          CharacterImporter.fromBytes(bytes, displayName(uri))
        }
      outcome
        .onSuccess { result ->
          if (!CharacterStore.saveImported(appContext, result.card)) {
            _state.update { it.copy(importing = false, message = "导入失败：写不进应用目录") }
            return@onSuccess
          }
          refresh()
          _state.update {
            it.copy(importing = false, message = "已导入「${result.card.name}」", notes = result.notes)
          }
        }
        .onFailure { error ->
          _state.update {
            it.copy(importing = false, message = "导入失败：${error.message ?: error::class.simpleName}")
          }
        }
    }
  }

  /** 删掉一张导入的卡；正在扮演的卡被删时顺手退出角色，免得界面停在「扮演中」。 */
  fun delete(id: String) {
    CharacterStore.deleteImported(appContext, id)
    if (_state.value.activeId == id) {
      PersonaStore.clearCharacter(appContext)
      apply("已删除「${_state.value.cards.firstOrNull { it.id == id }?.name ?: "角色"}」，已退出扮演")
      return
    }
    refresh()
    _state.update { it.copy(message = "已删除") }
  }

  private fun displayName(uri: Uri): String {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    val name =
      runCatching {
        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
      }
        .getOrNull()
    return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "未命名角色"
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

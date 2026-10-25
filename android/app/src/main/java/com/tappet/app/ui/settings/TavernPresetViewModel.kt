package com.tappet.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.tappet.app.agent.CharacterStore
import com.tappet.app.agent.TavernMessage
import com.tappet.app.agent.TavernPreset
import com.tappet.app.agent.TavernPresetStore
import com.tappet.app.agent.TavernPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 酒馆预设页状态。
 *
 * 三件事分开记，免得互相踩：
 * - [selectedId]：**正在生效**的那套（下一条消息就用它）；
 * - [editing]：编辑区里正在改的那套的来源（内置的那套只能另存，不能就地改）；
 * - [system] / [postHistory]：编辑区里的**草稿**，没点保存就不影响生效。
 *
 * 预览用第一张卡渲染：模板变量缺一张真卡就看不出效果（条件块、开场白都跟卡的内容有关）。
 */
class TavernPresetViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val presets: List<TavernPreset> = emptyList(),
    val selectedId: String = TavernPreset.DEFAULT_ID,
    /** 编辑区来源的名字；内置的那套显示「默认（内置）· 另存为新预设才能保留改动」。 */
    val editingBuiltIn: Boolean = false,
    val system: String = TavernPreset.DEFAULT_SYSTEM,
    val postHistory: String = TavernPreset.DEFAULT_POST_HISTORY,
    /** 草稿与来源不一致（界面据此提示「还没保存」）。 */
    val dirty: Boolean = false,
    val preview: String = "",
    val previewName: String = "",
    val message: String? = null,
  )

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  /** 编辑区的来源 id；草稿要覆盖保存时用它定位文件。 */
  private var editingId: String? = null

  init {
    // 一开始就把**当前生效**的那套载入编辑区：否则界面写着「当前编辑：自定义预设」，
    // 而实际生效的是内置默认 —— 用户看到的不等于在用的。
    load(TavernPresetStore.selected(appContext))
    refresh()
  }

  fun refresh() {
    val presets = TavernPresetStore.presets(appContext)
    val selectedId = TavernPresetStore.selectedId(appContext)
    _state.update { it.copy(presets = presets, selectedId = selectedId, preview = preview(it.system, it.postHistory)) }
  }

  /** 切一套生效，并把正文载入编辑区（多数人点一套就是想看看它长什么样）。 */
  fun select(id: String) {
    val preset = TavernPresetStore.presets(appContext).firstOrNull { it.id == id } ?: return
    TavernPresetStore.select(appContext, id)
    load(preset)
    _state.update { it.copy(selectedId = id) }
  }

  /** 载入某套到编辑区，不改变生效的那套。 */
  fun edit(id: String) {
    val preset = TavernPresetStore.presets(appContext).firstOrNull { it.id == id } ?: return
    load(preset)
  }

  private fun load(preset: TavernPreset) {
    editingId = preset.id
    _state.update {
      it.copy(
        system = preset.system,
        postHistory = preset.postHistory,
        editingBuiltIn = preset.builtIn,
        dirty = false,
        preview = preview(preset.system, preset.postHistory),
      )
    }
  }

  fun updateSystem(text: String) =
    _state.update { it.copy(system = text, dirty = true, preview = preview(text, it.postHistory)) }

  fun updatePostHistory(text: String) =
    _state.update { it.copy(postHistory = text, dirty = true, preview = preview(it.system, text)) }

  /**
   * 保存。内置的那套不落盘 —— 落盘了就再也「恢复默认」不回来了，所以内置的直接转成新预设。
   */
  fun save() {
    val snapshot = _state.value
    if (snapshot.system.isBlank() || snapshot.postHistory.isBlank()) {
      _state.update { it.copy(message = "两段都不能留空：系统模板负责设定，后置指令负责收尾") }
      return
    }
    val builtIn = snapshot.editingBuiltIn
    val id = if (builtIn || editingId == null) TavernPresetStore.newId() else editingId!!
    val name =
      if (builtIn) {
        "我的预设 ${TavernPresetStore.presets(appContext).count { !it.builtIn } + 1}"
      } else {
        TavernPresetStore.presets(appContext).firstOrNull { it.id == id }?.name ?: "我的预设"
      }
    val preset =
      TavernPreset(id = id, name = name, system = snapshot.system, postHistory = snapshot.postHistory)
    val saved = TavernPresetStore.save(appContext, preset)
    if (!saved) {
      _state.update { it.copy(message = "保存失败：存不进应用私有目录") }
      return
    }
    // 刚保存的这套顺手生效：用户改它就是为了用。
    TavernPresetStore.select(appContext, id)
    editingId = id
    _state.update {
      it.copy(
        editingBuiltIn = false,
        dirty = false,
        selectedId = id,
        presets = TavernPresetStore.presets(appContext),
        message = "已保存并启用",
      )
    }
  }

  /** 从编辑区当前内容另存一套（内置预设走这条路）。 */
  fun saveAsCopy() {
    val snapshot = _state.value
    val id = TavernPresetStore.newId()
    val preset =
      TavernPreset(
        id = id,
        name = "我的预设 ${TavernPresetStore.presets(appContext).count { !it.builtIn } + 1}",
        system = snapshot.system,
        postHistory = snapshot.postHistory,
      )
    if (!TavernPresetStore.save(appContext, preset)) {
      _state.update { it.copy(message = "另存失败：存不进应用私有目录") }
      return
    }
    editingId = id
    _state.update {
      it.copy(
        presets = TavernPresetStore.presets(appContext),
        editingBuiltIn = false,
        dirty = false,
        message = "已另存为新预设（还没启用，选中它就生效）",
      )
    }
  }

  fun delete() {
    val id = editingId ?: return
    if (id == TavernPreset.DEFAULT_ID) {
      _state.update { it.copy(message = "内置预设删不掉，可以另存后改") }
      return
    }
    val removed = TavernPresetStore.delete(appContext, id)
    val presets = TavernPresetStore.presets(appContext)
    editingId = TavernPreset.DEFAULT_ID
    load(TavernPreset.DEFAULT)
    _state.update {
      it.copy(
        presets = presets,
        selectedId = TavernPresetStore.selectedId(appContext),
        message = if (removed) "已删除" else "删除失败",
      )
    }
  }

  /** 丢弃草稿，回到默认那套的文本（不动已保存的预设）。 */
  fun resetDraft() {
    load(TavernPreset.DEFAULT)
    _state.update { it.copy(message = "编辑区已恢复默认文本（还没保存）") }
  }

  fun consumeMessage() = _state.update { it.copy(message = null) }

  /** 用第一张卡渲染预览：没有真卡时条件块全都不出现，看不出模板长什么样。 */
  private fun preview(system: String, postHistory: String): String {
    val card = runCatching { CharacterStore.cards(appContext).firstOrNull() }.getOrNull() ?: return ""
    val preset = TavernPreset(id = "preview", name = "预览", system = system, postHistory = postHistory)
    val turns = listOf(TavernMessage(TavernMessage.USER, "（预览用的一句话）"))
    val messages = TavernPrompt.build(card, memory = "", turns = turns, preset = preset)
    return messages.joinToString("\n\n———\n\n") { "[${it.role}]\n${it.content}" }
  }
}

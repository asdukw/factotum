package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.agent.AgentConfigStore
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.agent.ProviderSettings
import com.zoneu.app.node.NodeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提供商列表状态。启用是**互斥**的：内嵌运行时每个进程只连一个端点，多启用没有运行语义。
 * 启用一个未配置完整的提供商时给出错误，而不是把正在用的那家停掉、让 App 静默变成未配置。
 */
internal class ProvidersViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val query: String = "",
    val providers: List<ProviderSettings> = emptyList(),
    val error: String? = null,
  ) {
    val visible: List<ProviderSettings>
      get() = filterProviders(providers, query)
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    _state.update {
      it.copy(providers = sortProviders(AgentConfigStore.providers(appContext)), error = null)
    }
  }

  fun onQueryChange(value: String) {
    _state.update { it.copy(query = value, error = null) }
  }

  fun clearError() {
    _state.update { it.copy(error = null) }
  }

  fun toggleEnabled(preset: LlmPreset) {
    val settings = AgentConfigStore.provider(appContext, preset)
    if (settings.enabled) {
      val ok = AgentConfigStore.setEnabled(appContext, preset, false)
      reload()
      _state.update { it.copy(error = if (ok) null else "写入失败：本地存储不可用") }
      if (ok) applyRuntime()
      return
    }
    if (!settings.canEnable) {
      _state.update { it.copy(error = "先填写 API Base Url 与模型 id，再启用") }
      return
    }
    if (!settings.hasKey) {
      _state.update { it.copy(error = "先保存 API Key，再启用") }
      return
    }
    val ok = AgentConfigStore.setEnabled(appContext, preset, true)
    reload()
    _state.update { it.copy(error = if (ok) null else "写入失败：本地存储不可用") }
    if (ok) applyRuntime()
  }

  private fun applyRuntime() {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { NodeRuntime.reloadIfRunning(appContext) }
      } catch (_: Throwable) {
        _state.update { it.copy(error = "设置已保存，但运行时应用失败，请重试") }
      }
    }
  }
}

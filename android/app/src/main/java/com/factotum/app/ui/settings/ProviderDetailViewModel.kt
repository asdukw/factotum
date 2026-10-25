package com.factotum.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.factotum.app.agent.AgentConfigStore
import com.factotum.app.agent.HttpModelListFetcher
import com.factotum.app.agent.LlmPreset
import com.factotum.app.agent.ModelListFetcher
import com.factotum.app.agent.requestApiKey
import com.factotum.app.agent.saveRemoteModels
import com.factotum.app.agent.saveSelectedModels
import com.factotum.app.agent.setModel
import com.factotum.app.node.NodeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 提供商详情：名称 / 密钥 / 端点 / 启用状态 + 模型选择 + 高级设置。
 *
 * 非密钥字段**改一次存一次**（输入框里看到什么，下次启动就用什么）；密钥必须点「保存」——
 * 每次击键都做一次 Keystore 加密既不必要，也让「留空 = 不修改」这条规则变得模糊。
 */
internal class ProviderDetailViewModel(
  private val appContext: Context,
  providerId: String,
  private val fetcher: ModelListFetcher = HttpModelListFetcher(),
  private val runtimeApply: suspend (Context) -> Boolean = { context ->
    NodeRuntime.applyWhenIdle(context)
  },
) : ViewModel() {
  private val preset: LlmPreset = LlmPreset.byId(providerId) ?: LlmPreset.Custom

  data class UiState(
    val preset: LlmPreset = LlmPreset.Custom,
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val api: String = "",
    val enabled: Boolean = false,
    val hasKey: Boolean = false,
    /** 这次输入的新密钥；空串表示「不修改」。**已存的密钥永远不会回填到这里。** */
    val keyInput: String = "",
    val revealKey: Boolean = false,
    val keySaved: Boolean = false,
    val runtimeApplying: Boolean = false,
    val runtimeApplied: Boolean = false,
    /** 模型页里「其它模型 id」的草稿，点「使用」才落盘。 */
    val modelDraft: String = "",
    /** 从端点 `GET /models` 拉回来并落盘的清单（可能很长）。 */
    val remoteModels: List<String> = emptyList(),
    /** 用户选进「可用模型」的清单；聊天页从这里挑。 */
    val selectedModels: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
    val fetchError: String? = null,
    val tab: DetailTab = DetailTab.Config,
    val error: String? = null,
  ) {
    val canEnable: Boolean
      get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** 「已验证」只对目录默认组合成立：用户改过端点 / 模型 / 调用风格后就不适用了。 */
    val verifiedNow: Boolean
      get() =
        preset.verified &&
          baseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/') &&
          model == preset.defaultModel &&
          api == preset.api

    /** 「Response API」开关的状态：切到 openai-responses 时为真。 */
    val responseApi: Boolean
      get() = api == "openai-responses"
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var runtimeApplyJob: Job? = null

  init {
    reload()
  }

  /**
   * 重新读存储。**不动未保存的输入**：拨开关 / 选模型也会走到这里，把用户刚粘进来的 key
   * 清空会让人以为已经保存好了，而实际上什么都没落盘。
   */
  fun reload() {
    val settings = AgentConfigStore.provider(appContext, preset)
    _state.update { current ->
      current.copy(
        preset = preset,
        name = settings.name,
        baseUrl = settings.baseUrl,
        model = settings.model,
        api = settings.api,
        enabled = settings.enabled,
        hasKey = settings.hasKey,
        // 首次加载（草稿为空）跟随已保存模型；用户正在编辑的草稿保留。
        modelDraft = current.modelDraft.ifBlank { settings.model },
        remoteModels = settings.remoteModels,
        selectedModels = settings.selectedModels,
      )
    }
  }

  /**
   * 拉端点模型列表（`GET /models`）。密钥只在这一条请求里用，取完即弃 —— 不进状态、不进日志。
   * 拉回来的清单落盘（换页 / 重启还在），选中之后仍走动态模态声明：目录外的 id 只有文本。
   */
  fun fetchModels() {
    if (_state.value.fetchingModels) return
    val settings = AgentConfigStore.provider(appContext, preset)
    val apiKey = requestApiKey(appContext, preset)
    _state.update { it.copy(fetchingModels = true, fetchError = null) }
    viewModelScope.launch {
      val result = withContext(Dispatchers.IO) { fetcher.fetch(settings, apiKey) }
      val failure = result.error
      if (failure != null) {
        _state.update { it.copy(fetchingModels = false, fetchError = failure) }
        return@launch
      }
      saveRemoteModels(appContext, preset, result.ids)
      _state.update {
        it.copy(fetchingModels = false, remoteModels = result.ids, fetchError = null)
      }
    }
  }

  fun onTabSelect(tab: DetailTab) {
    _state.update { it.copy(tab = tab) }
  }

  fun onNameChange(value: String) {
    _state.update { it.copy(name = value, error = null) }
    persistFields()
  }

  fun onBaseUrlChange(value: String) {
    _state.update { it.copy(baseUrl = value, error = null) }
    persistFields()
    scheduleRuntimeApply()
  }

  fun onModelDraftChange(value: String) {
    _state.update { it.copy(modelDraft = value, error = null) }
  }

  fun onApiKeyChange(value: String) {
    _state.update { it.copy(keyInput = value, keySaved = false, error = null) }
  }

  fun toggleKeyReveal() {
    _state.update { it.copy(revealKey = !it.revealKey) }
  }

  fun saveKey() {
    val key = _state.value.keyInput
    if (key.isBlank()) {
      _state.update { it.copy(error = "请输入 API Key") }
      return
    }
    if (!AgentConfigStore.saveApiKey(appContext, preset, key)) {
      _state.update { it.copy(error = "写入失败：本地存储不可用") }
      return
    }
    _state.update {
      it.copy(
        keyInput = "",
        keySaved = true,
        hasKey = true,
        runtimeApplying = false,
        runtimeApplied = false,
        error = null,
      )
    }
    scheduleRuntimeApply()
  }

  fun clearKey() {
    if (!AgentConfigStore.clearApiKey(appContext, preset)) {
      _state.update { it.copy(error = "清除失败：本地存储不可用") }
      return
    }
    _state.update {
      it.copy(
        hasKey = false,
        keySaved = false,
        runtimeApplying = false,
        runtimeApplied = false,
        error = null,
      )
    }
    scheduleRuntimeApply()
  }

  /**
   * 启用 / 停用。启用前要求端点、模型、**密钥**三样都齐 —— 否则会把正在用的那家停掉，
   * 下一次启动变成「未配置」，而列表里只看到徽章换了颜色。
   */
  fun onEnabledChange(enabled: Boolean) {
    val current = _state.value
    if (enabled && !current.canEnable) {
      _state.update { it.copy(error = "先填写 API Base Url 与模型 id，再启用") }
      return
    }
    if (enabled && !current.hasKey) {
      _state.update { it.copy(error = "先保存 API Key，再启用") }
      return
    }
    val wasActive = AgentConfigStore.enabledPreset(appContext) == preset
    if (!AgentConfigStore.setEnabled(appContext, preset, enabled)) {
      _state.update { it.copy(error = "写入失败：本地存储不可用") }
      return
    }
    reload()
    if (wasActive || enabled) scheduleRuntimeApply(force = true)
  }

  /** Response API 开关只改调用风格，不动端点 / 模型。 */
  fun onResponseApiChange(checked: Boolean) {
    val api =
      if (checked) {
        "openai-responses"
      } else {
        "openai-completions"
      }
    _state.update { it.copy(api = api, error = null) }
    persistFields()
    scheduleRuntimeApply()
  }

  /**
   * 把模型加进 / 移出「可用模型」。移除当前模型时自动落到剩下的第一个；一个都不剩就清空。
   * 新加的第一个模型（当前模型为空）会顺手设为当前模型。
   */
  @Suppress("CyclomaticComplexMethod")
  fun toggleSelectedModel(modelId: String) {
    val id = modelId.trim()
    if (id.isEmpty()) return
    val current = _state.value
    val next =
      if (id in current.selectedModels) {
        current.selectedModels - id
      } else {
        current.selectedModels + id
      }
    saveSelectedModels(appContext, preset, next)
    val nextModel =
      when {
        id !in next && current.model == id -> next.firstOrNull().orEmpty()
        else -> current.model
      }
    when {
      id !in next && current.model == id -> setModel(appContext, preset, nextModel)
      id in next && current.model.isBlank() -> setModel(appContext, preset, id)
    }
    reload()
    val modelChanged =
      (id !in next && current.model == id) || (id in next && current.model.isBlank())
    if (modelChanged && current.enabled) scheduleRuntimeApply(force = true)
  }

  /**
   * 选一个模型。有密钥时**选中即启用这家**：模型页的语义是「Agent 用这个模型」。
   * 没有密钥时只保存模型 —— 启用一个没有 key 的提供商会把正在用的那家停掉，App 变成未配置。
   */
  fun onModelSelect(modelId: String) {
    val id = modelId.trim()
    if (id.isEmpty()) {
      _state.update { it.copy(error = "模型 id 不能为空") }
      return
    }
    val current = _state.value
    if (current.baseUrl.isBlank()) {
      _state.update { it.copy(error = "先填写 API Base Url，再选择模型") }
      return
    }
    setModel(appContext, preset, id)
    val hasKey = AgentConfigStore.provider(appContext, preset).hasKey
    if (hasKey) AgentConfigStore.setEnabled(appContext, preset, true)
    reload()
    _state.update {
      it.copy(
        modelDraft = id,
        error = if (hasKey) null else "模型已保存；保存 API Key 后才能启用",
      )
    }
    if (hasKey) scheduleRuntimeApply(force = true)
  }

  fun useModelDraft() {
    onModelSelect(_state.value.modelDraft)
  }

  fun resetProvider() {
    val wasActive = AgentConfigStore.enabledPreset(appContext) == preset
    if (!AgentConfigStore.resetProvider(appContext, preset)) {
      _state.update { it.copy(error = "重置失败：本地存储不可用") }
      return
    }
    reload()
    _state.update {
      it.copy(
        modelDraft = it.model,
        keyInput = "",
        keySaved = false,
        revealKey = false,
        error = null,
      )
    }
    scheduleRuntimeApply(force = wasActive)
  }

  private fun scheduleRuntimeApply(force: Boolean = false) {
    if (!force && AgentConfigStore.enabledPreset(appContext) != preset) return
    _state.update { it.copy(runtimeApplying = true, runtimeApplied = false, error = null) }
    runtimeApplyJob?.cancel()
    runtimeApplyJob = viewModelScope.launch {
      delay(250)
      try {
        val applied = withContext(Dispatchers.IO) { runtimeApply(appContext) }
        _state.update {
          it.copy(runtimeApplying = false, runtimeApplied = applied, error = if (applied) null else "运行时尚未就绪")
        }
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        _state.update {
          it.copy(
            runtimeApplying = false,
            runtimeApplied = false,
            error = "密钥已保存，但运行时应用失败，请重试",
          )
        }
      }
    }
  }

  private fun persistFields() {
    val current = _state.value
    AgentConfigStore.saveProviderFields(
      context = appContext,
      preset = preset,
      name = current.name,
      baseUrl = current.baseUrl,
      model = current.model,
      api = current.api,
    )
  }
}

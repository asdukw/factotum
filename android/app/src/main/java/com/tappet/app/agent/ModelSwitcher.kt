package com.tappet.app.agent

import android.content.Context
import com.tappet.app.node.NodeRuntime

/** 聊天页模型选择器的一行（公开类型：ChatViewModel / UI 都用它，不含密钥）。 */
data class ModelChoice(
  val providerId: String,
  val providerName: String,
  val model: String,
  val current: Boolean,
)

/**
 * 聊天页的提供商 / 模型切换。
 *
 * `switch` 做两件事：把选择落盘（当前提供商 + 当前模型），再**重载 dsh 运行时**。
 * 重载是同一个 Node 进程内的 cordis remount（launcher 的 runtime.reload 信号），
 * 不是重启 App、也不是 process.exit。
 */
interface ModelSwitcher {
  fun choices(): List<ModelChoice>

  suspend fun switch(providerId: String, model: String)
}

class AgentModelSwitcher(private val appContext: Context) : ModelSwitcher {
  override fun choices(): List<ModelChoice> =
    AgentConfigStore.providers(appContext)
      .filter { it.hasKey && it.selectedModels.isNotEmpty() }
      .flatMap { provider ->
        (provider.selectedModels + provider.model)
          .filter { it.isNotBlank() }
          .distinct()
          .map { model ->
            ModelChoice(
              providerId = provider.preset.id,
              providerName = provider.name,
              model = model,
              current = provider.enabled && model == provider.model,
            )
          }
      }

  override suspend fun switch(providerId: String, model: String) {
    val preset = LlmPreset.byId(providerId) ?: error("未知提供商")
    val settings = AgentConfigStore.provider(appContext, preset)
    if (!settings.hasKey) error("这个提供商还没有 API Key")
    if (model.isBlank()) error("模型 id 为空")
    // 落盘顺序：先模型（同时保证它在可用列表里），再启用，最后重载。
    setModel(appContext, preset, model)
    AgentConfigStore.setEnabled(appContext, preset, true)
    NodeRuntime.reload(appContext)
  }
}

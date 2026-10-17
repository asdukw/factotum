package com.tappet.app.ui.settings

import com.tappet.app.agent.ProviderSettings

/**
 * 提供商搜索。纯函数，JVM 单测直接钉；匹配名称、说明、id、当前模型与目录里的模型 id，
 * 这样「搜 gpt 能找到 OpenRouter」这类预期才成立。
 */
internal fun filterProviders(
  providers: List<ProviderSettings>,
  query: String,
): List<ProviderSettings> {
  val needle = query.trim().lowercase()
  if (needle.isEmpty()) return providers
  return providers.filter { provider ->
    provider.name.lowercase().contains(needle) ||
      provider.preset.label.lowercase().contains(needle) ||
      provider.preset.description.lowercase().contains(needle) ||
      provider.preset.id.lowercase().contains(needle) ||
      provider.model.lowercase().contains(needle) ||
      provider.preset.models.any {
        it.id.lowercase().contains(needle) || it.label.lowercase().contains(needle)
      }
  }
}

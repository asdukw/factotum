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

/**
 * 按显示名（用户可改）的字母序排。中文名按 UTF-16 排在拉丁字母之后 —— 不做「默认 / 已验证
 * 置顶」：置顶会让顺序看起来是坏的，而用户要的就是稳定的字母序。改名后位置会跟着变。
 */
internal fun sortProviders(providers: List<ProviderSettings>): List<ProviderSettings> =
  providers.sortedBy { it.name.lowercase() }

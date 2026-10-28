package com.zoneu.app.accessibility

import android.content.Context

/**
 * 悬浮球的开关（用户可见的偏好）。
 *
 * 单独一个对象是因为它有两个使用方：`AgentOverlayController`（读，决定显不显示）与
 * 设置页（写，用户点开关）。prefs 名沿用控制器一直用的那个 `agent_overlay` —— 用户拖过的
 * 位置也存在那里，改名字会让老用户的坐标静默丢失（与 AGENTS.md「存储身份」同一条规矩）。
 *
 * **非密**：只是一个布尔，不走 SecretVault。
 */
object OverlaySettings {
  private const val PREFS = "zoneu_agent_overlay"
  private const val KEY_ENABLED = "enabled"

  /**
   * 默认**开**：悬浮球是任务在别的 App 里跑时唯一的可见信号，默认关掉等于默认让用户
   * 「不知道现在机器人在干什么」。不想看的用户在设置里关一次即可。
   */
  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

  /** 写偏好，并让**正在跑**的那个无障碍服务立刻生效（不必等下次服务重连）。 */
  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    ZoneuAccessibilityService.get()?.applyOverlayEnabled(enabled)
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

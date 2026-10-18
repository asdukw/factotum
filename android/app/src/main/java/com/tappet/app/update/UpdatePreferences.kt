package com.tappet.app.update

import android.content.Context

/**
 * 用户「跳过」过的版本。
 *
 * **为什么记一个列表而不是「一个布尔」**：跳过是针对**某个版本**的表态，不是「以后别再提醒我」。
 * 只记一个布尔的话，下个版本就再也不会提醒 —— 那等于把更新功能关掉了。
 * 记版本号则语义正好：跳过了 0.7.0，等 0.8.0 出来照样提醒。
 *
 * 存在普通 SharedPreferences 里（**非密**，不进 SecretVault）：它只是一个版本号集合，
 * 泄露没有任何后果。prefs 名带着 `tappet_` 前缀、与连接器那组同风格。
 */
object UpdatePreferences {
  private const val PREFS = "tappet_update"
  private const val KEY_SKIPPED = "skipped_versions"

  fun skipped(context: Context): Set<String> =
    prefs(context).getStringSet(KEY_SKIPPED, emptySet()).orEmpty().toSet()

  fun isSkipped(context: Context, version: String): Boolean = version in skipped(context)

  /** 记下「这个版本跳过了」。用 `apply`：它不参与任何需要「确实写进去了」判断的流程。 */
  fun skip(context: Context, version: String) {
    val next = skipped(context) + version
    prefs(context).edit().putStringSet(KEY_SKIPPED, next).apply()
  }

  /** 用户主动装上了某个版本之后调用：把比它旧/同版的跳过记录清掉，列表不会无限长。 */
  fun pruneAtOrBelow(context: Context, version: String) {
    val remaining = skipped(context).filter { AppUpdater.isNewerVersion(version, it) }.toSet()
    prefs(context).edit().putStringSet(KEY_SKIPPED, remaining).apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

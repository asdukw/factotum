package com.factotum.app.voice

import android.content.Context
import androidx.core.content.edit
import com.factotum.app.data.crypto.SecretVault

/**
 * 朗读用的 Fish Audio API key。与 LLM key 同一条规矩：**只有** SecretVault（Keystore 封装）
 * 一个来源，不进 BuildConfig、不进日志、不进 `runtime-config.json`。
 *
 * 与 LLM key 不同的是它**不经过 Node**：朗读完全在 Kotlin 侧，`VoiceReader` 建引擎时直接读。
 * 所以改 key 不需要重启运行时，只需要让缓存的引擎实例失效 —— 这就是 [revision] 的用途：
 * 引擎缓存的 key 里带上它，保存新 key 之后下次朗读自然重建。
 *
 * 存储身份（AGENTS.md §6.3）：prefs 名与 Keystore 别名定下就别改，改了等于换一把密钥，
 * 已存的 key 读不回来，表现出来只是「设置页说配过，但朗读说没配」。
 */
object VoiceSecretStore {
  private const val SECRET_PREFS = "factotum_voice_tts_key_v1"
  private const val KEY_ALIAS = "factotum-voice-tts-key-v1"

  private const val PREFS = "voice"
  private const val KEY_REVISION = "fish_key_revision"

  private val vault = SecretVault(prefsName = SECRET_PREFS, keyAlias = KEY_ALIAS)

  /** 明文只在这里过一手；调用方用完就丢，别缓存、别进日志。 */
  fun apiKey(context: Context): String? = vault.get(context)

  fun hasKey(context: Context): Boolean = vault.has(context)

  /** 保存（空串 = 清除）。成功后 [revision] 加一，正在用的引擎随之重建。 */
  fun save(context: Context, key: String): Boolean {
    val saved = vault.put(context, key.trim())
    if (saved) prefs(context).edit { putInt(KEY_REVISION, revision(context) + 1) }
    return saved
  }

  fun clear(context: Context): Boolean = save(context, "")

  /** 单调递增的版本号：只参与引擎缓存键，不是秘密。 */
  fun revision(context: Context): Int = prefs(context).getInt(KEY_REVISION, 0)

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

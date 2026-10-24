package com.tappet.app.voice

import android.content.Context
import androidx.core.content.edit

/**
 * 朗读引擎的 id。写进偏好，**改字面量 = 用户的选择静默丢失**（与 AGENTS.md 的存储身份同一条）。
 *
 * 现在只有 Fish Audio 一个引擎。[ALL] 与 [VoiceSettings.effectiveEngineId] 留着，是为了
 * 老用户偏好里存的 `system` / `local` 能安静地落到新引擎上，而不是让朗读整个不可用。
 */
object VoiceEngines {
  const val FISH = "fish"

  val ALL = listOf(FISH)
}

/**
 * 朗读偏好（用户可见）。**非密**：引擎、语速、自动朗读都是配置，存普通 prefs；
 * Fish Audio 的 API key 在 [VoiceSecretStore]（Keystore 封装）。
 *
 * 读的地方有两个（[VoiceReader] 建引擎、设置页显示），写的地方只有设置页 ——
 * 与 [com.tappet.app.accessibility.OverlaySettings] 同一个形状。
 *
 * 这些值在**建引擎时**读一次。改语速 / 换 key 之后由 [VoiceReader] 重建引擎生效，
 * 不需要重启 App。
 */
object VoiceSettings {
  private const val PREFS = "voice"
  private const val KEY_ENGINE = "engine"
  private const val KEY_AUTO_READ = "auto_read"
  private const val KEY_RATE = "rate"
  private const val KEY_FISH_VOICE = "fish_voice"

  const val MIN_RATE = 0.5f
  const val MAX_RATE = 2.0f
  const val DEFAULT_RATE = 1.0f

  fun engineId(context: Context): String = effectiveEngineId(prefs(context).getString(KEY_ENGINE, null))

  /**
   * [engineId] 的纯函数部分：认不出的值（老版本存的 `system` / `local`、手改过的 prefs）
   * 一律落到 Fish，而不是让朗读停在一个不存在的引擎上。
   *
   * 抽出来是为了能在 JVM 单测里钉住回落规则 —— SharedPreferences 那层不值得也不方便测。
   */
  internal fun effectiveEngineId(stored: String?): String =
    stored?.takeIf { it in VoiceEngines.ALL } ?: VoiceEngines.FISH

  /** 默认**关**：没点过朗读的用户不该在模型每次答完时被声音吓一跳。 */
  fun autoRead(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_READ, false)

  fun setAutoRead(context: Context, enabled: Boolean) {
    prefs(context).edit { putBoolean(KEY_AUTO_READ, enabled) }
  }

  /** 语速倍率，映射到 Fish 的 `prosody.speed`。 */
  fun rate(context: Context): Float =
    prefs(context).getFloat(KEY_RATE, DEFAULT_RATE).coerceIn(MIN_RATE, MAX_RATE)

  fun setRate(context: Context, rate: Float) {
    prefs(context).edit { putFloat(KEY_RATE, rate.coerceIn(MIN_RATE, MAX_RATE)) }
  }

  /** Fish 的参考音色 id；空 = 默认音色（[FishVoices.DEFAULT_ID]）。 */
  fun fishVoice(context: Context): String =
    prefs(context).getString(KEY_FISH_VOICE, FishVoices.DEFAULT_ID).orEmpty()

  fun setFishVoice(context: Context, voiceId: String) {
    prefs(context).edit { putString(KEY_FISH_VOICE, voiceId) }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

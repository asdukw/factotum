package com.tappet.app.voice

import android.content.Context

/** 朗读引擎的 id。写进偏好，**改字面量 = 用户的选择静默丢失**（与 AGENTS.md 的存储身份同一条）。 */
object VoiceEngines {
  const val SYSTEM = "system"
  const val LOCAL = "local"

  /** 界面上的顺序：装完就能用的排前面。 */
  val ALL = listOf(SYSTEM, LOCAL)
}

/**
 * 朗读偏好（用户可见）。**非密**：引擎、语速、音色都是配置，不走 SecretVault。
 *
 * 读的地方有两个（[VoiceReader] 建引擎、设置页显示），写的地方只有设置页 ——
 * 与 [com.tappet.app.accessibility.OverlaySettings] 同一个形状。
 *
 * 注意：这些值在**建引擎时**读一次。改语速 / 换引擎之后由 [VoiceReader] 重建引擎生效，
 * 不需要重启 App（与 API key 不同 —— 那个是 Node 启动时读的）。
 */
object VoiceSettings {
  private const val PREFS = "voice"
  private const val KEY_ENGINE = "engine"
  private const val KEY_AUTO_READ = "auto_read"
  private const val KEY_RATE = "rate"
  private const val KEY_SYSTEM_VOICE = "system_voice"
  private const val KEY_LOCAL_MODEL = "local_model"
  private const val KEY_ASR_MODEL = "asr_model"

  const val MIN_RATE = 0.5f
  const val MAX_RATE = 2.0f
  const val DEFAULT_RATE = 1.0f

  /** 认不出的值（改过 id / 手改过 prefs）一律退回系统引擎，而不是让朗读整个不可用。 */
  fun engineId(context: Context): String =
    prefs(context).getString(KEY_ENGINE, null)?.takeIf { it in VoiceEngines.ALL } ?: VoiceEngines.SYSTEM

  fun setEngineId(context: Context, id: String) {
    prefs(context).edit().putString(KEY_ENGINE, id).apply()
  }

  /** 默认**关**：没点过朗读的用户不该在模型每次答完时被声音吓一跳。 */
  fun autoRead(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_READ, false)

  fun setAutoRead(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_AUTO_READ, enabled).apply()
  }

  /** 语速倍率。系统引擎与本地引擎都认它。 */
  fun rate(context: Context): Float =
    prefs(context).getFloat(KEY_RATE, DEFAULT_RATE).coerceIn(MIN_RATE, MAX_RATE)

  fun setRate(context: Context, rate: Float) {
    prefs(context).edit().putFloat(KEY_RATE, rate.coerceIn(MIN_RATE, MAX_RATE)).apply()
  }

  /** 系统引擎的音色。本地引擎不用它 —— 音色由模型决定。 */
  fun systemVoice(context: Context): String? = prefs(context).getString(KEY_SYSTEM_VOICE, null)

  fun setSystemVoice(context: Context, voiceId: String?) {
    prefs(context).edit().apply {
      if (voiceId == null) remove(KEY_SYSTEM_VOICE) else putString(KEY_SYSTEM_VOICE, voiceId)
    }.apply()
  }

  /** 本地引擎选中的模型 id；null = 还没下过模型。 */
  fun localModel(context: Context): String? = prefs(context).getString(KEY_LOCAL_MODEL, null)

  fun setLocalModel(context: Context, modelId: String?) {
    prefs(context).edit().apply {
      if (modelId == null) remove(KEY_LOCAL_MODEL) else putString(KEY_LOCAL_MODEL, modelId)
    }.apply()
  }

  /**
   * 语音输入选中的识别模型 id；null = 还没下过模型。
   *
   * 与朗读的 [localModel] **各存各的**：一个用户完全可能想用本地识别但用系统朗读
   * （或者反过来），合成与识别是两件独立的事，共用一个槽位等于逼他二选一。
   */
  fun asrModel(context: Context): String? = prefs(context).getString(KEY_ASR_MODEL, null)

  fun setAsrModel(context: Context, modelId: String?) {
    prefs(context).edit().apply {
      if (modelId == null) remove(KEY_ASR_MODEL) else putString(KEY_ASR_MODEL, modelId)
    }.apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

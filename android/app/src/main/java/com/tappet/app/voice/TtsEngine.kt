package com.tappet.app.voice

/** 一个可选音色。当前引擎不提供可选项，界面据此不画选择器。 */
data class TtsVoice(
  val id: String,
  val label: String,
)

/**
 * 朗读引擎。[FishTtsEngine] 是当前唯一的实现：正文交给 Fish Audio 的免费模型合成，
 * 拿回 mp3 在本地播放。
 *
 * 留着这层接口而不是把 Fish 直接写进 [VoiceReader]：切分、音频焦点、一次只读一条这些约束
 * 都在接口**上面**，换引擎时不该动它们。
 */
interface TtsEngine {
  val id: String

  val displayName: String

  /** 一次 [speak] 能吃的字符上限。VoiceReader 按它切句。 */
  val maxChunk: Int

  /**
   * 上一次 [speak] 失败的用户可见原因；成功或还没失败过时为 null。
   *
   * 云端引擎的失败都有具体原因（key 无效、余额不足、限流），只给界面一个 false
   * 会让用户看到「朗读中断」这种什么也没说的话。
   */
  val lastError: String?
    get() = null

  /** 该引擎是否可用（例如还没配置 API key）。 */
  fun isAvailable(): Boolean

  /** 不可用时的原因，直接展示给用户。 */
  fun unavailableReason(): String?

  /** 准备好底层引擎。返回 null = 成功，否则是用户可见的失败原因。 */
  suspend fun initialize(): String?

  /** 当前可选的音色；没有可选项时返回空表。 */
  fun voices(): List<TtsVoice>

  /** 选中一个音色；null = 用引擎默认。 */
  fun selectVoice(id: String?)

  /**
   * 预取一段的音频（可选，默认空实现）。[VoiceReader] 在播放当前段时预取下一段，
   * 把「下一句的网络往返」藏进这一段的播放时间里 —— 否则每句之间会空好几秒。
   */
  suspend fun prefetch(text: String) = Unit

  /**
   * 读一段，挂起直到读完、被打断或出错。
   *
   * @return true = 正常读完；false = 被 [stop] 打断或引擎报错（原因见 [lastError]）。
   */
  suspend fun speak(text: String): Boolean

  fun stop()

  fun release()
}

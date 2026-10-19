package com.tappet.app.voice

/** 一个可选音色。系统引擎给的是它自己的 voices；本地引擎给的是模型自带的那一种。 */
data class TtsVoice(
  val id: String,
  val label: String,
)

/**
 * 朗读引擎。目前两个实现：设备自带的系统 TTS（[SystemTtsEngine]，零依赖、离线）与
 * 可下载的开源本地引擎（[LocalTtsEngine]）。
 *
 * 切成接口是为了让「用哪个引擎」是**用户的一个设置**，而不是代码里的一个分支：
 * 系统引擎装完就能用但音色随 ROM，本地引擎音色好但要多下一个模型 —— 两种取舍都成立，
 * 让用户按自己的网络与耳朵挑。
 *
 * 长文本的切分**不在这里**（在 [SpeakableText.chunks]，由 [VoiceReader] 驱动）：
 * 引擎只负责「把这一段读出来」，这样两个引擎的差异面小到只有 [maxChunk] 一个数字。
 */
interface TtsEngine {
  val id: String

  val displayName: String

  /** 一次 [speak] 能吃的字符上限。VoiceReader 按它切句。 */
  val maxChunk: Int

  /** 该引擎是否可用（本地引擎没下模型时不可用）。 */
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
   * 读一段，挂起直到读完、被打断或出错。
   *
   * @return true = 正常读完；false = 被 [stop] 打断或引擎报错。
   */
  suspend fun speak(text: String): Boolean

  fun stop()

  fun release()
}

package com.tappet.app.voice

import android.content.Context

/**
 * 本地开源引擎的占位实现 —— **真正的推理还没接上**（下一步接 sherpa-onnx + 中文 VITS 模型）。
 *
 * 先把壳放在这里，是为了让「换引擎」这条路径从第一天就是完整的：设置页能选、[VoiceReader]
 * 能建、失败能如实报错。等推理接上时，改的只有这个文件。
 *
 * 它**不联网、不落盘、不读正文**，只是把「还没装模型」这件事说清楚 —— 比静默退回系统引擎好：
 * 用户明确选了本地引擎，就不该在背后被换成另一个声音。
 */
class LocalTtsEngine(
  @Suppress("UNUSED_PARAMETER") private val context: Context,
  private val modelId: String?,
  @Suppress("UNUSED_PARAMETER") private val rate: Float,
) : TtsEngine {
  override val id: String = VoiceEngines.LOCAL
  override val displayName: String = "本地引擎"

  /** 本地引擎按句合成，每段都是独立的一次前向；切短一点，停止才跟手。 */
  override val maxChunk: Int = 200

  override fun isAvailable(): Boolean = false

  override fun unavailableReason(): String? =
    if (modelId == null) "还没下载语音模型" else "本地引擎的推理还没接上"

  override suspend fun initialize(): String? = unavailableReason()

  override fun voices(): List<TtsVoice> = emptyList()

  override fun selectVoice(id: String?) = Unit

  override suspend fun speak(text: String): Boolean = false

  override fun stop() = Unit

  override fun release() = Unit
}

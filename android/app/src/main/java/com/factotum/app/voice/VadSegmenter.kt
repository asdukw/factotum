package com.factotum.app.voice

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 「哪几段是人在说话」—— 用 **sherpa-onnx 自带的 silero VAD**，不是自己算能量阈值。
 *
 * 早先这里是一个手写的 RMS 端点判定（`EndpointDetector`），已经删掉。换掉的理由是实测的：
 * 把几秒环境噪声交给识别模型，它会「听」出一句像模像样的话然后落进输入框
 * （真机实测：静音 5 秒 → 7~11 个字），而**靠调阈值堵不住** —— 安静房间里的底噪照样越线。
 * 而 VAD 前置过滤正是 whisper 系模型幻觉的公认主修法（faster-whisper 的 `vad_filter`、
 * WhisperX 都这么干），sherpa-onnx 又恰好自带：`silero_vad.onnx` 629KB，打进 assets，
 * 不让用户为它多下一样东西。
 *
 * ## 三个阈值都是「按自己的取舍给值」，所以写在这里而不是用上游的工厂函数
 *
 * - [THRESHOLD] 0.5：上游默认，也是 silero 的标准值。调高会更保守（宁可漏掉轻声说话），
 *   调低会把环境音当说话 —— 而后者正是我们要摆脱的那个失败模式，所以不往下调。
 * - [MIN_SILENCE_MS] 800：**这就是「说完了」**。短了会在换气的停顿上断句（一句话被切成
 *   两段、识别质量跟着掉），长了显得反应慢。上游默认 0.25 秒是给「切文件」用的，不是
 *   给「按住说话」用的。
 * - [MAX_SPEECH_MS] 10 秒：一段最长切多长。**这里要的是「别在句子中间切」**：切得短会
 *   把一句话截成几段（每段单独识别，上下文没了）；切得长则界面上半天不出字。10 秒是
 *   这两头之间的取值。切到上限时 [isSpeechDetected] 仍为真 —— 调用方据此区分
 *   「因为静音结束」与「因为切太长了」，别把后者当成用户说完了（见 [dictationEnd]）。
 *
 * ## 用法（与上游 `vad-with-non-streaming-asr.py` 同一个循环）
 *
 * [accept] 喂音频 → [drain] 取走已经说完的段 → 段样本交给识别器。**只喂 VAD 认过的段**，
 * 环境噪声根本不会出现在里面 —— 「静音喂出幻觉文本」于是结构上不可能，而不是靠一个判据挡。
 *
 * 窗口必须是 [WINDOW_SIZE] 个样本（silero 的硬要求），所以这里自己攒够一个窗口再喂：
 * 调用方给多碎的块都行。
 */
internal class VadSegmenter(assetManager: AssetManager) {
  private val vad = Vad(assetManager, config())

  /** 攒不够一个窗口的余量。见类注释。 */
  private var pending = FloatArray(WINDOW_SIZE * 4)

  private var pendingSize = 0

  private val ready = ArrayList<FloatArray>()

  /**
   * 喂一段音频。块大小随意，内部按 [WINDOW_SIZE] 切。
   *
   * 返回**已经说完**的那几段（可能为空）。取走之后 VAD 内部的队列就空了。
   */
  fun accept(samples: FloatArray): List<FloatArray> {
    compact(samples.size)
    samples.copyInto(pending, pendingSize)
    pendingSize += samples.size

    var offset = 0
    while (pendingSize - offset >= WINDOW_SIZE) {
      vad.acceptWaveform(pending.copyOfRange(offset, offset + WINDOW_SIZE))
      offset += WINDOW_SIZE
    }
    if (offset > 0) {
      pending.copyInto(pending, 0, offset, pendingSize)
      pendingSize -= offset
    }
    return drain()
  }

  /** 此刻是不是有人在说话。切到 [MAX_SPEECH_MS] 上限时它**仍然**为真，见类注释。 */
  fun isSpeechDetected(): Boolean = vad.isSpeechDetected()

  /**
   * 音频到此为止：把**还没闭合的那一段**收出来。
   *
   * **不调它就丢尾巴**，而且丢得很难看：段是在「后面跟了 [MIN_SILENCE_MS] 的静音」时才
   * 闭合的，所以正在说的最后一句永远悬着。用户说完立刻按停（正是最常见的操作）、以及拿
   * 一段 WAV 喂进来验（文件末尾没有静音）这两种情形都会命中 —— 实测就是这样：真实中文
   * 样例喂进去切出 **0 段**，加上 `flush()` 才有。
   *
   * 只能在采集线程已经停掉之后调（`Vad` 不是线程安全的，见 [VoiceInput] 的收尾顺序）。
   */
  fun finish(): List<FloatArray> {
    vad.flush()
    return drain()
  }

  fun release() {
    try {
      vad.release()
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      // 释放失败不该盖住真正的失败原因（与 LocalRecognizer 同一条规矩）。
      Log.w(TAG, "VAD 释放失败：${error.javaClass.simpleName}")
    }
  }

  private fun drain(): List<FloatArray> {
    ready.clear()
    while (!vad.empty()) {
      ready.add(vad.front().samples)
      vad.pop()
    }
    return ready.toList()
  }

  /**
   * 给新来的样本腾地方。
   *
   * **不能只往大里长**：一次说几十秒时，余量最多只有半个窗口，而缓冲区会一路涨到整段
   * 音频那么大。这里按「余量 + 新样本」重新开一块，旧的那块交给 GC。
   */
  private fun compact(incoming: Int) {
    if (pendingSize + incoming <= pending.size) return
    val kept = pending.copyOfRange(0, pendingSize)
    pending = FloatArray(maxOf(pendingSize + incoming, WINDOW_SIZE * 4))
    kept.copyInto(pending)
  }

  private companion object {
    const val TAG = "FactotumVoice"

    /** silero 在 16kHz 下的硬要求，不能改。 */
    const val WINDOW_SIZE = 512

    const val THRESHOLD = 0.5f

    const val MIN_SILENCE_MS = 800

    const val MIN_SPEECH_MS = 250

    const val MAX_SPEECH_MS = 10_000

    fun config() =
      VadModelConfig(
        sileroVadModelConfig =
          SileroVadModelConfig(
            // assets 里的文件名，交给 Vad(assetManager, ...) 从 APK 里读。
            model = "silero_vad.onnx",
            threshold = THRESHOLD,
            minSilenceDuration = MIN_SILENCE_MS / 1000f,
            minSpeechDuration = MIN_SPEECH_MS / 1000f,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = MAX_SPEECH_MS / 1000f,
          ),
        sampleRate = AudioCapture.SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
      )
  }
}

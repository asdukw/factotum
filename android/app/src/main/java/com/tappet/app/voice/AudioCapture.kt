package com.tappet.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

/**
 * 麦克风采集：16kHz 单声道，读出来直接是归一化的 `FloatArray`（sherpa-onnx 要的形态）。
 *
 * 采样率**固定 16kHz**：sherpa-onnx 的识别模型都是按 16kHz 训练的，而 AudioRecord 拿到的
 * 是设备原生采样率 —— 让系统去重采样（`AudioRecord` 支持非原生采样率）比自己写重采样可靠，
 * 代价是有的设备会因此多几毫秒延迟。
 *
 * 音源用 `VOICE_RECOGNITION` 而不是 `MIC`：前者是给识别用的（关掉 AGC、开降噪），
 * 也是系统语音助手的默认选择。注意它**因设备而异**：有的 ROM 会做激进降噪，
 * 把小声说话削成静音 —— 那种情况下 [EndpointDetector] 的自适应阈值会跟着降下来，仍能工作。
 *
 * 采集跑在自己的线程上：`AudioRecord.read` 是阻塞的，放在协程里会占住调度线程。
 * 停止靠 [running] 标志 + `read` 返回，**不打断线程**（打断阻塞的 native 读会留下半帧）。
 */
internal class AudioCapture(
  private val onFrame: (samples: FloatArray, rms: Float) -> Unit,
) {
  private var record: AudioRecord? = null

  private var thread: Thread? = null

  @Volatile private var running = false

  /** @return null = 开始成功，否则是给用户看的一句话。 */
  @SuppressLint("MissingPermission") // 调用方（VoiceInput）已经查过 RECORD_AUDIO，见那里的注释
  fun start(): String? {
    if (running) return null
    val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    if (minBytes <= 0) return "这台设备的麦克风不支持 16kHz 单声道采集"
    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        CHANNEL,
        ENCODING,
        // 缓冲给到「一帧的四倍」与系统下限的较大者：太小会丢帧（表现为识别缺字），
        // 太大则停止时要多等一个缓冲的时间才真的静下来。
        maxOf(minBytes * 2, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4),
      )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      return "麦克风打不开，可能被别的应用占着"
    }
    record = recorder
    running = true
    recorder.startRecording()
    thread = Thread({ pump(recorder) }, "tappet-audio").apply { start() }
    return null
  }

  fun stop() {
    if (!running) return
    running = false
    // 先等采集线程自己退出，再释放 —— 反过来的话 `read` 会撞上一个已经 release 的对象。
    thread?.let { runCatching { it.join(THREAD_JOIN_MS) } }
    thread = null
    record?.let { recorder ->
      runCatching { recorder.stop() }
      recorder.release()
    }
    record = null
  }

  private fun pump(recorder: AudioRecord) {
    val buffer = ShortArray(FRAME_SAMPLES)
    while (running) {
      val read = recorder.read(buffer, 0, buffer.size)
      if (read <= 0) {
        // 负数 = 设备没了（拔耳机、被抢占）；0 只是这一次没读到，继续。
        if (read < 0) {
          Log.w(TAG, "采集中断：read=$read")
          running = false
        }
        continue
      }
      val samples = FloatArray(read) { buffer[it] / SHORT_SCALE }
      onFrame(samples, rms(samples))
    }
  }

  private companion object {
    const val TAG = "TappetVoice"

    const val SAMPLE_RATE = 16_000
    const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** 一帧 32ms（512 个样本）—— 与常见 VAD 的帧长一致，端点上够细，开销可忽略。 */
    const val FRAME_SAMPLES = 512

    const val BYTES_PER_SAMPLE = 2

    /** PCM16 的满量程。用它把整数样本归一化到 [-1,1]。 */
    const val SHORT_SCALE = 32_768f

    const val THREAD_JOIN_MS = 500L

    fun rms(samples: FloatArray): Float {
      if (samples.isEmpty()) return 0f
      var sum = 0.0
      for (sample in samples) sum += sample.toDouble() * sample
      return sqrt(sum / samples.size).toFloat()
    }
  }
}

// Copyright (c)  2023  Xiaomi Corporation
//
// **VENDORED**：取自 https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/Vad.kt
// （Apache-2.0）。只按本仓库的格式重排了缩进，并删掉了文件末尾的工厂函数
// `getVadModelConfig`（与 `OfflineRecognizer.kt` 同样的处理）—— 那几个阈值是本仓库要
// 按自己的取舍给值的东西，写在 `voice/VadSegmenter.kt` 里，那里能写清为什么。
//
// 除上面这些只动了两处：`finalize()` 加了 `@Suppress("DEPRECATION")`（它调的是
// `Object.finalize`），类上加了 `@Suppress("TooManyFunctions")`（23 个方法，本仓库的阈值
// 是 20）。两处都是**原地抑制而不是改结构**：这一层的形状必须与上游一致，下次升级要对着
// diff 看，而 native 句柄的生命周期不该由我们重新设计。
// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class SileroVadModelConfig(
  var model: String = "",
  var threshold: Float = 0.5F,
  var minSilenceDuration: Float = 0.25F,
  var minSpeechDuration: Float = 0.25F,
  var windowSize: Int = 512,
  var maxSpeechDuration: Float = 5.0F,
)

data class TenVadModelConfig(
  var model: String = "",
  var threshold: Float = 0.5F,
  var minSilenceDuration: Float = 0.25F,
  var minSpeechDuration: Float = 0.25F,
  var windowSize: Int = 256,
  var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
  var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
  var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
  var sampleRate: Int = 16000,
  var numThreads: Int = 1,
  var provider: String = "cpu",
  var debug: Boolean = false,
)

class SpeechSegment(val start: Int, val samples: FloatArray)

@Suppress("TooManyFunctions")
class Vad(
  assetManager: AssetManager? = null,
  var config: VadModelConfig,
) {
  private var ptr: Long

  init {
    if (assetManager != null) {
      ptr = newFromAsset(assetManager, config)
    } else {
      ptr = newFromFile(config)
    }
    require(ptr != 0L) {
      "Invalid VadConfig: failed to create native Vad"
    }
  }

  @Suppress("DEPRECATION")
  protected fun finalize() {
    if (ptr != 0L) {
      delete(ptr)
      ptr = 0
    }
  }

  fun release() = finalize()

  fun compute(samples: FloatArray): Float = compute(ptr, samples)

  fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)

  fun empty(): Boolean = empty(ptr)

  fun pop() = pop(ptr)

  fun front(): SpeechSegment = front(ptr)

  fun clear() = clear(ptr)

  fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)

  fun reset() = reset(ptr)

  fun flush() = flush(ptr)

  private external fun delete(ptr: Long)

  private external fun newFromAsset(
    assetManager: AssetManager,
    config: VadModelConfig,
  ): Long

  private external fun newFromFile(
    config: VadModelConfig,
  ): Long

  private external fun acceptWaveform(
    ptr: Long,
    samples: FloatArray,
  )

  private external fun compute(
    ptr: Long,
    samples: FloatArray,
  ): Float

  private external fun empty(ptr: Long): Boolean

  private external fun pop(ptr: Long)

  private external fun clear(ptr: Long)

  private external fun front(ptr: Long): SpeechSegment

  private external fun isSpeechDetected(ptr: Long): Boolean

  private external fun reset(ptr: Long)

  private external fun flush(ptr: Long)

  companion object {
    init {
      System.loadLibrary("sherpa-onnx-jni")
    }
  }
}

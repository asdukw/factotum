// Copyright (c)  2024  Xiaomi Corporation
//
// **VENDORED**：取自 https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OfflinePunctuation.kt
// （Apache-2.0）。只按本仓库的格式重排了缩进，并给 `finalize()` 加了
// `@Suppress("DEPRECATION")`（它调的是 `Object.finalize`）—— 与 `Vad.kt` / `Tts.kt` 同样的处理。
//
// **不要手改这个文件的其余部分**：JNI 那一侧按 Kotlin 对象的字段名取值，少一个字段不会
// 编译报错，会在 native 层崩。升级 sherpa-onnx 时连同 tools/fetch-sherpa.ps1 的版本号一起换，
// 并且保持这些文件与 Tts.kt 同 tag。
//
// 这个绑定对应的 native 符号（`Java_com_k2fsa_sherpa_onnx_OfflinePunctuation_*`）**本来就在**
// `libsherpa-onnx-jni.so` 里 —— 加标点不需要重新编译 native，只差这一层 Kotlin 声明。
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflinePunctuationModelConfig(
  var ctTransformer: String = "",
  var numThreads: Int = 1,
  var debug: Boolean = false,
  var provider: String = "cpu",
)

data class OfflinePunctuationConfig(
  var model: OfflinePunctuationModelConfig,
)

class OfflinePunctuation(
  assetManager: AssetManager? = null,
  var config: OfflinePunctuationConfig,
) {
  private var ptr: Long

  init {
    ptr =
      if (assetManager != null) {
        newFromAsset(assetManager, config)
      } else {
        newFromFile(config)
      }
    require(ptr != 0L) {
      "Invalid OfflinePunctuationConfig: failed to create native OfflinePunctuation"
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

  fun addPunctuation(text: String) = addPunctuation(ptr, text)

  private external fun delete(ptr: Long)

  private external fun addPunctuation(
    ptr: Long,
    text: String,
  ): String

  private external fun newFromAsset(
    assetManager: AssetManager,
    config: OfflinePunctuationConfig,
  ): Long

  private external fun newFromFile(
    config: OfflinePunctuationConfig,
  ): Long

  companion object {
    init {
      System.loadLibrary("sherpa-onnx-jni")
    }
  }
}

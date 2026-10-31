// Copyright (c)  2023  Xiaomi Corporation
//
// **VENDORED**：取自 https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/WaveReader.kt
// （Apache-2.0）。只按本仓库的格式重排了缩进，其余一字未改。
//
// 用途只有一个：debug 构建里的 `VoiceDebugReceiver` 要把一段 WAV 喂给识别引擎
// （见 `docs/voice.md` 的「验证」）。产品路径上不读 WAV —— 音频直接来自 AudioRecord。
// 它读的是 **mono** wav，16kHz 的样例音频正好符合。
//
// 除上面这段说明外只动了一处：给 `WaveReader` 加了一行 `@Suppress`。
// 它是个只有伴生函数的工具类，detekt 的 UtilityClassWithPublicConstructor 会报它；
// 改成 object 就与上游不一样了（native 那一侧不关心，但下次升级要对着 diff 看），
// 所以宁可在原地抑制并写清理由。
// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class WaveData(
  val samples: FloatArray,
  val sampleRate: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as WaveData

    if (!samples.contentEquals(other.samples)) return false
    if (sampleRate != other.sampleRate) return false

    return true
  }

  override fun hashCode(): Int {
    var result = samples.contentHashCode()
    result = 31 * result + sampleRate
    return result
  }
}

@Suppress("UtilityClassWithPublicConstructor")
class WaveReader {
  companion object {
    fun readWave(
      assetManager: AssetManager,
      filename: String,
    ): WaveData = readWaveFromAsset(assetManager, filename)

    fun readWave(
      filename: String,
    ): WaveData = readWaveFromFile(filename)

    // Read a mono wave file asset
    external fun readWaveFromAsset(
      assetManager: AssetManager,
      filename: String,
    ): WaveData

    // Read a mono wave file from disk
    external fun readWaveFromFile(
      filename: String,
    ): WaveData

    init {
      System.loadLibrary("sherpa-onnx-jni")
    }
  }
}

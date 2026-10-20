package com.tappet.app.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 仅 debug 构建存在：绕过麦克风，把一段 WAV 喂给识别引擎。
 *
 * 为什么需要它：语音输入这条路上，「模型下好了没有」「native 引擎起不起得来」
 * 「三种模型的配置拼得对不对」这些事**都能在没有人的情况下验**，只有「麦克风收到的是不是
 * 人声」需要用户开口。把 WAV 这条入口留着，验证就不必每次拉上一个人对着手机说话 ——
 * 与 `DeviceActionReceiver`（执行层）和 `NodeDebugReceiver`（运行时）同一个理由。
 *
 * 样例音频就在模型归档里（`test_wavs/` 下的 `0.wav` 那几个，16kHz mono），推到设备上即可：
 *
 * ```
 * adb push <模型目录>/test_wavs/0.wav /data/local/tmp/asr/
 * adb shell am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver \
 *   -a com.tappet.app.debug.VOICE --es op ASR --es wav /data/local/tmp/asr/0.wav
 * ```
 *
 * **识别正文默认不打印**（与 `--ez logPayload` 同一条规矩：屏幕 / 正文不进日志）。
 * 验的是「这条路通不通、出多少字、多久」，要看文本时显式加 `--ez logPayload true` ——
 * 那时打印的是**你推上去的那段测试音频**，不是用户说的话。
 */
class VoiceDebugReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    val op = intent.getStringExtra(EXTRA_OP) ?: return
    val app = context.applicationContext
    when (op) {
      "ASR" -> {
        val wav = intent.getStringExtra(EXTRA_WAV) ?: return
        val logPayload = intent.getBooleanExtra(EXTRA_LOG_PAYLOAD, false)
        val modelId = intent.getStringExtra(EXTRA_MODEL) ?: VoiceSettings.asrModel(app)
        // 广播立即返回，识别在进程内异步跑（Broadcast Timeout 是 60 秒，
        // 而首次识别要把上百 MB 的模型读进内存）。
        CoroutineScope(Dispatchers.IO).launch { transcribe(app, wav, modelId, logPayload) }
      }

      "INSTALL" -> {
        val model = AsrModelCatalog.byId(intent.getStringExtra(EXTRA_MODEL))
        if (model == null) {
          Log.w(TAG, "INSTALL 需要一个目录里的 model id")
          return
        }
        CoroutineScope(Dispatchers.IO).launch { install(app, model) }
      }

      "MODELS" -> logModels(app)

      else -> Log.w(TAG, "未知 op=$op")
    }
  }

  /**
   * 走**和设置页完全相同**的那条路（[VoiceModelInstaller] → 下载 → 解包 → 安装），
   * 只是入口换成了广播。验的是「这条链路在真机上通不通」——
   * 它包含断点续传、tar.bz2 解包、原子换目录，全都是没有设备就验不了的部分。
   */
  private suspend fun install(
    app: Context,
    model: AsrModel,
  ) {
    Log.i(TAG, "install start id=${model.id} bytes=${model.sizeBytes}")
    VoiceModelInstaller.install(app, VoiceModelKinds.ASR, model)
    VoiceModelInstaller.state.collect { state ->
      when {
        state == null -> {
          val installed = VoiceModelStore.isInstalled(app, VoiceModelKinds.ASR, model)
          Log.i(TAG, "install done id=${model.id} installed=$installed")
          return@collect
        }

        state.error != null -> {
          Log.e(TAG, "install failed id=${model.id} reason=${state.error}")
          return@collect
        }

        else ->
          Log.i(
            TAG,
            "install progress id=${model.id} ${state.progress?.let { it::class.simpleName } ?: "starting"}",
          )
      }
    }
  }

  private suspend fun transcribe(
    app: Context,
    wav: String,
    modelId: String?,
    logPayload: Boolean,
  ) {
    // 先看一眼文件本身：读不到时，「文件没权限」与「native 读不了」是两回事，
    // 而这句日志是分清它们最快的办法。
    val file = java.io.File(wav)
    Log.i(TAG, "wav file readable=${file.canRead()} bytes=${file.length()}")
    val wave =
      try {
        WaveReader.readWave(wav)
      } catch (
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.e(TAG, "读 WAV 失败：$wav", error)
        return
      }
    Log.i(TAG, "wav ready samples=${wave.samples.size} rate=${wave.sampleRate} model=${modelId ?: "none"}")

    val engine = LocalRecognizer(app, modelId)
    val initError = engine.initialize()
    if (initError != null) {
      Log.e(TAG, "引擎不可用：$initError")
      return
    }
    val startedAt = System.currentTimeMillis()
    val text = engine.transcribe(wave.samples)
    Log.i(
      TAG,
      "asr done chars=${text.length} ms=${System.currentTimeMillis() - startedAt}" +
        if (logPayload) " text=$text" else " (加 --ez logPayload true 才打印文本)",
    )
    engine.release()
  }

  /**
   * 打印目录里每个模型在这台设备上的状态。**没有正文**，只有 id / 装没装 / 占多大 ——
   * 排「模型下到一半」这类问题时，这比翻设置页快。
   */
  private fun logModels(app: Context) {
    AsrModelCatalog.ALL.forEach { model ->
      val installed = VoiceModelStore.isInstalled(app, VoiceModelKinds.ASR, model)
      val bytes = VoiceModelStore.installedBytes(app, VoiceModelKinds.ASR, model.id)
      val partial = VoiceModelDownloader.partialBytes(app, VoiceModelKinds.ASR, model.id)
      Log.i(TAG, "asr model id=${model.id} installed=$installed bytes=$bytes partial=$partial")
    }
    Log.i(TAG, "asr selected=${VoiceSettings.asrModel(app) ?: "none"}")
  }

  private companion object {
    const val TAG = "TappetVoice"
    const val EXTRA_OP = "op"
    const val EXTRA_WAV = "wav"
    const val EXTRA_MODEL = "model"
    const val EXTRA_LOG_PAYLOAD = "logPayload"
  }
}

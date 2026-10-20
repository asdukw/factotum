package com.tappet.app.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 给识别结果补标点：sherpa-onnx 的 `OfflinePunctuation`（CT-Transformer）+ [PunctModelCatalog]
 * 里那个模型。
 *
 * 只有 paraformer 那条路线需要它（见 `AsrEngineSpec.needsPunctuation`），而且**它是改善，
 * 不是前置条件**：没装标点模型时 [VoiceInput] 直接用识别原文，不会因此说「语音输入不可用」。
 * 这一点决定了这里的失败处理与 [LocalRecognizer] 不同 —— 那边失败等于这次输入没成，
 * 这边失败只是少几个逗号。
 *
 * ## 与 [LocalRecognizer] 同一道闸门、同一个理由
 *
 * `OfflinePunctuation` 也不是线程安全的，而协程取消**打断不了阻塞的 native 调用**：
 * 并发进去就是拿一个正在被使用的 `Ort::Session` 去 `delete`，表现是整个 App 闪退。
 * 所以同一时刻只允许一次调用（[gate]），销毁只能发生在没人用的时候（[destroyIfIdle]）。
 * 那两条的完整来由写在 [LocalRecognizer] 的类注释里，这里不重复。
 *
 * **文本不进日志**：这里只记字符数与耗时。识别正文与标点结果是同一份东西。
 */
internal class LocalPunctuator(context: Context) {
  private val appContext = context.applicationContext

  private var punctuator: OfflinePunctuation? = null

  /** 调用的独占闸门。见类注释。 */
  private val gate = Semaphore(1)

  /** 已经被换掉、等着销毁。真正 `delete` 的时机由 [destroyIfIdle] 决定。 */
  @Volatile private var released = false

  private val model: PunctModel? = PunctModelCatalog.byId(PunctModelCatalog.installedId(appContext))

  fun isAvailable(): Boolean = model != null && isInstalled(model)

  /** 模型已经读进内存了吗。 */
  fun isReady(): Boolean = punctuator != null

  /** 准备好引擎。返回 null = 成功，否则是给用户看的一句话。 */
  suspend fun initialize(): String? {
    if (punctuator != null) return null
    val selected = model ?: return "还没下载标点模型"
    if (!isInstalled(selected)) return "标点模型「${selected.label}」还没下载完"
    return withContext(Dispatchers.IO) {
      val dir = VoiceModelStore.modelDir(appContext, VoiceModelKinds.PUNCT, selected.id)
      try {
        punctuator =
          OfflinePunctuation(
            config =
              OfflinePunctuationConfig(
                model =
                  OfflinePunctuationModelConfig(
                    ctTransformer = File(dir, selected.modelFile).absolutePath,
                    numThreads = threadsForDecoding(),
                    debug = false,
                    provider = "cpu",
                  ),
              ),
          )
        null
      } catch (
        // 刻意的兜底：模型文件坏了、与 native 库版本对不上、内存不够，都从这里出来。
        @Suppress("TooGenericExceptionCaught") error: Throwable,
      ) {
        Log.w(TAG, "标点引擎初始化失败：${error.javaClass.simpleName}")
        "标点引擎加载模型失败：${error.message ?: error.javaClass.simpleName}"
      }
    }
  }

  /**
   * 给一段文本补标点。
   *
   * @return 补好的文本；**失败或结果为空时原样返回 [text]** —— 这条路线的契约是「标点是
   *   锦上添花」，任何异常都不该让用户刚说出来的那句话消失。
   */
  suspend fun addPunctuation(text: String): String {
    if (text.isBlank()) return text
    if (released) return text
    val engine = punctuator ?: return text
    return withContext(Dispatchers.IO) {
      if (!gate.tryAcquire(GATE_WAIT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(TAG, "上一次标点调用还没退出 native，这一段原样返回")
        return@withContext text
      }
      try {
        if (released) return@withContext text
        run(engine, text)
      } finally {
        gate.release()
        destroyIfIdle()
      }
    }
  }

  private fun run(
    engine: OfflinePunctuation,
    text: String,
  ): String {
    val startedAt = System.currentTimeMillis()
    return try {
      val punctuated = engine.addPunctuation(text)
      // 只记长度与耗时：正文不进日志。
      Log.i(
        TAG,
        "标点完成 chars=${text.length}->${punctuated.length} ms=${System.currentTimeMillis() - startedAt}",
      )
      punctuated
    } catch (
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      Log.w(TAG, "标点失败：${error.javaClass.simpleName}")
      text
    }
  }

  /** 标记这个引擎作废，并**在安全的时候**销毁 native 对象。理由同 [LocalRecognizer.release]。 */
  fun release() {
    released = true
    destroyIfIdle()
  }

  /**
   * **被换掉之后**、且没有调用在跑时，才真的销毁。
   *
   * `if (!released) return` 这一行不能省 —— 没有它的话每次调用结束都会把引擎释放掉。
   */
  private fun destroyIfIdle() {
    if (!released) return
    if (!gate.tryAcquire()) return
    try {
      punctuator?.release()
      punctuator = null
    } finally {
      gate.release()
    }
  }

  private fun isInstalled(model: PunctModel): Boolean =
    VoiceModelStore.isInstalled(appContext, VoiceModelKinds.PUNCT, model)

  /** 与识别那边同一个上限：给满会发烫掉电，给太少又跟不上。 */
  private fun threadsForDecoding(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

  private companion object {
    const val TAG = "TappetVoice"
    const val MAX_THREADS = 4

    /** 等上一次调用退出 native 的上限。 */
    const val GATE_WAIT_MS = 5_000L
  }
}

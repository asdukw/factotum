package com.tappet.app.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 模型下载的**唯一状态源**，进程内单例。**合成与识别共用**（见 [VoiceModel]）。
 *
 * 与 `UpdateManager` 同一个理由：如果下载挂在设置页的 ViewModel 上，用户返回上一级就会
 * 把下载连带取消 —— 而「正在下 80MB」这件事与哪个页面开着没有关系。放在单例里，
 * 关掉设置页下载照跑，回来还能看到进度。
 *
 * 同时只装一个：两个下载并行会抢同一份 staging 目录（[VoiceModelDownloader] 的前提），
 * 而且合成模型与识别模型用的是同一块磁盘、同一条网络。再点另一个模型 = 取消当前的、开始新的。
 *
 * **不落日志正文**：这里只有种类、模型 id、字节数与失败原因。
 */
object VoiceModelInstaller {
  data class State(
    val kind: String,
    val modelId: String,
    val progress: VoiceModelDownloader.Progress? = null,
    val error: String? = null,
  ) {
    val running: Boolean get() = error == null
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<State?>(null)

  /** 正在装哪个模型、到哪一步了；null = 没有在装。 */
  val state: StateFlow<State?> = _state.asStateFlow()

  private var job: Job? = null

  /** 装完 / 失败后由界面调一次，把状态清掉。 */
  fun clear() {
    if (job?.isActive != true) _state.value = null
  }

  fun install(
    context: Context,
    kind: String,
    model: VoiceModel,
  ) {
    val app = context.applicationContext
    job?.cancel()
    _state.value = State(kind, model.id)
    job =
      scope.launch {
        try {
          // 先装本体、再装伴生模型（[VoiceModel.companions]，目前就是标点那种）：
          // 中途取消时留下的是「识别能用、标点还差一点」，反过来则是一份没人用得上的标点。
          // **已经装好的跳过** —— 老用户升级后点一次「补下」只拉缺的那一份，不会把 82MB 的
          // 识别模型再下一遍。
          //
          // 进度条会**从头走两遍**（两条归档是两次下载），这是如实反映而不是 bug：
          // 界面上那一行的按钮写的就是两样加起来的大小。
          for (target in listOf(model) + model.companions) {
            if (VoiceModelStore.isInstalled(app, target.kind, target)) continue
            VoiceModelDownloader.install(app, target.kind, target) { progress ->
              _state.value = State(kind, model.id, progress = progress)
            }
          }
          _state.value = null
        } catch (cancelled: CancellationException) {
          _state.value = null
          throw cancelled
        } catch (
          // 兜底：网络断、磁盘满、归档坏了都从这里出去，消息直接给用户看。
          @Suppress("TooGenericExceptionCaught") error: Throwable,
        ) {
          _state.value = State(kind, model.id, error = error.message ?: error.javaClass.simpleName)
        }
      }
  }

  fun cancel() {
    job?.cancel()
    job = null
    _state.value = null
  }
}

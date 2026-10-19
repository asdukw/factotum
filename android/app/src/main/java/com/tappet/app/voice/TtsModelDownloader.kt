package com.tappet.app.voice

import android.content.Context
import com.tappet.app.update.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把一个模型下下来、解开、放到该在的地方。
 *
 * 分三步，每一步失败都不会留下「看起来装好了」的目录：
 *
 * 1. **下**：复用 [UpdateDownloader.fetchInto] —— 它已经处理好了断点续传的三条细节
 *    （`If-Range` 而不是裸 `Range`、206 的 Content-Length 是剩余长度、416 要丢掉本地重来）。
 *    一百多 MB 的模型在城市地铁里重下三遍是很实在的痛，而那段逻辑没必要写第二遍。
 *    归档留在 cacheDir 的 `.part` 里，跨进程存活，中断后接着下。
 * 2. **解**：解到 `.staging-<id>`，剥掉归档的顶层目录（见 [TtsArchive]）。
 * 3. **换**：删掉旧的正式目录，再把 staging rename 成正式目录。同目录下 rename 是原子的，
 *    所以「装好了」这个状态不会出现半成品 —— 直接解到正式目录就会。
 */
object TtsModelDownloader {
  /** 下载 / 解包到哪一步了。解包没有细粒度进度（tar.bz2 要顺序解），所以只有阶段。 */
  sealed interface Progress {
    data class Downloading(val done: Long, val total: Long) : Progress {
      val known: Boolean get() = total > 0

      val percent: Int get() = if (known) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    data object Extracting : Progress
  }

  /**
   * 下载并安装 [model]。失败时抛异常，消息是给用户看的一句话。
   *
   * 调用方负责「同时只装一个」—— 两个下载并行会抢同一份 staging 目录。
   */
  suspend fun install(
    context: Context,
    model: TtsModel,
    onProgress: (Progress) -> Unit = {},
  ) = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    TtsModelStore.pruneStaging(app)

    val part = TtsModelStore.archivePart(app, model.id)
    val etag = TtsModelStore.archiveEtag(app, model.id)
    UpdateDownloader.fetchInto(model.url, part, etag) { onProgress(Progress.Downloading(it.done, it.total)) }

    onProgress(Progress.Extracting)
    val staging = TtsModelStore.stagingDir(app, model.id)
    try {
      TtsArchive.extract(archive = part, stripRoot = model.archiveRoot, dest = staging)
      // 解出来的东西必须真的齐 —— 归档换了内容、或者我们目录表写错了，都在这里拦下，
      // 而不是等用户点朗读时崩在 native 层。
      if (!hasRequiredFiles(staging, model)) error("归档里缺文件，模型可能换了内容")
    } catch (
      // 刻意的兜底：解包可能因为归档坏了、磁盘满、进程被限流而以任何异常失败，
      // 而**任何一种都必须先清掉半成品再抛** —— 留下半个目录会让下次「装好了」的判断出错。
      @Suppress("TooGenericExceptionCaught") error: Throwable,
    ) {
      staging.deleteRecursively()
      throw error
    }

    val target = TtsModelStore.modelDir(app, model.id)
    target.deleteRecursively()
    if (!staging.renameTo(target)) {
      staging.deleteRecursively()
      error("无法把模型放到 ${target.name}")
    }
    // 归档用完了就删：一百多 MB 没必要在 cacheDir 里留第二份。
    part.delete()
    etag.delete()
  }

  /** 已经下好但没解开的归档有多大（界面上显示「继续下载」时用）。 */
  fun partialBytes(context: Context, id: String): Long =
    TtsModelStore.archivePart(context, id).takeIf { it.isFile }?.length() ?: 0L

  private fun hasRequiredFiles(dir: File, model: TtsModel): Boolean =
    (listOf(model.modelFile, model.tokensFile, model.lexiconFile) + model.ruleFsts).all { File(dir, it).isFile }
}

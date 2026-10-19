package com.tappet.app.voice

import android.content.Context
import java.io.File

/**
 * 本地模型落在哪、装没装、占多大、怎么删。
 *
 * 位置是 `filesDir/tts-models/<id>/`（**App 私有目录**）：模型不是用户文档，不该出现在
 * 相册 / 文件管理器里，也不该被别的应用读到。卸载即清空 —— 与 `tappet.db` 同一条边界，
 * 只是模型可以重下，所以不需要导出。
 *
 * 「装好了没有」的判据是**关键文件都在**，不是「目录存在」：解包到一半被杀会留下半个目录，
 * 拿它去建引擎会在 native 层崩，而崩在哪很难看出来。
 */
object TtsModelStore {
  private const val ROOT = "tts-models"

  /** 解包期间的暂存目录前缀。解完才 rename 成正式目录，中途失败不会污染 [modelDir]。 */
  private const val STAGING_PREFIX = ".staging-"

  fun modelDir(context: Context, id: String): File = File(root(context), id)

  fun stagingDir(context: Context, id: String): File = File(root(context), "$STAGING_PREFIX$id")

  /** 关键文件齐了才算装好。 */
  fun isInstalled(context: Context, model: TtsModel): Boolean {
    val dir = modelDir(context, model.id)
    if (!dir.isDirectory) return false
    return requiredFiles(model).all { File(dir, it).isFile }
  }

  fun installedBytes(context: Context, id: String): Long {
    val dir = modelDir(context, id)
    if (!dir.isDirectory) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  /** 删掉模型（含残留的暂存目录）。用户在设置页点「删除」时调用。 */
  fun delete(context: Context, id: String) {
    modelDir(context, id).deleteRecursively()
    stagingDir(context, id).deleteRecursively()
  }

  /**
   * 清掉所有**没装完的残留**：暂存目录，以及解包失败的半成品。
   *
   * 在下载开始前调一次即可 —— 这类残留只可能来自「上一次下到一半被杀」，
   * 而那时用户已经在重下了。
   */
  fun pruneStaging(context: Context) {
    root(context).listFiles()?.forEach { file ->
      if (file.isDirectory && file.name.startsWith(STAGING_PREFIX)) file.deleteRecursively()
    }
  }

  /** 已下载归档的暂存位置（断点续传靠它跨进程存活）。 */
  fun archivePart(context: Context, id: String): File = File(context.cacheDir, "tts-$id.tar.bz2.part")

  fun archiveEtag(context: Context, id: String): File = File(context.cacheDir, "tts-$id.tar.bz2.part.etag")

  private fun requiredFiles(model: TtsModel): List<String> =
    listOf(model.modelFile, model.tokensFile, model.lexiconFile) + model.ruleFsts

  private fun root(context: Context): File =
    File(context.filesDir, ROOT).apply { if (!isDirectory) mkdirs() }
}

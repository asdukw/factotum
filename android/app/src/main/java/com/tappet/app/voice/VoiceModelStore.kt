package com.tappet.app.voice

import android.content.Context
import java.io.File

/**
 * 本地语音模型落在哪、装没装、占多大、怎么删。**合成与识别共用**（见 [VoiceModel]）。
 *
 * 位置是 `filesDir/<kind>-models/<id>/`（**App 私有目录**）：模型不是用户文档，不该出现在
 * 相册 / 文件管理器里，也不该被别的应用读到。卸载即清空 —— 与 `tappet.db` 同一条边界，
 * 只是模型可以重下，所以不需要导出。
 *
 * 「装好了没有」的判据是**关键文件都在**，不是「目录存在」：解包到一半被杀会留下半个目录，
 * 拿它去建引擎会在 native 层崩，而崩在哪很难看出来。
 */
object VoiceModelStore {
  /** 解包期间的暂存目录前缀。解完才 rename 成正式目录，中途失败不会污染 [modelDir]。 */
  private const val STAGING_PREFIX = ".staging-"

  fun modelDir(
    context: Context,
    kind: String,
    id: String,
  ): File = File(root(context, kind), id)

  fun stagingDir(
    context: Context,
    kind: String,
    id: String,
  ): File = File(root(context, kind), "$STAGING_PREFIX$id")

  /** 关键文件齐了才算装好。 */
  fun isInstalled(
    context: Context,
    kind: String,
    model: VoiceModel,
  ): Boolean = isInstalledIn(modelDir(context, kind, model.id), model)

  fun installedBytes(
    context: Context,
    kind: String,
    id: String,
  ): Long {
    val dir = modelDir(context, kind, id)
    if (!dir.isDirectory) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  /** 删掉模型（含残留的暂存目录）。用户在设置页点「删除」时调用。 */
  fun delete(
    context: Context,
    kind: String,
    id: String,
  ) {
    modelDir(context, kind, id).deleteRecursively()
    stagingDir(context, kind, id).deleteRecursively()
  }

  /**
   * 清掉这个种类下所有**没装完的残留**：暂存目录，以及解包失败的半成品。
   *
   * 在下载开始前调一次即可 —— 这类残留只可能来自「上一次下到一半被杀」，
   * 而那时用户已经在重下了。
   */
  fun pruneStaging(
    context: Context,
    kind: String,
  ) {
    root(context, kind).listFiles()?.forEach { file ->
      if (file.isDirectory && file.name.startsWith(STAGING_PREFIX)) file.deleteRecursively()
    }
  }

  /**
   * 删掉这个种类下**不在 [keepIds] 里的模型**，连同它们的续传归档。
   *
   * 目录表删掉一条模型之后，设备上那份没人会用、界面上也看不到 —— 留着只是占空间
   * （一个识别模型就是几十上百 MB）。只动 `filesDir/<kind>-models/` 下的一级目录，
   * 别的地方一概不碰。
   */
  fun pruneUnused(
    context: Context,
    kind: String,
    keepIds: Set<String>,
  ) {
    root(context, kind).listFiles()?.forEach { file ->
      if (!file.isDirectory || file.name.startsWith(STAGING_PREFIX) || file.name in keepIds) return@forEach
      file.deleteRecursively()
      archivePart(context, kind, file.name).delete()
      archiveEtag(context, kind, file.name).delete()
    }
  }

  /** 已下载归档的暂存位置（断点续传靠它跨进程存活）。 */
  fun archivePart(
    context: Context,
    kind: String,
    id: String,
  ): File = File(context.cacheDir, "$kind-$id.tar.bz2.part")

  fun archiveEtag(
    context: Context,
    kind: String,
    id: String,
  ): File = File(context.cacheDir, "$kind-$id.tar.bz2.part.etag")

  /**
   * 判据本体。**纯文件 IO、不碰 Android** —— 解包与「装好了没有」是这套流程里最容易出错
   * 又最不需要设备的一段，抽出来才能在 JVM 单测里真跑一遍（见 `VoiceModelStoreTest`）。
   */
  internal fun isInstalledIn(
    dir: File,
    model: VoiceModel,
  ): Boolean {
    if (!dir.isDirectory) return false
    return model.requiredFiles.all { File(dir, it).isFile }
  }

  private fun root(
    context: Context,
    kind: String,
  ): File = File(context.filesDir, "$kind-models").apply { if (!isDirectory) mkdirs() }
}

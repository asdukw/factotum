package com.tappet.app.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * 把 release 里的 APK 取到本地：**断点续传 + 可取消 + 两道校验**。
 *
 * 与 [AppUpdater]（查版本、交安装器）分开是有意的：这里只管字节，那里只管「哪个版本、装什么」。
 * 分开之后各自的失败面也清楚 —— 这里的失败都是「包没拿到 / 拿到的包不对」，
 * 调用方（[UpdateManager]）据此决定是「接着下」还是「重新查」。
 *
 * 续传靠 cacheDir 里的两样东西：`update-<版本>.apk.part`（半截文件）与它的 `.etag` 边车。
 * 中断（用户取消 / 进程被杀 / 网络断）之后重进，从已有长度接着下 —— 116MB 的包在城市地铁里
 * 重下三遍是很实在的痛。三个刻意的性质：
 *
 * 1. **哈希与签名仍是对整包算的**。续传只是省流量，不放松校验：拼接出来的字节必须整份
 *    对得上 `.sha256`，签名也必须与当前安装包一致，否则照旧作废。
 * 2. **`If-Range` 而不是裸 `Range`**。同名文件在服务器上换了内容时（重新打过 tag），
 *    裸 Range 会把两份不同的字节拼在一起 —— 那时哈希必然对不上，但已经白下了几十 MB。
 * 3. **可取消**。每 256KB 检查一次协程是否还活着，取消后立刻停手，半截文件留着给下次续传。
 */
object UpdateDownloader {
  private const val BUFFER_BYTES = 64 * 1024

  /**
   * 进度回调的节流步长。
   *
   * 按 64KB 的读块逐次回调的话，116MB 的包会产生 ~1800 次 UI 状态更新 —— 每次都要跨线程
   * 推一次 Compose 状态。按 256KB 合并之后仍然「肉眼是连续的」，回调次数降到 1/4。
   */
  private const val PROGRESS_STEP_BYTES = 256 * 1024

  private const val HTTP_OK = 200
  private const val HTTP_PARTIAL = 206
  private const val HTTP_RANGE_NOT_SATISFIABLE = 416

  /** 半截文件的命名前缀。断点续传靠它跨进程存活（cacheDir 不会被系统清掉）。 */
  private const val PART_SUFFIX = ".part"

  /** 记上一次响应的 ETag，续传时用 `If-Range` 判断服务器上那份还是不是同一个文件。 */
  private const val ETAG_SUFFIX = ".part.etag"

  /** 一次下载的进度。`total` 未知（服务器没给 Content-Length）时为 -1。 */
  data class Progress(val done: Long, val total: Long) {
    val known: Boolean get() = total > 0

    /** 总长未知时返回 0 —— UI 据此显示不确定进度条，而不是一个卡在 0% 的假进度。 */
    val percent: Int get() = if (known) ((done * 100) / total).toInt().coerceIn(0, 100) else 0

    val fraction: Float get() = if (known) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
  }

  /** 服务器对带 `Range` 的请求回了什么，本地那份半截文件该怎么接。 */
  internal enum class ResumeAction {
    /** 206：接着写。 */
    APPEND,

    /** 200：服务器没理会 Range（或 `If-Range` 判定文件变了）→ 本地那份作废，整份重来。 */
    RESTART,

    /** 416：本地那份比服务器上的还长 → 丢掉，去掉 Range 重新问一次。 */
    RETRY_WITHOUT_RANGE,
  }

  /**
   * 由响应码决定怎么接上本地那份半截文件。
   *
   * 抽成纯函数是为了能离开设备验证 —— 断点续传最容易错的地方就是这里的分支，
   * 而它恰恰不需要网络就能测。其余响应码一律当失败：**不猜**。
   */
  internal fun resumeAction(responseCode: Int): ResumeAction =
    when (responseCode) {
      HTTP_PARTIAL -> ResumeAction.APPEND
      HTTP_OK -> ResumeAction.RESTART
      HTTP_RANGE_NOT_SATISFIABLE -> ResumeAction.RETRY_WITHOUT_RANGE
      else -> error("HTTP_$responseCode")
    }

  /**
   * 本次响应声明的总字节数。
   *
   * 206 的 `Content-Length` 是**剩余**长度，所以要加上已经落盘的那一段才是整包大小 ——
   * 忘了加的表现是进度条从 80% 起跳然后卡在 100% 之前。
   */
  internal fun totalBytes(offset: Long, contentLength: Long): Long =
    if (contentLength < 0L) -1L else offset + contentLength

  /**
   * 下载并校验。任何一步不过都抛异常，调用方据此不给安装入口。
   *
   * @param onProgress 已下载字节 / 总字节（总字节未知时为 -1），只用来更新 UI。
   */
  suspend fun download(
    context: Context,
    release: AppUpdater.Release,
    onProgress: (Progress) -> Unit = {},
  ): File =
    withContext(Dispatchers.IO) {
      val expected = UpdateHttp.getText(release.sha256Url).trim().substringBefore(' ').lowercase()
      require(expected.length == 64) { "SHA256_MALFORMED" }

      val target = apkFile(context, release.version)
      // 上一次已经下好并校验过的，直接用：用户在系统安装器那一步退回来时不该重下整个包。
      if (target.isFile && UpdateHttp.sha256(target) == expected && signerMatches(context, target)) {
        onProgress(Progress(target.length(), target.length()))
        return@withContext target
      }

      val part = partFile(context, release.version)
      val meta = etagFile(context, release.version)
      pruneStalePartials(context, release.version)
      fetchInto(release.apkUrl, part, meta, onProgress)

      if (UpdateHttp.sha256(part) != expected) {
        part.delete()
        meta.delete()
        error("SHA256_MISMATCH")
      }
      if (!signerMatches(context, part)) {
        part.delete()
        meta.delete()
        error("SIGNER_MISMATCH")
      }
      // rename 而不是 copy：同一个 cacheDir 下是原子的，中途被杀不会留下半个「看起来完整」的包。
      target.delete()
      if (!part.renameTo(target)) {
        part.delete()
        meta.delete()
        error("RENAME_FAILED")
      }
      meta.delete()
      target
    }

  /** 下载完成后落地的位置。UI 在「已下载，待安装」状态下靠它取回文件。 */
  fun apkFile(context: Context, version: String): File = File(context.cacheDir, "update-$version.apk")

  /**
   * 下载到的 APK 是否与**当前安装包**同一个签名。
   *
   * 这是整条更新链路上唯一挡得住「换了个包冒充更新」的一步：`getPackageArchiveInfo` 读的是
   * 文件本身的签名，与已安装包的签名逐字节比。对不上就拒 —— 换签名 = 另一个 App，
   * 装上去旧数据也读不到。
   */
  fun signerMatches(context: Context, apk: File): Boolean {
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    // 读不出来的情况一律当成「对不上」—— 这条路径上只有 fail closed 是对的。
    val downloaded =
      runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)?.signingInfo }
        .getOrNull()
    val installed =
      runCatching { context.packageManager.getPackageInfo(context.packageName, flags).signingInfo }
        .getOrNull()
    val left = downloaded?.apkContentsSigners?.map { UpdateHttp.sha256(it.toByteArray()) }?.toSet().orEmpty()
    val right = installed?.apkContentsSigners?.map { UpdateHttp.sha256(it.toByteArray()) }?.toSet().orEmpty()
    return left.isNotEmpty() && left == right
  }

  private fun partFile(context: Context, version: String): File =
    File(context.cacheDir, "update-$version.apk$PART_SUFFIX")

  private fun etagFile(context: Context, version: String): File =
    File(context.cacheDir, "update-$version.apk$ETAG_SUFFIX")

  /** 清掉别的版本留下的半截文件：换了版本之后它们再也接不上，只会白占 cacheDir。 */
  private fun pruneStalePartials(context: Context, keepVersion: String) {
    val keep = partFile(context, keepVersion).name
    context.cacheDir
      .listFiles()
      ?.filter { it.name.startsWith("update-") && it.name != keep && !it.name.startsWith(keep) }
      ?.forEach { it.delete() }
  }

  /**
   * 取一次响应体，接着本地已有的长度往下写。
   *
   * 416 单独处理一次：本地那份比服务器上的还长（换过 release、或上一次写坏了）时，
   * 服务器拒绝这个 Range，此时**不能**直接失败 —— 丢掉本地那份、去掉 Range 重新问一次就好。
   *
   * **`internal` 而不是 `private` 是有意的**：它是整条续传逻辑里唯一「搬字节」的那一步，
   * 而它不碰 Android —— 测试拿一个本地 HTTP 服务器就能把「Range 真的发出去了吗」
   * 「服务器忽略 Range 时会拼坏吗」这些真跑一遍（见 UpdateDownloaderResumeTest）。
   * 只留 `download` 一层 public，是为了不让调用方绕过哈希与签名校验。
   */
  internal suspend fun fetchInto(url: String, part: File, meta: File, onProgress: (Progress) -> Unit) {
    var offset = if (part.isFile) part.length() else 0L
    var connection = UpdateHttp.open(url, offset, readEtag(meta))
    try {
      if (resumeAction(connection.responseCode) == ResumeAction.RETRY_WITHOUT_RANGE) {
        connection.disconnect()
        part.delete()
        meta.delete()
        offset = 0L
        connection = UpdateHttp.open(url, 0L, null)
      }
      if (resumeAction(connection.responseCode) == ResumeAction.RESTART) {
        part.delete()
        offset = 0L
      }
      // ETag 先落盘再下正文：中途断掉时，下一次续传要靠它判断服务器上那份还是不是同一个文件。
      writeEtag(meta, connection.getHeaderField("ETag"))
      pump(connection, part, offset, onProgress)
    } finally {
      connection.disconnect()
    }
  }

  /**
   * 把响应体抄进文件。
   *
   * 拆成两层只是为了让嵌套深度留在 detekt 的门槛内（函数 → use → use → 调用，
   * 而不是函数 → use → use → while → if）。这不是为规则而规则：五层嵌套里漏写一个
   * 「读到末尾就退出」是很难看出来的。
   */
  private suspend fun pump(connection: HttpURLConnection, part: File, offset: Long, onProgress: (Progress) -> Unit) {
    val total = totalBytes(offset, connection.contentLengthLong)
    // 先报一次起点：续传时进度条应当从已下载的那一段开始，而不是从 0% 跳上去。
    onProgress(Progress(offset, total))
    val written = connection.inputStream.use { input -> copyInto(input, part, offset, total, onProgress) }
    onProgress(Progress(written, total))
  }

  private suspend fun copyInto(
    input: InputStream,
    part: File,
    offset: Long,
    total: Long,
    onProgress: (Progress) -> Unit,
  ): Long {
    var written = offset
    var reportedAt = offset
    FileOutputStream(part, offset > 0L).use { output ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        // 取消点。放在读之前：阻塞的 read 结束后立刻就能退出，不必等下一块。
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        written += read
        if (written - reportedAt >= PROGRESS_STEP_BYTES) {
          reportedAt = written
          onProgress(Progress(written, total))
        }
      }
    }
    return written
  }

  private fun readEtag(meta: File): String? =
    runCatching { meta.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

  private fun writeEtag(meta: File, etag: String?) {
    runCatching { if (etag.isNullOrBlank()) meta.delete() else meta.writeText(etag) }
  }
}

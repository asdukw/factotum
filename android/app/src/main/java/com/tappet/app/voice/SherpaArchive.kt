package com.tappet.app.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 解开 sherpa-onnx 的模型归档（`.tar.bz2`）。**合成与识别共用**（见 [VoiceModel]）。
 *
 * 为什么需要它：这些模型**只有 `.tar.bz2` 一种分发格式**，而 Android 的 `java.util.zip`
 * 只认 deflate —— 不认 bzip2，也不认 tar。所以引了 commons-compress（纯 Java，Apache-2.0）。
 *
 * 两件必须做对的事：
 *
 * 1. **剥掉顶层目录**。归档里是 `vits-piper-.../xxx.onnx` 这样的结构，解出来直接摊平到
 *    目标目录，运行时拼路径才不用管归档的目录名。
 * 2. **挡住路径穿越**。tar 条目名可以是 `../../databases/tappet.db`。来源是 GitHub release、
 *    理论上可信，但「下载来的字节按名字写文件」这件事本身就该设一道闸 —— 何况这条路径的
 *    输出直接落在 App 私有目录里。
 *
 * 纯 Java IO，不碰 Android：解包是这套流程里最容易出错又最不需要设备的一段，
 * 用 commons-compress 自己造一个小归档就能在 JVM 单测里真跑一遍。
 */
object SherpaArchive {
  /** 单个条目的上限，挡住「解出来比归档大几百倍」的归档炸弹。 */
  private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

  /**
   * 把 [archive] 解到 [dest]（会先清空），剥掉 [stripRoot] 这一层目录。
   *
   * @return 解出来的文件数（不含目录）。
   * @throws IllegalStateException 归档结构不对（剥完没有文件、条目过大）。越界的条目是**跳过**，不是报错。
   */
  fun extract(archive: File, stripRoot: String, dest: File): Int {
    dest.deleteRecursively()
    if (!dest.mkdirs()) error("无法创建目录：$dest")
    val destPath = dest.canonicalFile

    var count = 0
    BufferedInputStream(FileInputStream(archive)).use { raw ->
      BZip2CompressorInputStream(raw).use { bz ->
        TarArchiveInputStream(bz).use { tar -> count = unpack(tar, stripRoot, destPath) }
      }
    }
    if (count == 0) error("归档里没有剥掉 $stripRoot/ 之后的文件")
    return count
  }

  /**
   * 逐条解出来。
   *
   * 拆成独立函数只是为了让嵌套留在门槛内（use → use → use → while → if 五层里漏写一个
   * 「读完就退出」是很难看出来的）—— 与 UpdateDownloader 里 `pump` / `copyInto` 拆开同一个理由。
   */
  private fun unpack(tar: TarArchiveInputStream, stripRoot: String, destPath: File): Int {
    var count = 0
    var entry = tar.nextEntry
    while (entry != null) {
      val target = entry.relativeTo(stripRoot, destPath)
      when {
        target == null -> Unit

        entry.isDirectory -> target.mkdirs()

        else -> {
          writeEntry(tar, entry, target)
          count++
        }
      }
      entry = tar.nextEntry
    }
    return count
  }

  private fun writeEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File) {
    if (entry.size > MAX_ENTRY_BYTES) error("归档里的条目过大：${entry.name}")
    target.parentFile?.mkdirs()
    FileOutputStream(target).use { out -> tar.copyTo(out) }
  }

  /**
   * 条目该落到哪；不属于 [stripRoot] 或越出 [destPath] 时返回 null。
   *
   * 越界判定用 canonical path 比对，而不是查 `..` 子串 —— 后者挡不住符号链接，
   * 也挡不住 `a/./../../b` 这类写法。
   */
  private fun TarArchiveEntry.relativeTo(
    stripRoot: String,
    destPath: File,
  ): File? {
    val name = name.replace('\\', '/').trimStart('/')
    val prefix = "$stripRoot/"
    if (!name.startsWith(prefix)) return null
    val relative = name.removePrefix(prefix)
    if (relative.isEmpty()) return null
    val target = File(destPath, relative).canonicalFile
    return if (target.path == destPath.path || target.path.startsWith(destPath.path + File.separator)) {
      target
    } else {
      null
    }
  }
}

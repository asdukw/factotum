package com.factotum.app.proot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * 解开 Debian rootfs 的 `.tar.gz`（就是 Docker 镜像的那一层）。
 *
 * 和 [com.factotum.app.voice.SherpaArchive] 是三处不同的东西，别合并：
 * 那个是 bzip2、剥顶层目录、不建符号链接（模型目录里没有链接）；rootfs 正好相反 ——
 * **符号链接是主体**（`bin -> usr/bin`、`usr/bin/sh -> dash`），丢了它们 bash 根本起不来。
 *
 * 两条硬约束：
 * 1. **路径穿越必须挡**。条目名可以写 `../../databases/factotum.db`；归档是我们自己打的，
 *    但这个文件最终来自网络，闸门不因为「应该没问题」就省掉。
 * 2. **模式位要保留**。Linux 靠 `0111` 判断可执行；Android 解压出来是 `0600`，不 chmod
 *    的话 guest 里每个程序都是 `Permission denied`。
 *
 * 符号链接与 chmod 通过 [Hooks] 注入，是为了让 JVM 单测能跑：Windows 上建符号链接要特权，
 * 而这一段逻辑（判类型、挡穿越、剥前缀）恰恰是最该用单测钉住的。
 */
object ProotArchive {
  /** 单条上限，挡住「解出来比归档大几百倍」的归档炸弹。 */
  private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

  interface Hooks {
    fun symlink(target: String, link: File)

    fun chmod(file: File, mode: Int)
  }

  /**
   * 解到 [dest]（先清空）。返回写出的普通文件数。
   *
   * @throws IllegalStateException 归档里一个文件都没有（下错文件 / 空镜像层）。
   */
  fun extract(archive: InputStream, dest: File, hooks: Hooks): Int {
    dest.deleteRecursively()
    if (!dest.mkdirs() && !dest.isDirectory) error("无法创建目录：$dest")
    val root = dest.canonicalFile
    var files = 0
    BufferedInputStream(archive, 1 shl 16).use { raw ->
      GzipCompressorInputStream(raw).use { gz ->
        TarArchiveInputStream(gz).use { tar -> files = unpack(tar, root, hooks) }
      }
    }
    if (files == 0) error("rootfs 归档里没有文件")
    return files
  }

  private fun unpack(tar: TarArchiveInputStream, root: File, hooks: Hooks): Int {
    var files = 0
    var entry = tar.nextEntry
    while (entry != null) {
      val target = entry.target(root)
      if (target != null) files += place(tar, entry, target, hooks, root)
      entry = tar.nextEntry
    }
    return files
  }

  /**
   * 落一个条目；返回写出的普通文件数（目录 / 链接 / 设备节点都是 0）。
   *
   * 拆出来是因为 [extract] 的三层 `use` 已经够深，再套一层 `when` 会同时踩上
   * CyclomaticComplexMethod 与 NestedBlockDepth —— 和 SherpaArchive 拆 `unpack` 同一个理由。
   */
  private fun place(
    tar: TarArchiveInputStream,
    entry: TarArchiveEntry,
    target: File,
    hooks: Hooks,
    root: File,
  ): Int =
    when {
      entry.isDirectory -> {
        target.mkdirs()
        0
      }

      entry.isSymbolicLink -> {
        hooks.symlink(entry.linkName, target)
        0
      }

      entry.isLink -> {
        linkWithin(entry, root, target)
        0
      }

      entry.isFile -> {
        writeFile(tar, entry, target, hooks)
        1
      }

      // 设备节点 / fifo：Android 上建不出来，guest 里也由 proot 的 /dev 绑定提供。
      else -> 0
    }

  private fun writeFile(tar: TarArchiveInputStream, entry: TarArchiveEntry, target: File, hooks: Hooks) {
    if (entry.size > MAX_ENTRY_BYTES) error("归档条目过大：${entry.name}")
    target.parentFile?.mkdirs()
    FileOutputStream(target).use { out -> tar.copyRangeTo(out, entry.size) }
    hooks.chmod(target, entry.mode and 0b111_111_111)
  }

  /**
   * 硬链接：tar 的 `linkName` 是**归档内路径**，必须在 rootfs 之内。
   * 建不出来就退化成复制 —— 目标文件内容等价，rootfs 里没有需要靠 inode 相等才能工作的东西。
   */
  private fun linkWithin(entry: TarArchiveEntry, root: File, target: File) {
    val source = File(root, entry.linkName.removePrefix("./"))
    if (!source.canonicalPath.startsWith(root.path + File.separator)) return
    target.parentFile?.mkdirs()
    try {
      Files.deleteIfExists(target.toPath())
      Files.createLink(target.toPath(), source.toPath())
    } catch (_: Exception) {
      if (source.isFile) source.copyTo(target, overwrite = true)
    }
  }

  /** 规范化条目名；越界、whiteout、绝对路径一律返回 null（跳过）。 */
  private fun TarArchiveEntry.target(root: File): File? {
    val name = this.name.removePrefix("./")
    if (name.isEmpty() || name.startsWith("/")) return null
    if (name.substringAfterLast('/').startsWith(".wh.")) return null
    if (name.split('/').any { it == ".." }) return null
    val candidate = File(root, name)
    return if (candidate.canonicalPath == root.path ||
      candidate.canonicalPath.startsWith(root.path + File.separator)
    ) {
      candidate
    } else {
      null
    }
  }

  /** commons-compress 没有跨版本的「只读这么多字节」，自己搬。 */
  private fun TarArchiveInputStream.copyRangeTo(out: FileOutputStream, size: Long) {
    val buffer = ByteArray(1 shl 16)
    var remaining = size
    while (remaining > 0) {
      val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) error("归档被截断")
      out.write(buffer, 0, read)
      remaining -= read
    }
  }
}

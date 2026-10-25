package com.tappet.app.proot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * rootfs 解包的纯 JVM 单测。
 *
 * 这一段最该被钉住，理由和 [com.tappet.app.voice.SherpaArchiveTest] 一样：**错了不会报错**。
 * 符号链接没建出来，现象是容器里 `bash: not found`；模式位没保留，是每个程序都
 * `Permission denied`；穿越没挡住，是网络来的字节能写进 App 私有目录之外。
 *
 * 符号链接与 chmod 走 [ProotArchive.Hooks] 注入 —— Windows 上建真链接要特权，
 * 而这里要验的是「判类型 / 挡穿越 / 剥前缀」这层逻辑。
 */
class ProotArchiveTest {
  @get:Rule val temp = TemporaryFolder()

  private val symlinks = mutableListOf<Pair<String, String>>()
  private val modes = mutableMapOf<String, Int>()

  private val hooks =
    object : ProotArchive.Hooks {
      override fun symlink(target: String, link: File) {
        symlinks += target to link.name
      }

      override fun chmod(file: File, mode: Int) {
        modes[file.name] = mode
      }
    }

  @Test
  fun `普通文件、符号链接与模式位都按归档来`() {
    val archive = makeArchive { tar ->
      tar.putDir("usr/bin")
      tar.putFile("usr/bin/bash", "ELF", mode = 0b111_101_101)
      tar.putSymlink("usr/bin/sh", "dash")
    }
    val dest = temp.newFolder("rootfs")

    val count = ProotArchive.extract(archive.inputStream(), dest, hooks)

    assertEquals(1, count)
    assertEquals("ELF", File(dest, "usr/bin/bash").readText())
    assertEquals(0b111_101_101 and 0b111_111_111, modes["bash"])
    assertTrue(symlinks.contains("dash" to "sh"))
  }

  @Test
  fun `硬链接在 rootfs 内落地成同一份内容`() {
    val archive = makeArchive { tar ->
      tar.putFile("usr/bin/busybox", "MULTI", mode = 0b111_101_101)
      tar.putHardlink("usr/bin/ls", "usr/bin/busybox")
    }
    val dest = temp.newFolder("rootfs")

    ProotArchive.extract(archive.inputStream(), dest, hooks)

    assertEquals("MULTI", File(dest, "usr/bin/ls").readText())
  }

  @Test
  fun `路径穿越与 whiteout 条目被跳过`() {
    val archive = makeArchive { tar ->
      tar.putFile("../evil.txt", "pwn")
      tar.putFile("usr/.wh.removed", "")
      tar.putFile("etc/debian_version", "12.0")
    }
    val dest = temp.newFolder("rootfs")

    val count = ProotArchive.extract(archive.inputStream(), dest, hooks)

    assertEquals(1, count)
    assertTrue(File(dest, "etc/debian_version").isFile)
    assertFalse(File(dest.parentFile, "evil.txt").exists())
    assertFalse(File(dest, "usr/.wh.removed").exists())
  }

  private fun makeArchive(build: (TarArchiveOutputStream) -> Unit): File {
    val archive = File(temp.root, "rootfs.tar.gz")
    FileOutputStream(archive).use { raw ->
      GzipCompressorOutputStream(raw).use { gz ->
        TarArchiveOutputStream(gz).use { tar -> build(tar) }
      }
    }
    return archive
  }

  private fun TarArchiveOutputStream.putDir(name: String) {
    putArchiveEntry(TarArchiveEntry(name + "/").apply { mode = 0b111_101_101 })
    closeArchiveEntry()
  }

  private fun TarArchiveOutputStream.putFile(name: String, content: String, mode: Int = 0b110_100_100) {
    val bytes = content.toByteArray()
    val entry = TarArchiveEntry(name).apply {
      this.mode = mode
      size = bytes.size.toLong()
    }
    putArchiveEntry(entry)
    write(bytes)
    closeArchiveEntry()
  }

  private fun TarArchiveOutputStream.putSymlink(name: String, target: String) {
    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
    entry.linkName = target
    putArchiveEntry(entry)
    closeArchiveEntry()
  }

  private fun TarArchiveOutputStream.putHardlink(name: String, target: String) {
    val entry = TarArchiveEntry(name, TarConstants.LF_LINK)
    entry.linkName = target
    putArchiveEntry(entry)
    closeArchiveEntry()
  }
}

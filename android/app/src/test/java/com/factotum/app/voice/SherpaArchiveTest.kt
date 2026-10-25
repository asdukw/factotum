package com.factotum.app.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * `.tar.bz2` 解包的纯 JVM 单测。
 *
 * 用 commons-compress 自己造归档再解开 —— 不需要设备，也不需要下真模型。
 * 这里钉住的两件事都是「错了不会报错、只会静默做错」的：剥目录剥错，模型文件就找不到；
 * 路径穿越没挡住，下载来的字节就能写到 App 私有目录之外。
 */
class SherpaArchiveTest {
  @get:Rule val temp = TemporaryFolder()

  @Test
  fun `剥掉顶层目录，文件摊平到目标目录`() {
    val archive = makeArchive(
      "model-root/model.onnx" to "weights",
      "model-root/tokens.txt" to "tokens",
      "model-root/rule.fst" to "fst",
    )
    val dest = temp.newFolder("out")

    val count = SherpaArchive.extract(archive, stripRoot = "model-root", dest = dest)

    assertEquals(3, count)
    assertEquals("weights", File(dest, "model.onnx").readText())
    assertEquals("tokens", File(dest, "tokens.txt").readText())
    assertEquals("fst", File(dest, "rule.fst").readText())
  }

  @Test
  fun `别的顶层目录不碰`() {
    val archive = makeArchive(
      "model-root/model.onnx" to "weights",
      "other-root/unrelated.txt" to "nope",
    )
    val dest = temp.newFolder("out")

    SherpaArchive.extract(archive, stripRoot = "model-root", dest = dest)

    assertTrue(File(dest, "model.onnx").isFile)
    assertFalse("不该把别的目录也解出来", File(dest, "unrelated.txt").exists())
  }

  @Test
  fun `路径穿越的条目被跳过，不写到目标目录之外`() {
    val archive = makeArchive(
      "model-root/model.onnx" to "weights",
      "model-root/../../escaped.txt" to "should not land outside",
    )
    val dest = temp.newFolder("out")

    SherpaArchive.extract(archive, stripRoot = "model-root", dest = dest)

    assertTrue(File(dest, "model.onnx").isFile)
    val escaped = File(dest.parentFile, "escaped.txt")
    assertFalse("条目越出了目标目录，必须被丢掉", escaped.exists())
  }

  @Test
  fun `剥完一个文件都不剩时抛错，而不是留一个空目录`() {
    val archive = makeArchive("other-root/a.txt" to "x")
    val dest = temp.newFolder("out")

    val failure = runCatching { SherpaArchive.extract(archive, stripRoot = "model-root", dest = dest) }

    assertTrue(failure.isFailure)
  }

  /** 造一个 tar.bz2：路径 → 内容。 */
  private fun makeArchive(vararg entries: Pair<String, String>): File {
    val file = temp.newFile("model.tar.bz2")
    FileOutputStream(file).use { out ->
      BZip2CompressorOutputStream(out).use { bz ->
        TarArchiveOutputStream(bz).use { tar -> writeAll(tar, entries) }
      }
    }
    return file
  }

  private fun writeAll(tar: TarArchiveOutputStream, entries: Array<out Pair<String, String>>) {
    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
    for ((path, content) in entries) {
      val bytes = content.toByteArray()
      tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
      tar.write(bytes)
      tar.closeArchiveEntry()
    }
  }
}

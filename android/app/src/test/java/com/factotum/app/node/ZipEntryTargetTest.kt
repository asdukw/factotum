package com.factotum.app.node

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Zip Slip：归档条目必须落在目标目录内。
 *
 * 载荷来自 App 自己的 assets，但解包代码面对的是一条**不可信**的路径字符串 ——
 * 只要 APK 能被替换，`../` 就能把文件写到 filesDir 之外。这些用例把边界钉住。
 */
class ZipEntryTargetTest {
  private lateinit var root: File

  @Before
  fun setUp() {
    root = Files.createTempDirectory("factotum-zip").toFile()
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun `普通条目落在目标目录内`() {
    assertEquals(File(root, "launcher.mjs").canonicalFile, zipEntryTarget(root, "launcher.mjs"))
  }

  @Test
  fun `嵌套条目落在目标目录内`() {
    assertEquals(File(root, "dsh/lib/a.mjs").canonicalFile, zipEntryTarget(root, "dsh/lib/a.mjs"))
  }

  @Test
  fun `点开头的文件名不是穿越`() {
    assertEquals(File(root, "..hidden").canonicalFile, zipEntryTarget(root, "..hidden"))
  }

  @Test
  fun `父目录穿越被拒绝`() {
    assertThrows(IllegalArgumentException::class.java) { zipEntryTarget(root, "../evil.txt") }
  }

  @Test
  fun `嵌套父目录穿越被拒绝`() {
    assertThrows(IllegalArgumentException::class.java) { zipEntryTarget(root, "a/../../evil.txt") }
  }

  @Test
  fun `前缀相同的兄弟目录被拒绝`() {
    assertThrows(IllegalArgumentException::class.java) { zipEntryTarget(root, "../${root.name}-evil") }
  }
}

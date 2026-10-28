package com.zoneu.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「模型装好了没有」的纯 JVM 单测。
 *
 * 这条判据错了的表现很隐蔽：目录存在但文件不全时放行 → 建引擎时崩在 native 层，
 * 而崩在哪几乎看不出来（模型文件是按路径交给 sherpa 的）。判据是**关键文件都在**，
 * 不是「目录在」，这条用真文件验一遍。
 */
class VoiceModelStoreTest {
  @get:Rule val temp = TemporaryFolder()

  private val model = AsrModelCatalog.PARAFORMER_ZH_SMALL

  @Test
  fun `关键文件齐了才算装好`() {
    val dir = temp.newFolder("paraformer_zh_small")
    model.requiredFiles.forEach { File(dir, it).writeText("x") }

    assertTrue(VoiceModelStore.isInstalledIn(dir, model))
  }

  @Test
  fun `缺一个文件就不算装好`() {
    val dir = temp.newFolder("half")
    File(dir, model.requiredFiles.first()).writeText("x")

    assertFalse(VoiceModelStore.isInstalledIn(dir, model))
  }

  @Test
  fun `目录不存在不算装好`() {
    assertFalse(VoiceModelStore.isInstalledIn(File(temp.root, "missing"), model))
  }

  @Test
  fun `空目录不算装好`() {
    // 解包解到一半被杀留下的正是这种目录。
    assertFalse(VoiceModelStore.isInstalledIn(temp.newFolder("empty"), model))
  }
}

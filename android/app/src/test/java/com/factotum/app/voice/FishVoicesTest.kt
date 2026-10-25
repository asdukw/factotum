package com.factotum.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预置音色的目录约束。
 *
 * 这些 id 是社区上传的 Fish 模型：id 重复会让设置页出现两条一模一样的选项，
 * 而默认音色如果不在列表首位、或 id 不为空，界面就没法表达「不指定音色」。
 */
class FishVoicesTest {
  @Test
  fun `默认音色排在首位且 id 为空`() {
    assertEquals("", FishVoices.ALL.first().id)
    assertEquals(FishVoices.DEFAULT_ID, FishVoices.ALL.first().id)
  }

  @Test
  fun `id 不重复`() {
    val ids = FishVoices.ALL.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `认不出的 id 显示默认音色名`() {
    assertEquals(FishVoices.ALL.first().label, FishVoices.labelOf("model-deleted"))
    assertEquals(FishVoices.ALL.first().label, FishVoices.labelOf(null))
  }
}

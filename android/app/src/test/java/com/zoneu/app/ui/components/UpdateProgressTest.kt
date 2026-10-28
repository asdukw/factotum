package com.zoneu.app.ui.components

import com.zoneu.app.update.UpdateDownloader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进度文案里**可以离开设备验证**的部分。
 *
 * 这两条都出过事的那种：单位换算写错一位（`116.5 GB`），以及总长未知时编一个百分比出来
 * —— 后者更坏，因为进度条会停在一个**看起来正常但永远不动**的位置，用户以为卡死了。
 */
class UpdateProgressTest {
  @Test
  fun `bytes are scaled to the unit that reads naturally`() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("1 KB", formatBytes(1024))
    assertEquals("52.1 MB", formatBytes(54_630_000))
    assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
  }

  @Test
  fun `progress label shows both the percentage and the byte counts`() {
    val label = progressLabel(UpdateDownloader.Progress(done = 1024 * 1024, total = 4 * 1024 * 1024))
    assertEquals("25% · 1.0 MB / 4.0 MB", label)
  }

  @Test
  fun `progress label admits when the total is unknown`() {
    // 不写「0%」：那是个假进度。只说已经下了多少。
    val label = progressLabel(UpdateDownloader.Progress(done = 2 * 1024 * 1024, total = -1))
    assertEquals("已下载 2.0 MB", label)
  }
}

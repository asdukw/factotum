package com.tappet.app.connector

import org.junit.Assert.assertEquals
import org.junit.Test

/** 附件文件名清洗：挡住路径、控制字符、空名与超长名。 */
class AttachmentFileNameTest {
  @Test
  fun `keeps only the last path segment`() {
    assertEquals("report.pdf", AttachmentFileName.sanitize("../../report.pdf"))
    assertEquals("report.pdf", AttachmentFileName.sanitize("C:\\tmp\\report.pdf"))
    assertEquals("report.pdf", AttachmentFileName.sanitize("/sdcard/Download/report.pdf"))
  }

  @Test
  fun `replaces reserved and control characters`() {
    assertEquals("a_b.pdf", AttachmentFileName.sanitize("a:b.pdf"))
    assertEquals("x_y.pdf", AttachmentFileName.sanitize("x\ny.pdf"))
    assertEquals("a_b_c.pdf", AttachmentFileName.sanitize("a*b?c.pdf"))
  }

  @Test
  fun `falls back for blank names and caps length`() {
    assertEquals("attachment", AttachmentFileName.sanitize("   "))
    assertEquals("attachment", AttachmentFileName.sanitize("../../"))
    assertEquals(120, AttachmentFileName.sanitize("a".repeat(500)).length)
  }
}

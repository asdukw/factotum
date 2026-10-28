package com.zoneu.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传里**可以离开设备验证**的部分：三种响应码各自的处置、206 的长度换算、进度百分比。
 *
 * 这几条正是续传最容易错的地方，而它们都不需要网络 —— 真跑一遍 116MB 的下载来验它们
 * 既慢又不可重复。需要设备的只剩「Range 请求真的带上了」与「拼出来的整包哈希对得上」，
 * 那部分靠真机/模拟器实测。
 */
class UpdateDownloaderTest {
  // --- 断点续传 ---
  //
  // 续传最容易错的就是「服务器这么回了，本地那份该怎么接」这几个分支，而它们**不需要网络**
  // 就能测。这里钉住三种响应码各自的处置；其余响应码一律失败（不猜）。

  @Test
  fun `206 appends to the partial file`() {
    assertEquals(UpdateDownloader.ResumeAction.APPEND, UpdateDownloader.resumeAction(206))
  }

  @Test
  fun `200 restarts from scratch`() {
    // 服务器没理会 Range，或 If-Range 判定服务器上那份变了 → 本地那份作废。
    // 当成 206 接着写会把两份不同的字节拼在一起，哈希必然对不上，但那时已经白下了几十 MB。
    assertEquals(UpdateDownloader.ResumeAction.RESTART, UpdateDownloader.resumeAction(200))
  }

  @Test
  fun `416 drops the partial file and retries without a range`() {
    // 本地那份比服务器上的还长（换过 release、或上次写坏了）。
    assertEquals(UpdateDownloader.ResumeAction.RETRY_WITHOUT_RANGE, UpdateDownloader.resumeAction(416))
  }

  @Test
  fun `other status codes fail instead of guessing`() {
    for (code in listOf(301, 403, 404, 500, 503)) {
      val error = runCatching { UpdateDownloader.resumeAction(code) }.exceptionOrNull()
      assertEquals("HTTP_$code", error?.message)
    }
  }

  @Test
  fun `206 content length is the remaining bytes, so total must add the offset`() {
    // 206 的 Content-Length 是**剩余**长度。忘了加 offset 的表现是进度条从 80% 起跳，
    // 然后永远停在 100% 之前。
    assertEquals(1000L, UpdateDownloader.totalBytes(offset = 800L, contentLength = 200L))
    assertEquals(200L, UpdateDownloader.totalBytes(offset = 0L, contentLength = 200L))
  }

  @Test
  fun `unknown content length stays unknown`() {
    assertEquals(-1L, UpdateDownloader.totalBytes(offset = 800L, contentLength = -1L))
  }

  @Test
  fun `progress reports percent only when the total is known`() {
    val known = UpdateDownloader.Progress(done = 45, total = 100)
    assertTrue(known.known)
    assertEquals(45, known.percent)
    assertEquals(0.45f, known.fraction)

    // 总长未知时不编一个百分比出来 —— UI 据此显示不确定进度条。
    val unknown = UpdateDownloader.Progress(done = 45, total = -1)
    assertFalse(unknown.known)
    assertEquals(0, unknown.percent)
    assertEquals(0f, unknown.fraction)
  }

  @Test
  fun `progress clamps overshoot instead of drawing past the end`() {
    // 服务器报的总长比实际少时（代理改写 Content-Length）不该把进度条画到 100% 以外。
    val over = UpdateDownloader.Progress(done = 120, total = 100)
    assertEquals(100, over.percent)
    assertEquals(1f, over.fraction)
  }
}

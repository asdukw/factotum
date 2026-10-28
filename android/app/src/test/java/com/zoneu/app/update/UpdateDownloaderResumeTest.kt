package com.zoneu.app.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

/**
 * 断点续传的**真跑一遍**：起一个本地 HTTP 服务器，让它按真实语义回 200 / 206 / 416，
 * 然后看 `fetchInto` 到底把哪些字节写进了文件。
 *
 * 为什么不靠模拟器实测：真跑一次 116MB 的下载既慢又不可重复，而续传最容易错的恰恰是
 * 「服务器这么回了，本地那份该怎么接」这几个分支 —— 它们不需要网络、也不需要 Android。
 * 需要设备的只剩「真的从 GitHub 下下来」那一次（哈希与签名校验），那个交给真机/模拟器。
 */
class UpdateDownloaderResumeTest {
  private val servers = mutableListOf<HttpServer>()

  @After
  fun stopServers() {
    servers.forEach { it.stop(0) }
    servers.clear()
  }

  @Test
  fun `fresh download writes the whole body`() = runBlocking {
    val body = body(64 * 1024)
    val server = serve(body)
    val part = tempFile()

    UpdateDownloader.fetchInto(server.url, part, tempFile(), {})

    assertArrayEquals(body, part.readBytes())
    assertEquals(listOf<String?>(null), server.ranges)
  }

  @Test
  fun `resume asks for the remaining bytes and appends them`() = runBlocking {
    val body = body(64 * 1024)
    val server = serve(body)
    val part = tempFile()
    // 上一次下到一半：文件里已经有前半段，边车里记着当时的 ETag。
    val half = body.size / 2
    part.writeBytes(body.copyOfRange(0, half))
    val meta = tempFile().apply { writeText(ETAG) }

    UpdateDownloader.fetchInto(server.url, part, meta, {})

    assertEquals("bytes=$half-", server.ranges.single())
    assertArrayEquals(body, part.readBytes())
  }

  @Test
  fun `a server that ignores Range must not produce a doubled file`() = runBlocking {
    val body = body(32 * 1024)
    val server = serve(body, supportsRange = false)
    val part = tempFile()
    part.writeBytes(body.copyOfRange(0, 4096))

    UpdateDownloader.fetchInto(server.url, part, tempFile(), {})

    // 本地那份必须被丢掉重来。当成 206 接着写会得到 4KB + 32KB 的拼接，长度先不对，
    // 哈希随后也会拦下来 —— 但那时已经白下了几十 MB。
    assertArrayEquals(body, part.readBytes())
  }

  @Test
  fun `a stale partial longer than the release is dropped and refetched`() = runBlocking {
    val body = body(8 * 1024)
    val server = serve(body)
    val part = tempFile()
    // 本地那份比服务器上的还长（换过 release，或上次写坏了）→ 服务器回 416。
    part.writeBytes(ByteArray(body.size + 4096))
    val meta = tempFile().apply { writeText(ETAG) }

    UpdateDownloader.fetchInto(server.url, part, meta, {})

    // 先带 Range 问一次（被 416 拒），再不带 Range 问一次。
    assertEquals(2, server.ranges.size)
    assertTrue(server.ranges.first()!!.startsWith("bytes="))
    assertNull(server.ranges.last())
    assertArrayEquals(body, part.readBytes())
  }

  @Test
  fun `a changed ETag restarts instead of splicing two different files`() = runBlocking {
    val body = body(32 * 1024)
    // 服务器上那份换过了（重新打过 tag）：If-Range 对不上，它回 200 整份。
    val server = serve(body, etag = "\"new\"")
    val part = tempFile()
    part.writeBytes(body.copyOfRange(0, 4096))
    val meta = tempFile().apply { writeText("\"old\"") }

    UpdateDownloader.fetchInto(server.url, part, meta, {})

    assertArrayEquals(body, part.readBytes())
    assertEquals("\"new\"", meta.readText())
  }

  @Test
  fun `cancelling keeps the partial file so the next attempt can resume`() = runBlocking {
    val body = body(4 * 1024 * 1024)
    // 慢速服务器：不一次性给完，否则还没等到取消就已经下完了。
    val server = serve(body, chunkBytes = 64 * 1024, chunkDelayMs = 5)
    val part = tempFile()

    val job = launch(Dispatchers.IO) { UpdateDownloader.fetchInto(server.url, part, tempFile(), {}) }
    delay(150)
    job.cancel()
    job.join()

    val written = part.length()
    assertTrue("取消后应当留下半截文件，实际 $written", written in 1 until body.size.toLong())
  }

  @Test
  fun `a connection closed mid-body fails instead of leaving a short file`() = runBlocking {
    val body = body(64 * 1024)
    // 服务器声明了完整长度，却只发一半就把连接关了 —— 而 `read` 对「下完了」与「断了」
    // 给的是同一个 -1。不拦的话半截文件会被当成完整的一份：更新那边哈希会兜住（白下几十 MB），
    // 模型那边 bzip2 解包失败、报的却是「归档坏了」。
    val server = serve(body, truncateTo = body.size / 2)
    val part = tempFile()

    val error = runCatching { UpdateDownloader.fetchInto(server.url, part, tempFile(), {}) }.exceptionOrNull()

    assertEquals(UpdateDownloader.TRANSFER_TRUNCATED, error?.message)
    // 半截文件要**留着**：它是下一次续传的起点（与取消那条同一条规矩）。
    assertEquals((body.size / 2).toLong(), part.length())
  }

  // --- 测试脚手架 ---

  private class TestServer(val url: String, val ranges: MutableList<String?>, val server: HttpServer)

  /**
   * 起一个按真实语义回应的服务器。
   *
   * 关键点是 **206 的 `Content-Length` 只算剩余长度** —— 真实服务器就是这么做的，
   * 而本地那份已经落盘的字节要由客户端自己加上去（见 `totalBytes`）。
   *
   * [truncateTo] 只发这么多字节就关连接，但**声明的长度仍是完整的** —— 用来模拟
   * 「代理断了 / 服务器中途挂了」那一种。
   */
  private fun serve(
    body: ByteArray,
    etag: String = ETAG,
    supportsRange: Boolean = true,
    chunkBytes: Int = 0,
    chunkDelayMs: Long = 0,
    truncateTo: Int = 0,
  ): TestServer {
    val ranges = mutableListOf<String?>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/apk") { exchange ->
      ranges += exchange.requestHeaders.getFirst("Range")
      respond(exchange, body, etag, supportsRange, chunkBytes, chunkDelayMs, truncateTo)
    }
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    servers += server
    return TestServer("http://127.0.0.1:${server.address.port}/apk", ranges, server)
  }

  private fun respond(
    exchange: HttpExchange,
    body: ByteArray,
    etag: String,
    supportsRange: Boolean,
    chunkBytes: Int,
    chunkDelayMs: Long,
    truncateTo: Int,
  ) {
    val range = exchange.requestHeaders.getFirst("Range")
    val ifRange = exchange.requestHeaders.getFirst("If-Range")
    // If-Range 对不上 = 服务器上那份换了 → 无视 Range，整份重发。
    val usable = supportsRange && range != null && (ifRange == null || ifRange == etag)
    val start = if (usable) range!!.removePrefix("bytes=").removeSuffix("-").toInt() else 0
    exchange.responseHeaders.add("ETag", etag)
    if (start >= body.size && start > 0) {
      exchange.sendResponseHeaders(416, -1)
      exchange.close()
      return
    }
    val slice = body.copyOfRange(start, body.size)
    val send = if (truncateTo > 0) minOf(truncateTo, slice.size) else slice.size
    exchange.sendResponseHeaders(if (start > 0) 206 else 200, slice.size.toLong())
    exchange.responseBody.use { out ->
      if (chunkBytes <= 0) {
        out.write(slice, 0, send)
      } else {
        var at = 0
        while (at < send) {
          val end = minOf(at + chunkBytes, send)
          out.write(slice, at, end - at)
          out.flush()
          at = end
          Thread.sleep(chunkDelayMs)
        }
      }
    }
  }

  private fun body(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

  private fun tempFile(): File = File.createTempFile("zoneu-resume-", ".bin").apply { delete() }

  private companion object {
    private const val ETAG = "\"v1\""
  }
}

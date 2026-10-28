package com.zoneu.app.connector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URI

class GmailLoopbackTest {
  @Test
  fun `cancelling accept closes listener immediately`() = runTest {
    val receiver = GmailLoopback()
    val waiting = async { receiver.await(600_000) }
    testScheduler.runCurrent()
    waiting.cancelAndJoin()
    val port = URI(receiver.redirectUri).port
    assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
  }

  @Test
  fun `cancelling partial request closes accepted socket`() = runTest {
    val receiver = GmailLoopback()
    val waiting = async { receiver.await(600_000) }
    testScheduler.runCurrent()
    withContext(Dispatchers.IO) {
      Socket("127.0.0.1", URI(receiver.redirectUri).port).use {
        it.soTimeout = 2000
        it.getOutputStream().write("GET /?code=unfinished".toByteArray())
        waiting.cancel()
        assertTrue(runCatching { it.getInputStream().read() == -1 }.getOrDefault(true))
      }
    }
    waiting.join()
    receiver.close()
  }

  @Test
  fun `callback page confirms receipt not completed authorization`() = runTest {
    GmailLoopback().use { receiver ->
      val waiting = async { receiver.await(10_000) }
      testScheduler.runCurrent()
      val response = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", URI(receiver.redirectUri).port).use {
          it.soTimeout = 2000
          it.getOutputStream().write("GET /?code=fixture&state=expected HTTP/1.1\r\n\r\n".toByteArray())
          it.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
      }
      assertEquals("expected", waiting.await()?.state)
      assertTrue(response.contains("已收到授权回调"))
      assertFalse(response.contains("授权完成"))
      assertTrue(response.contains("name=\"viewport\""))
      assertTrue(response.contains("Cache-Control: no-store"))
      assertTrue(response.contains("Referrer-Policy: no-referrer"))
      assertTrue(response.contains("Content-Security-Policy:"))
      assertFalse(response.contains("code=fixture"))
      val (head, body) = response.split("\r\n\r\n", limit = 2)
      val length = head.lineSequence().single { it.startsWith("Content-Length:") }.substringAfter(":").trim().toInt()
      assertEquals(body.toByteArray(Charsets.UTF_8).size, length)
    }
  }

  @Test
  fun `receiver times out without a callback`() = runTest {
    GmailLoopback().use { assertNull(it.await(25)) }
  }
}

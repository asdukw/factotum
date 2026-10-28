package com.zoneu.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 失败原因翻译的纯 JVM 单测。
 *
 * 为什么要钉住：以前界面直接显示异常消息，真机上那句是
 * `failed to connect to github.com/20.205.243.166 (port 443) from /172.103.8.162 (port 56054)
 * after 30000ms` —— 用户看到的是两个 IP 和一个毫秒数，**没有一句话是可行动的**。
 * 而这条链路上下一步动作完全取决于失败类型：连不上 = 开 VPN 再点；服务器 404 = 重试没用。
 */
class UpdateHttpTest {
  @Test
  fun `解析不了域名时说清楚下一步是开 VPN`() {
    // 代理没开 / 被墙时最常见的一种。
    val reason = UpdateHttp.failReason(UnknownHostException("github.com"))

    assertTrue(reason, reason.contains("VPN"))
  }

  @Test
  fun `连不上主机时也说开 VPN`() {
    val reason = UpdateHttp.failReason(ConnectException("failed to connect to github.com/20.205.243.166 (port 443)"))

    assertTrue(reason, reason.contains("VPN"))
    // 异常消息里的 IP 与端口不该出现在给用户看的话里 —— 他要的不是这个。
    assertFalse(reason, reason.contains("20.205.243.166"))
    assertFalse(reason, reason.contains("443"))
  }

  @Test
  fun `连上了但没数据与连不上是两句不同的话`() {
    // 下一步动作不同：前者等一等 / 换网络，后者检查代理。合成一句等于什么都没说。
    val reason = UpdateHttp.failReason(SocketTimeoutException("Read timed out"))

    assertTrue(reason, reason.contains("没有数据"))
    assertFalse(reason, reason.contains("VPN"))
  }

  @Test
  fun `TLS 握手失败指向代理`() {
    val reason = UpdateHttp.failReason(SSLException("handshake failed"))

    assertTrue(reason, reason.contains("TLS"))
  }

  @Test
  fun `HTTP 状态码原样带出来，并说明重试没用`() {
    val reason = UpdateHttp.failReason(IllegalStateException("HTTP_404"))

    assertTrue(reason, reason.contains("404"))
    assertTrue(reason, reason.contains("重试没用"))
  }

  @Test
  fun `连接中途断掉时说明会接着下`() {
    // 与「服务器 404」相反：这一种**重试有用**，而且半截文件还在。
    val reason = UpdateHttp.failReason(IllegalStateException(UpdateDownloader.TRANSFER_TRUNCATED))

    assertTrue(reason, reason.contains("接着下"))
  }

  @Test
  fun `认不出的异常只报类型，不带消息`() {
    // 与 AppUpdater.check 同一条规矩：异常消息里可能带 URL，没必要进界面与日志。
    val reason = UpdateHttp.failReason(RuntimeException("https://example.com/secret-path"))

    assertEquals("RuntimeException", reason)
  }

  @Test
  fun `普通 IO 错误归到网络中断`() {
    val reason = UpdateHttp.failReason(IOException("unexpected end of stream"))

    assertTrue(reason, reason.contains("网络中断"))
  }
}

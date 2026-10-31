package com.zoneu.app.agent

import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** 错误码不携带网络 URL、响应正文或卡正文。 */
class CardOperationException(val code: String) : IOException(code)

/** 公网地址策略与连接共用同一 DNS 结果，不在校验后重新解析。 */
internal object CardNetworkPolicy {
  fun url(text: String): HttpUrl {
    val url = text.toHttpUrlOrNull() ?: throw CardOperationException("CARD_INVALID_URL")
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
      throw CardOperationException("CARD_HTTPS_REQUIRED")
    }
    // OkHttp 对 IP 字面量直接建连，不调用 Dns，因此必须在这里检查。
    if ((url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) && !publicAddress(InetAddress.getByName(url.host))) {
      privateAddress()
    }
    return url
  }

  private fun privateAddress(): Nothing = throw CardOperationException("CARD_PRIVATE_ADDRESS")

  fun publicAddress(address: InetAddress): Boolean {
    val bytes = address.address.map { it.toInt() and 255 }
    return when (bytes.size) {
      4 -> publicV4(bytes)

      // Only global unicast. Exclude special-use/documentation/transition ranges as well.
      16 -> bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] < 2) &&
        !(bytes.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) &&
        !(bytes.take(2) == listOf(0x20, 0x02)) &&
        !(bytes.take(3) == listOf(0x3f, 0xff, 0x00))

      else -> false
    }
  }

  private fun publicV4(bytes: List<Int>): Boolean {
    val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
    val blocked = listOf(
      0x00000000L to 8,
      0x0a000000L to 8,
      0x64400000L to 10,
      0x7f000000L to 8,
      0xa9fe0000L to 16,
      0xac100000L to 12,
      0xc0000000L to 24,
      0xc0000200L to 24,
      0xc0586300L to 24,
      0xc0a80000L to 16,
      0xc6120000L to 15,
      0xc6336400L to 24,
      0xcb007100L to 24,
      0xe0000000L to 3,
    )
    return blocked.none { (network, prefix) -> value ushr (32 - prefix) == network ushr (32 - prefix) }
  }

  fun checkedDns(upstream: Dns): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = upstream.lookup(hostname).also { addresses ->
      if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw CardOperationException("CARD_PRIVATE_ADDRESS")
    }
  }

  /** 查询参数可能含签名或 token，全部省略；直链不保存可能含 token 的路径。 */
  fun source(url: HttpUrl, chub: Boolean): String =
    if (chub) url.newBuilder().query(null).fragment(null).build().toString() else "${url.scheme}://${url.host}/"
}

/** 专用无凭据客户端。测试可注入 transport，但生产始终检查 HTTPS 与目的地址。 */
internal class CharacterCardDownloader(
  private val client: OkHttpClient = productionClient(),
  private val clock: () -> Long = System::nanoTime,
) {
  fun download(url: HttpUrl, control: CardDownloadControl): ByteArray {
    var current = url
    val deadline = clock() + TimeUnit.SECONDS.toNanos(90)
    repeat(6) { hop ->
      control.check()
      CardNetworkPolicy.url(current.toString())
      val remaining = deadline - clock()
      verify(remaining > 0, "CARD_TIMEOUT")
      val call = client.newCall(Request.Builder().url(current).header("Accept", "image/png,application/json").build())
      call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
      control.attach(call)
      call.execute().use { response ->
        control.check()
        if (response.code in listOf(301, 302, 303, 307, 308)) {
          verify(hop < 5, "CARD_REDIRECT_LIMIT")
          val next = response.header("Location")?.let { current.resolve(it) }
            ?: throw CardOperationException("CARD_INVALID_REDIRECT")
          current = CardNetworkPolicy.url(next.toString())
        } else {
          verify(response.isSuccessful, "CARD_HTTP_${response.code}")
          return readBody(response, control, deadline)
        }
      }
    }
    throw CardOperationException("CARD_REDIRECT_LIMIT")
  }

  private fun readBody(response: okhttp3.Response, control: CardDownloadControl, deadline: Long): ByteArray {
    val body = response.body ?: throw CardOperationException("CARD_EMPTY_BODY")
    val length = body.contentLength()
    verify(length <= CardInput.MAX_FILE, "CARD_TOO_LARGE")
    val output = ByteArrayOutputStream()
    body.byteStream().use { stream ->
      val buffer = ByteArray(8192)
      while (true) {
        control.check()
        verify(clock() < deadline, "CARD_TIMEOUT")
        val count = stream.read(buffer)
        if (count < 0) break
        verify(output.size() + count <= CardInput.MAX_FILE, "CARD_TOO_LARGE")
        output.write(buffer, 0, count)
      }
    }
    verify(length < 0 || output.size().toLong() == length, "CARD_TRANSFER_TRUNCATED")
    return output.toByteArray()
  }

  private fun verify(condition: Boolean, code: String) {
    if (!condition) throw CardOperationException(code)
  }

  companion object {
    internal fun productionClient(): OkHttpClient = OkHttpClient.Builder()
      .dns(CardNetworkPolicy.checkedDns(Dns.SYSTEM))
      .proxy(Proxy.NO_PROXY)
      .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
      .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
      .callTimeout(90, TimeUnit.SECONDS)
      .build()
  }
}

/** 一个请求只能取消自身；取消先于 HTTP 注册到达时也不会漏掉。 */
internal class CardDownloadControl {
  @Volatile private var cancelled = false

  @Volatile private var call: Call? = null

  fun check() {
    if (cancelled) throw CardOperationException("CARD_CANCELLED")
  }

  @Synchronized fun attach(next: Call) {
    check()
    call = next
    if (cancelled) next.cancel()
  }

  @Synchronized fun cancel() {
    cancelled = true
    call?.cancel()
  }
}

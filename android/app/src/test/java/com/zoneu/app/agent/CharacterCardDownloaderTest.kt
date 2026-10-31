package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CharacterCardDownloaderTest {
  @Test
  fun publicAndPrivateAddressMatrixIncludesIpv6AndMappedAddresses() {
    listOf(
      "127.0.0.1",
      "10.0.0.1",
      "172.16.0.1",
      "192.168.0.1",
      "169.254.169.254",
      "100.64.0.1",
      "0.0.0.0",
      "224.0.0.1",
      "240.1.1.1",
      "192.0.2.1",
      "198.51.100.1",
      "203.0.113.1",
      "::1",
      "::",
      "fe80::1",
      "fc00::1",
      "ff02::1",
      "::ffff:127.0.0.1",
      "2001:db8::1",
      "2002:7f00:1::1",
    ).forEach {
      assertFalse(it, CardNetworkPolicy.publicAddress(InetAddress.getByName(it)))
    }
    listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach {
      assertTrue(it, CardNetworkPolicy.publicAddress(InetAddress.getByName(it)))
    }
    assertThrows(CardOperationException::class.java) { CardNetworkPolicy.url("http://example.com/card.json") }
    assertThrows(CardOperationException::class.java) { CardNetworkPolicy.url("https://user:pass@example.com/card.json") }
  }

  @Test
  fun dnsRejectsMixedResultsAndReturnsTheSameCheckedAddressesWithoutRebind() {
    var calls = 0
    val upstream = object : Dns {
      override fun lookup(hostname: String): List<InetAddress> {
        calls++
        return listOf(InetAddress.getByName(if (calls == 1) "8.8.8.8" else "127.0.0.1"))
      }
    }
    val checked = CardNetworkPolicy.checkedDns(upstream)
    assertEquals("8.8.8.8", checked.lookup("example.com").single().hostAddress)
    assertEquals(1, calls)
    assertThrows(CardOperationException::class.java) { checked.lookup("example.com") }
    val mixed = object : Dns {
      override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1"))
    }
    assertThrows(CardOperationException::class.java) { CardNetworkPolicy.checkedDns(mixed).lookup("example.com") }
  }

  private fun downloader(handler: (Interceptor.Chain) -> Response): CharacterCardDownloader = CharacterCardDownloader(
    OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor(handler)).build(),
  )

  private fun response(chain: Interceptor.Chain, code: Int = 200, location: String? = null, body: String = "{}"): Response =
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
      .apply { location?.let { header("Location", it) } }.body(body.toResponseBody()).build()

  @Test
  fun followsFiveRedirectsButNeverDowngradesHttpsAndDoesNotRetryFailures() {
    var calls = 0
    val redirect = downloader { chain ->
      calls++
      response(chain, 302, "https://example.com/$calls")
    }
    assertEquals(
      "CARD_REDIRECT_LIMIT",
      assertThrows(CardOperationException::class.java) {
        redirect.download("https://example.com/start".toHttpUrl(), CardDownloadControl())
      }.code,
    )
    assertEquals(6, calls)
    val downgrade = downloader { response(it, 302, "http://example.com/plain") }
    assertThrows(CardOperationException::class.java) { downgrade.download("https://example.com".toHttpUrl(), CardDownloadControl()) }
    calls = 0
    val denied = downloader {
      calls++
      response(it, 403)
    }
    assertEquals(
      "CARD_HTTP_403",
      assertThrows(CardOperationException::class.java) {
        denied.download("https://example.com".toHttpUrl(), CardDownloadControl())
      }.code,
    )
    assertEquals(1, calls)
  }

  @Test
  fun cancellationAndSizeLimitPreventSuccessfulDownloadsAndHtmlCannotImport() {
    val cancelled = CardDownloadControl().apply { cancel() }
    assertThrows(CardOperationException::class.java) {
      downloader { error("must not connect") }.download("https://example.com".toHttpUrl(), cancelled)
    }
    val large = downloader { response(it, body = "x".repeat(CardInput.MAX_FILE + 1)) }
    assertEquals(
      "CARD_TOO_LARGE",
      assertThrows(CardOperationException::class.java) {
        large.download("https://example.com".toHttpUrl(), CardDownloadControl())
      }.code,
    )
    val html = downloader { response(it, body = "<html>login</html>") }
      .download("https://example.com".toHttpUrl(), CardDownloadControl())
    assertThrows(CharacterImporter.ImportException::class.java) { CharacterImporter.fromBytes(html, "x") }
    val client = CharacterCardDownloader.productionClient()
    assertFalse(client.followRedirects)
    assertFalse(client.retryOnConnectionFailure)
    assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
  }

  @Test
  fun chubMapsSupportedFieldsAndPreservesUnknownMetadataWithoutDownloadingAvatars() {
    val source = "https://chub.ai/characters/author/project?token=secret".toHttpUrl()
    assertEquals("https://api.chub.ai/api/characters/author/project?full=true", ChubCardAdapter.apiUrl(source).toString())
    assertEquals(null, ChubCardAdapter.apiUrl("https://chub.ai.attacker.test/characters/a/b".toHttpUrl()))
    val bytes = ChubCardAdapter.convert(
      """{"node":{"topics":["标签"],"unknown":{"x":1},"definition":{
      "name":"测试","personality":"人设","tavern_personality":"性格","first_message":"开场",
      "system_prompt":"ARCHIVED_ONLY","extensions":{"com.zoneu.chub_source":"keep"},"other":[1,2]}}}""".toByteArray(),
      source,
    )
    val card = CharacterImporter.fromBytes(bytes, "x").card
    assertEquals("人设", card.description)
    assertEquals("性格", card.personality)
    val output = Json.parseToJsonElement(CardExporter.toV3(card)).jsonObject["data"]!!.jsonObject
    assertEquals("keep", output["extensions"]!!.jsonObject["com.zoneu.chub_source"]!!.jsonPrimitive.content)
    assertTrue(output["extensions"].toString().contains("other"))
    assertFalse(CharacterStore.renderPrompt(card).contains("ARCHIVED_ONLY"))
    assertFalse(CardNetworkPolicy.source(source, true).contains("secret"))
    assertEquals("https://example.com/", CardNetworkPolicy.source("https://example.com/token-secret/card?token=x".toHttpUrl(), false))
  }

  @Test
  fun literalPrivateRedirectNeverReachesSecondTransport() {
    var calls = 0
    val downloader = downloader {
      calls++
      response(it, 302, "https://127.0.0.1/private")
    }
    assertEquals(
      "CARD_PRIVATE_ADDRESS",
      assertThrows(CardOperationException::class.java) {
        downloader.download("https://example.com".toHttpUrl(), CardDownloadControl())
      }.code,
    )
    assertEquals(1, calls)
  }

  @Test
  fun truncatedStreamAndExpiredDeadlineCannotReturnSuccess() {
    val transport = downloader { chain ->
      response(chain).newBuilder().body(object : okhttp3.ResponseBody() {
        override fun contentType(): okhttp3.MediaType? = null

        override fun contentLength(): Long = 100

        override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("short")
      }).build()
    }
    assertEquals(
      "CARD_TRANSFER_TRUNCATED",
      assertThrows(CardOperationException::class.java) {
        transport.download("https://example.com".toHttpUrl(), CardDownloadControl())
      }.code,
    )
    var ticks = 0
    val expired = CharacterCardDownloader(OkHttpClient(), { if (ticks++ == 0) 0L else java.util.concurrent.TimeUnit.SECONDS.toNanos(91) })
    assertEquals(
      "CARD_TIMEOUT",
      assertThrows(CardOperationException::class.java) {
        expired.download("https://example.com".toHttpUrl(), CardDownloadControl())
      }.code,
    )
  }

  @Test
  fun cancellationDuringBodyStopsBeforeReturningBytes() {
    val control = CardDownloadControl()
    val transport = downloader { chain ->
      response(chain).newBuilder().body(object : okhttp3.ResponseBody() {
        private val data = okio.Buffer().writeUtf8("body")

        override fun contentType(): okhttp3.MediaType? = null

        override fun contentLength(): Long = -1

        override fun source(): okio.BufferedSource = object : okio.ForwardingSource(data) {
          override fun read(sink: okio.Buffer, byteCount: Long): Long {
            control.cancel()
            return super.read(sink, byteCount)
          }
        }.buffer()
      }).build()
    }
    assertThrows(CardOperationException::class.java) {
      transport.download("https://example.com".toHttpUrl(), control)
    }
  }
}

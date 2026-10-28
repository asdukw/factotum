package com.zoneu.app.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GmailSessionTest {
  private val client = GmailClient("client", "secret")
  private val old = GmailCredential("old-refresh", client, "old@example.test")

  private class Store(initial: GmailCredentialRead) : GmailCredentialStore {
    var value = initial
    var writable = true
    var writes = 0
    var clears = 0

    override fun read() = value

    override fun save(credential: GmailCredential): Boolean {
      writes++
      if (writable) value = GmailCredentialRead.Present(credential)
      return writable
    }

    override fun clear(): Boolean {
      clears++
      if (writable) value = GmailCredentialRead.Missing
      return writable
    }
  }

  private fun granted(refresh: String? = null, access: String = "access") = TokenOutcome.Granted(access, refresh, 3600, null)

  private fun session(store: Store, refresh: suspend (GmailClient, String) -> TokenOutcome = { _, _ -> granted() }) =
    GmailSession(store, { client }, refresh, { 0 }, { 1000 })

  @Test
  fun `missing long lived credential never publishes connected cache`() = runTest {
    val store = Store(GmailCredentialRead.Missing)
    val session = session(store)
    val result = session.commitConnection(session.beginConnection(), client, granted())
    assertEquals("MISSING_REFRESH_TOKEN", (result as AccessTokenResult.Unavailable).code)
    session.cancelPending()
    assertEquals("NOT_CONNECTED", (session.accessToken() as AccessTokenResult.Unavailable).code)
    assertEquals(0, store.writes)
  }

  @Test
  fun `failed durable save does not publish an access token`() = runTest {
    val store = Store(GmailCredentialRead.Missing).also { it.writable = false }
    val session = session(store)
    val result = session.commitConnection(session.beginConnection(), client, granted("new"))
    assertEquals("STORAGE_FAILED", (result as AccessTokenResult.Unavailable).code)
    assertEquals("STORAGE_FAILED", (session.accessToken() as AccessTokenResult.Unavailable).code)
  }

  @Test
  fun `new auth instance restores saved credentials without browser`() = runTest {
    val store = Store(GmailCredentialRead.Missing)
    val session = session(store)
    assertTrue(session.commitConnection(session.beginConnection(), client, granted("new")) is AccessTokenResult.Ok)
    val restarted = session(store) { config, token ->
      assertEquals(client, config)
      assertEquals("new", token)
      granted(access = "after-restart")
    }
    assertEquals("after-restart", (restarted.accessToken() as AccessTokenResult.Ok).accessToken)
  }

  @Test
  fun `refresh omitting new refresh token keeps existing record without rewrite`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    assertTrue(session(store).accessToken() is AccessTokenResult.Ok)
    assertEquals(GmailCredentialRead.Present(old), store.value)
    assertEquals(0, store.writes)
  }

  @Test
  fun `legacy token is bound only after successful refresh`() = runTest {
    val store = Store(GmailCredentialRead.Present(old.copy(client = null)))
    assertTrue(session(store).accessToken() is AccessTokenResult.Ok)
    assertEquals(GmailCredentialRead.Present(old), store.value)
  }

  @Test
  fun `concurrent requests single flight refresh`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    var calls = 0
    val session = session(store) { _, _ ->
      calls++
      granted()
    }
    List(12) { async { session.accessToken() } }.awaitAll().forEach { assertTrue(it is AccessTokenResult.Ok) }
    assertEquals(1, calls)
  }

  @Test
  fun `late invalid grant cannot erase a new connection`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<TokenOutcome>()
    val session = session(store) { _, _ ->
      started.complete(Unit)
      result.await()
    }
    val pending = async { session.accessToken() }
    started.await()
    assertTrue(session.commitConnection(session.beginConnection(), client, granted("new-refresh")) is AccessTokenResult.Ok)
    result.complete(TokenOutcome.Denied("invalid_grant"))
    assertEquals("STALE_OPERATION", (pending.await() as AccessTokenResult.Unavailable).code)
    assertEquals("new-refresh", (store.value as GmailCredentialRead.Present).credential.refreshToken)
    assertEquals(0, store.clears)
  }

  @Test
  fun `late refresh success cannot revive disconnected account`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val started = CompletableDeferred<Unit>()
    val result = CompletableDeferred<TokenOutcome>()
    val session = session(store) { _, _ ->
      started.complete(Unit)
      result.await()
    }
    val pending = async { session.accessToken() }
    started.await()
    assertTrue(session.disconnect())
    result.complete(granted("rotated"))
    assertEquals("STALE_OPERATION", (pending.await() as AccessTokenResult.Unavailable).code)
    assertEquals(GmailCredentialRead.Missing, store.value)
    assertEquals("NOT_CONNECTED", session.problem())
  }

  @Test
  fun `cancelled connection cannot publish credentials`() {
    val store = Store(GmailCredentialRead.Missing)
    val session = session(store)
    val ticket = session.beginConnection()
    session.cancelPending()
    assertEquals("STALE_OPERATION", (session.commitConnection(ticket, client, granted("new")) as AccessTokenResult.Unavailable).code)
    assertEquals(0, store.writes)
  }

  @Test
  fun `temporary network and configuration errors preserve credentials`() = runTest {
    for (code in listOf("TEMPORARY", "invalid_response", "invalid_client", "invalid_request")) {
      val store = Store(GmailCredentialRead.Present(old))
      val session = session(store) { _, _ -> TokenOutcome.Denied(code) }
      assertEquals(code, (session.accessToken() as AccessTokenResult.Unavailable).code)
      assertEquals(GmailCredentialRead.Present(old), store.value)
      assertEquals(0, store.clears)
    }
    val store = Store(GmailCredentialRead.Present(old))
    val network = session(store) { _, _ -> throw IOException() }.accessToken()
    assertEquals("NETWORK", (network as AccessTokenResult.Unavailable).code)
    assertEquals(GmailCredentialRead.Present(old), store.value)
  }

  @Test
  fun `only current invalid grant clears credentials`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val result = session(store) { _, _ -> TokenOutcome.Denied("invalid_grant") }.accessToken()
    assertEquals("invalid_grant", (result as AccessTokenResult.Unavailable).code)
    assertEquals(1, store.clears)
    assertEquals(GmailCredentialRead.Missing, store.value)
  }

  @Test
  fun `unreadable vault is distinct from never connected and preserved`() = runTest {
    val store = Store(GmailCredentialRead.Unreadable)
    val session = session(store)
    assertEquals("CREDENTIAL_UNREADABLE", session.problem())
    assertEquals("CREDENTIAL_UNREADABLE", (session.accessToken() as AccessTokenResult.Unavailable).code)
    assertEquals(0, store.clears)
  }

  @Test
  fun `client changes bypass neither stored binding nor cache`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    var config = client
    val session = GmailSession(store, { config }, { _, _ -> granted() }, { 0 }, { 1000 })
    assertTrue(session.accessToken() is AccessTokenResult.Ok)
    config = GmailClient("other", "other-secret")
    assertEquals("CLIENT_CHANGED", (session.accessToken() as AccessTokenResult.Unavailable).code)
    assertEquals(GmailCredentialRead.Present(old), store.value)
  }

  @Test
  fun `wall clock changes cannot extend access token freshness`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    var elapsed = 0L
    var wall = 1000L
    var calls = 0
    val session = GmailSession(store, { client }, { _, _ ->
      calls++
      granted()
    }, { elapsed }, { wall })
    session.accessToken()
    wall = -10000000
    elapsed = 3600000
    session.accessToken()
    assertEquals(2, calls)
  }

  @Test
  fun `concurrent 401 recovery ignores stale rejected tokens after refresh`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    var calls = 0
    val session = session(store) { _, _ ->
      calls++
      granted(access = "at-$calls")
    }
    val rejected = (session.accessToken() as AccessTokenResult.Ok).accessToken
    val recovered = List(10) { async { session.accessToken(rejected) } }.awaitAll()
    assertTrue(recovered.all { (it as AccessTokenResult.Ok).accessToken == "at-2" })
    assertEquals(2, calls)
  }

  @Test
  fun `short positive ttl token remains usable`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val session = session(store) { _, _ -> TokenOutcome.Granted("at", null, 10, null) }
    assertTrue(session.accessToken() is AccessTokenResult.Ok)
    assertTrue(session.accessToken() is AccessTokenResult.Ok)
    assertEquals(0, store.writes)
  }

  @Test
  fun `missing new refresh token never borrows previous account grant`() {
    val store = Store(GmailCredentialRead.Present(old))
    val session = session(store)
    val result = session.commitConnection(session.beginConnection(), client, granted())
    assertTrue(result is AccessTokenResult.Unavailable)
    assertEquals(GmailCredentialRead.Present(old), store.value)
    assertFalse(store.value == GmailCredentialRead.Missing)
  }

  @Test
  fun `failed disconnect blocks memory access and reports storage failure`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val session = session(store)
    session.accessToken()
    store.writable = false
    assertFalse(session.disconnect())
    assertEquals("STORAGE_FAILED", (session.accessToken() as AccessTokenResult.Unavailable).code)
  }

  @Test
  fun `interactive connection suspends old grant refresh until commit`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    var calls = 0
    val session = session(store) { _, _ ->
      calls++
      granted()
    }
    val ticket = session.beginConnection()
    assertEquals("CONNECTION_PENDING", (session.accessToken() as AccessTokenResult.Unavailable).code)
    assertEquals(0, calls)
    assertTrue(session.commitConnection(ticket, client, granted("new")) is AccessTokenResult.Ok)
    assertTrue(session.accessToken() is AccessTokenResult.Ok)
  }

  @Test
  fun `stale connection cannot persist client configuration`() {
    val session = session(Store(GmailCredentialRead.Missing))
    val ticket = session.beginConnection()
    session.cancelPending()
    var saved = false
    assertFalse(
      session.prepareConnection(ticket) {
        saved = true
        true
      },
    )
    assertFalse(saved)
  }

  @Test
  fun `storage exception fails without publishing cache`() = runTest {
    val store = object : GmailCredentialStore {
      override fun read() = GmailCredentialRead.Missing

      override fun save(credential: GmailCredential): Boolean = error("synthetic")

      override fun clear() = true
    }
    val session = GmailSession(store, { client }, { _, _ -> granted() }, { 0 }, { 0 })
    val ticket = session.beginConnection()
    val result = session.commitConnection(ticket, client, granted("new"))
    assertEquals("STORAGE_FAILED", (result as AccessTokenResult.Unavailable).code)
    session.cancelPending()
    assertEquals("STORAGE_FAILED", (session.accessToken() as AccessTokenResult.Unavailable).code)
  }

  @Test
  fun `cancelled refresh leaves persisted authorization unchanged`() = runTest {
    val store = Store(GmailCredentialRead.Present(old))
    val started = CompletableDeferred<Unit>()
    val session = session(store) { _, _ ->
      started.complete(Unit)
      kotlinx.coroutines.awaitCancellation()
    }
    val request = async { session.accessToken() }
    started.await()
    request.cancel()
    request.join()
    assertEquals(GmailCredentialRead.Present(old), store.value)
    assertEquals(0, store.writes)
  }

  @Test
  fun `record round trip and legacy decoding`() {
    assertEquals(GmailCredentialRead.Present(old), decodeGmailCredential(encodeGmailCredential(old)))
    assertEquals(GmailCredentialRead.Present(old.copy(client = null)), decodeGmailCredential("old-refresh", old.account))
    for (raw in listOf("", "null", "{", "{\"version\":1,\"refreshToken\":null}")) {
      assertEquals(GmailCredentialRead.Unreadable, decodeGmailCredential(raw))
    }
    assertNull(session(Store(GmailCredentialRead.Present(old))).problem())
  }
}

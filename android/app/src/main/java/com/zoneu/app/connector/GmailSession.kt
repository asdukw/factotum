package com.zoneu.app.connector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** 所有提交与 generation 检查位于同一短临界区，网络期间不持有状态锁。 */
internal class GmailSession(
  private val store: GmailCredentialStore,
  private val client: () -> GmailClient,
  private val refresh: suspend (GmailClient, String) -> TokenOutcome,
  private val elapsed: () -> Long,
  private val wall: () -> Long,
) {
  private val gate = Any()
  private val refreshLock = Mutex()
  private var generation = 0L
  private var cache: Cached? = null
  private var storageFailed = false
  private var disconnected = false
  private var pendingConnection: Long? = null

  private data class Cached(val token: String, val deadline: Long, val expiresAt: Long, val client: GmailClient)

  private data class Snapshot(val generation: Long, val credential: GmailCredential, val client: GmailClient)

  fun beginConnection(): Long = synchronized(gate) {
    generation++
    cache = null
    pendingConnection = generation
    generation
  }

  fun prepareConnection(ticket: Long, persistClient: () -> Boolean): Boolean = synchronized(gate) {
    ticket == generation && persistClient()
  }

  fun cancelPending() = synchronized(gate) {
    generation++
    pendingConnection = null
  }

  fun cancelConnection(ticket: Long) = synchronized(gate) {
    if (generation == ticket) {
      generation++
      pendingConnection = null
    }
  }

  fun commitConnection(ticket: Long, config: GmailClient, granted: TokenOutcome.Granted): AccessTokenResult = synchronized(gate) {
    if (ticket == generation) pendingConnection = null
    when {
      ticket != generation || config != client() -> tokenFailure("STALE_OPERATION")

      granted.refreshToken.isNullOrBlank() -> tokenFailure("MISSING_REFRESH_TOKEN")

      !save(GmailCredential(granted.refreshToken, config, granted.account)) -> tokenFailure("STORAGE_FAILED")

      else -> {
        disconnected = false
        pendingConnection = null
        generation++
        cache(granted, config)
      }
    }
  }

  fun disconnect(): Boolean = synchronized(gate) {
    generation++
    cache = null
    disconnected = true
    pendingConnection = null
    storageFailed = !runCatching { store.clear() }.getOrDefault(false)
    !storageFailed
  }

  fun invalidate(rejectedToken: String) = synchronized(gate) {
    if (cache?.token == rejectedToken) cache = null
  }

  fun problem(): String? = synchronized(gate) {
    when {
      storageFailed -> "STORAGE_FAILED"

      disconnected -> "NOT_CONNECTED"

      else -> when (val read = read()) {
        GmailCredentialRead.Missing -> "NOT_CONNECTED"
        GmailCredentialRead.Unreadable -> "CREDENTIAL_UNREADABLE"
        is GmailCredentialRead.Present -> if (read.credential.client?.let { it != client() } == true) "CLIENT_CHANGED" else null
      }
    }
  }

  suspend fun accessToken(rejectedToken: String? = null): AccessTokenResult {
    rejectedToken?.let(::invalidate)
    return refreshLock.withLock {
      currentCoroutineContext().ensureActive()
      val snapshot = synchronized(gate) { snapshot() }
      if (snapshot is AccessTokenResult) return@withLock snapshot
      snapshot as Snapshot
      val outcome = try {
        refresh(snapshot.client, snapshot.credential.refreshToken)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: IOException) {
        return@withLock tokenFailure("NETWORK")
      }
      currentCoroutineContext().ensureActive()
      synchronized(gate) { finishRefresh(snapshot, outcome) }
    }
  }

  private fun snapshot(): Any {
    if (pendingConnection != null) return tokenFailure("CONNECTION_PENDING")
    val problem = problem()
    if (problem != null) return tokenFailure(problem)
    val config = client()
    if (config.id.isBlank()) return tokenFailure("NOT_CONFIGURED")
    cache?.takeIf { it.client == config && elapsed() < it.deadline }?.let {
      return AccessTokenResult.Ok(it.token, it.expiresAt)
    }
    return when (val stored = read()) {
      is GmailCredentialRead.Present -> Snapshot(generation, stored.credential, stored.credential.client ?: config)
      else -> tokenFailure("CREDENTIAL_UNREADABLE")
    }
  }

  private fun finishRefresh(snapshot: Snapshot, outcome: TokenOutcome): AccessTokenResult {
    if (snapshot.generation != generation || snapshot.client != client() || read() != GmailCredentialRead.Present(snapshot.credential)) {
      return tokenFailure("STALE_OPERATION")
    }
    return when (outcome) {
      is TokenOutcome.Denied -> {
        if (outcome.code == "invalid_grant" && !disconnect()) return tokenFailure("STORAGE_FAILED")
        tokenFailure(outcome.code)
      }

      is TokenOutcome.Granted -> {
        val next = snapshot.credential.copy(
          refreshToken = outcome.refreshToken ?: snapshot.credential.refreshToken,
          client = snapshot.client,
          account = outcome.account ?: snapshot.credential.account,
        )
        if (next != snapshot.credential && !save(next)) tokenFailure("STORAGE_FAILED") else cache(outcome, snapshot.client)
      }
    }
  }

  private fun read(): GmailCredentialRead = runCatching { store.read() }.getOrDefault(GmailCredentialRead.Unreadable)

  private fun save(credential: GmailCredential): Boolean {
    val saved = runCatching { store.save(credential) }.getOrDefault(false)
    storageFailed = !saved
    if (!saved) cache = null
    return saved
  }

  private fun cache(granted: TokenOutcome.Granted, config: GmailClient): AccessTokenResult.Ok {
    val ttl = granted.expiresInSeconds.coerceIn(1, MAX_CACHE_SECONDS) * 1000L
    val usable = ttl - minOf(60_000L, ttl / 10)
    val expiresAt = wall() + ttl
    cache = Cached(granted.accessToken, elapsed() + usable, expiresAt, config)
    return AccessTokenResult.Ok(granted.accessToken, expiresAt)
  }

  private companion object {
    const val MAX_CACHE_SECONDS = 7 * 24 * 60 * 60L
  }
}

package com.zoneu.app.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal class GmailAuthorizationException(val code: String) : IOException(code)

/** 只保存尝试编号与就绪/停止信号，不持有 OAuth 配置或令牌。 */
internal class GmailAuthorizationLease {
  class Lease(val id: Long) {
    val ready = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<String>()

    suspend fun <T> protect(block: suspend () -> T): T = coroutineScope {
      if (withTimeoutOrNull(10_000) {
          ready.await()
          true
        } != true
      ) {
        throw GmailAuthorizationException("AUTH_SERVICE_FAILED")
      }
      if (ended.isCompleted) throw GmailAuthorizationException(ended.await())
      val work = async { block() }
      try {
        select {
          ended.onAwait { throw GmailAuthorizationException(it) }
          work.onAwait { it }
        }
      } finally {
        work.cancel()
      }
    }
  }

  private var sequence = 0L
  private var active: Lease? = null

  @Synchronized
  fun begin(): Lease {
    active?.ended?.complete("STALE_OPERATION")
    return Lease(++sequence).also { active = it }
  }

  @Synchronized
  fun current(id: Long): Lease? = active?.takeIf { it.id == id }

  @Synchronized
  fun release(id: Long): Boolean {
    if (active?.id != id) return false
    active = null
    return true
  }

  @Synchronized
  fun stop(id: Long, code: String) {
    current(id)?.let {
      it.ended.complete(code)
      it.ready.completeExceptionally(GmailAuthorizationException(code))
    }
  }
}

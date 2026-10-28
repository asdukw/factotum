package com.zoneu.app.connector

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 仅监听回环；取消同时关闭 accept 与已接收的 socket，不等待读超时。 */
internal class GmailLoopback : AutoCloseable {
  private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
  private val gate = Any()
  private var closed = false
  private var client: Socket? = null
  val redirectUri = "http://127.0.0.1:${server.localPort}"

  suspend fun await(timeoutMillis: Long): RedirectParams? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { close() }
    thread(name = "gmail-callback", isDaemon = true) {
      val result = runCatching { receive(timeoutMillis) }
      if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
    }
  }

  private fun receive(timeoutMillis: Long): RedirectParams? {
    val start = System.nanoTime()
    while ((System.nanoTime() - start) / 1_000_000 < timeoutMillis) {
      val remaining = timeoutMillis - (System.nanoTime() - start) / 1_000_000
      server.soTimeout = remaining.coerceIn(1, 1000).toInt()
      val socket = try {
        server.accept()
      } catch (_: SocketTimeoutException) {
        continue
      }
      synchronized(gate) {
        if (closed) socket.close() else client = socket
      }
      if (socket.isClosed) return null
      val result = socket.use {
        it.soTimeout = remaining.coerceIn(1, 5000).toInt()
        val params = runCatching { readLine(it)?.let(::parseRedirectRequest) }.getOrNull()
        val callback = params?.takeIf { value -> value.code != null || value.error != null }
        respond(it, callback)
        callback
      }
      synchronized(gate) { client = null }
      if (result != null) return result
    }
    return null
  }

  override fun close() = synchronized(gate) {
    closed = true
    runCatching { client?.close() }
    runCatching { server.close() }
    Unit
  }

  private fun readLine(socket: Socket): String? {
    val input = BufferedInputStream(socket.getInputStream())
    val line = StringBuilder()
    val deadline = System.nanoTime() + socket.soTimeout * 1_000_000L
    while (line.length < 8192) {
      val remaining = (deadline - System.nanoTime()) / 1_000_000
      if (remaining <= 0) return null
      socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
      val next = input.read()
      if (next < 0) return null
      if (next == '\n'.code) return line.toString().trimEnd('\r')
      line.append(next.toChar())
    }
    return null
  }

  private fun respond(socket: Socket, callback: RedirectParams?) {
    val body = if (callback != null) gmailCallbackPage(denied = callback.error != null) else ""
    val status = if (callback != null) "200 OK" else "204 No Content"
    val bytes = body.toByteArray(Charsets.UTF_8)
    runCatching {
      socket.getOutputStream().apply {
        val head = listOf(
          "HTTP/1.1 $status",
          "Content-Type: text/html; charset=utf-8",
          "Cache-Control: no-store",
          "Referrer-Policy: no-referrer",
          "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
          "Content-Length: ${bytes.size}",
          "Connection: close",
          "",
          "",
        ).joinToString("\r\n")
        write(head.toByteArray())
        write(bytes)
        flush()
      }
    }
  }
}

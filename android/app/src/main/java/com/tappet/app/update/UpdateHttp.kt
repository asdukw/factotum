package com.tappet.app.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 更新链路上共用的 HTTP 与哈希。
 *
 * 抽出来是因为 `AppUpdater`（查版本 / 装包）与 [UpdateDownloader]（取字节 / 续传）都要发请求、
 * 都要算 SHA-256，而 detekt 的「一个 object 最多 20 个函数」把两者塞在一起是塞不下的。
 * 顺带的好处是这两个 object 各自只管一件事。
 */
internal object UpdateHttp {
  const val TIMEOUT_MS = 30_000

  fun getText(url: String): String {
    val connection = open(url)
    return try {
      connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      connection.disconnect()
    }
  }

  fun open(url: String): HttpURLConnection = open(url, 0L, null)

  /**
   * 打开连接。`from > 0` 时带 `Range` 续传。
   *
   * `If-Range` 与 `Range` 成对使用：ETag 对不上时服务器直接回 200 整份，而不是把两份
   * 不同内容拼起来。没存过 ETag（第一次下、或上次的响应没给）就只能裸 `Range` ——
   * 那种情况下拼错的可能仍然存在，但整包哈希会兜住它。
   */
  fun open(url: String, from: Long, etag: String?): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      // GitHub API 要求 User-Agent；不带会被 403。
      setRequestProperty("User-Agent", "tappet-updater")
      setRequestProperty("Accept", "application/vnd.github+json")
      instanceFollowRedirects = true
      if (from > 0L) {
        setRequestProperty("Range", "bytes=$from-")
        etag?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Range", it) }
      }
    }

  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

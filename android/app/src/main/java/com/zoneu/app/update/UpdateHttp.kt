package com.zoneu.app.update

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * 更新链路上共用的 HTTP 与哈希。
 *
 * 抽出来是因为 `AppUpdater`（查版本 / 装包）与 [UpdateDownloader]（取字节 / 续传）都要发请求、
 * 都要算 SHA-256，而 detekt 的「一个 object 最多 20 个函数」把两者塞在一起是塞不下的。
 * 顺带的好处是这两个 object 各自只管一件事。
 */
internal object UpdateHttp {
  /**
   * 建连超时。**15 秒而不是 30**：这条链路上最常见的失败是「GitHub 连不上」（代理 / VPN 没开），
   * 而那种情况下 TCP 握手根本不会回 —— 30 秒就是用户盯着进度条干等半分钟才知道该开 VPN。
   * 15 秒对任何正常网络都绰绰有余（实测本机到 GitHub 的 TLS 握手不到 1 秒）。
   *
   * **不自动重试**：查版本、下模型、下安装包这三条都只在用户点的时候联网，连不上就直接回来把
   * 原因说清楚，等用户处理好网络再点一次。后台自动重试会让人以为「已经在下了」而其实一次都
   * 没连上 —— 这与连接器、应用内更新那条「不后台轮询」是同一条规矩。
   */
  const val CONNECT_TIMEOUT_MS = 15_000

  /**
   * 读超时。它是**单次 read 的阻塞上限**，不是整个下载的上限 —— 只要数据还在动就不会触发。
   * 62MB 的模型在 50KB/s 的线路上要下二十分钟，这个值不影响它；它挡的是「连上了但不给数据」。
   */
  const val READ_TIMEOUT_MS = 15_000

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
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      // GitHub API 要求 User-Agent；不带会被 403。
      setRequestProperty("User-Agent", "zoneu-updater")
      setRequestProperty("Accept", "application/vnd.github+json")
      instanceFollowRedirects = true
      if (from > 0L) {
        setRequestProperty("Range", "bytes=$from-")
        etag?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-Range", it) }
      }
    }

  /**
   * 给用户看的失败原因。**纯函数，JVM 单测钉住**（`UpdateHttpTest`）。
   *
   * 为什么要有它：以前界面直接把异常消息甩出来 —— 真机上那句是
   * `failed to connect to github.com/20.205.243.166 (port 443) from /172.103.8.162 (port 56054)
   * after 30000ms`。它对用户没有一句话是可行动的（IP 还是本机的），而这条链路上
   * **下一步动作完全取决于失败类型**：连不上 = 开 VPN 再点；服务器 404 = 地址变了，重试没用。
   *
   * 兜底**只报类型不报消息**：异常消息里可能带 URL，虽然 URL 里没有凭据，也没必要进界面与日志
   * （与 `AppUpdater.check` 那条同一条规矩）。
   */
  fun failReason(error: Throwable): String =
    when {
      error is UnknownHostException -> "解析不了 github.com —— 打开 VPN 或换个网络再试"

      error is SocketTimeoutException -> "连上之后一直没有数据 —— 网络不通或太慢，稍后再试"

      // 被代理 / 中间设备拦下时最典型的样子：握手阶段就断了。
      error is SSLException -> "TLS 握手失败 —— 代理或网络可能拦了它"

      error is ConnectException -> "连不上 github.com —— 打开 VPN 或换个网络再试"

      error.message?.startsWith("HTTP_") == true ->
        "服务器返回 ${error.message?.removePrefix("HTTP_")}（文件可能已下架，重试没用）"

      error.message == UpdateDownloader.TRANSFER_TRUNCATED -> "连接中断 —— 已下载的部分会留着，下次接着下"

      error is IOException -> "网络中断（${error.javaClass.simpleName}）—— 已下载的部分会留着"

      else -> error.javaClass.simpleName
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

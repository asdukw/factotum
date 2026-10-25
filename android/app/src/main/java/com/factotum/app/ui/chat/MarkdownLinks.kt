package com.factotum.app.ui.chat

/**
 * 助手正文里链接的 scheme 白名单。
 *
 * 链接 URL 来自模型输出，属于不可信内容：直接把任意 scheme 交给
 * `UriHandler` 等于把 `intent://`、`file://`、`javascript:` 这类跳转
 * 交给系统去执行。只放行浏览器能安全处理的三种。
 */
object MarkdownLinks {
  private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")

  /** `true` 表示这个 URL 可以交给系统打开。相对链接（`#anchor`）也返回 false。 */
  fun isAllowed(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
    return scheme in ALLOWED_SCHEMES
  }
}

package com.tappet.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新：从 GitHub Releases 取最新版、下载、**校验**、交给系统安装器。
 *
 * **为什么不是「跳到 release 页面让用户自己下」**：那条路把三件事推给用户（浏览器下载 →
 * 找文件 → 点安装），而且**没有任何校验**。这里多做两步换来的是一条结构性保证：
 *
 * 1. **SHA-256 对照 release 里那份 `.sha256`** —— 挡下载损坏与半截文件；
 * 2. **签名证书比对** —— 取下载到的 APK 的签名证书，与**当前安装包**的签名逐字节比。
 *    这一步才是真正的防线：哈希也从同一个地方来，换掉整个 release 的人能同时换掉哈希。
 *    证书对不上就直接拒，绝不交给安装器。
 *
 * **不静默安装**：做不到（没有 root / device owner），也不该做 —— 系统安装器的确认框
 * 是最后一道闸。这里只负责把**验证过的** APK 递过去。
 *
 * **不做后台轮询**：只在用户点「检查更新」时联网（与连接器「不后台轮询」同一条原则）。
 */
object AppUpdater {
  private const val API_LATEST = "https://api.github.com/repos/asdukw/tappet/releases/latest"
  private const val APK_PREFIX = "tappet-"
  private const val APK_SUFFIX = "-arm64-v8a.apk"
  private const val TIMEOUT_MS = 30_000

  /** 一次检查的结果。`release` 只有 [UpdateCheck.Available] 才非空。 */
  sealed interface UpdateCheck {
    data class UpToDate(val current: String) : UpdateCheck

    data class Available(val current: String, val release: Release) : UpdateCheck

    data class Failed(val reason: String) : UpdateCheck
  }

  /**
   * 一个可下载的版本。
   *
   * 只挑 **[apkUrl]** 与 [sha256Url]：二者缺一就不算可用版本 —— 没有哈希就不下载
   * （理由见类注释第 1 条）。
   */
  data class Release(
    val tag: String,
    val version: String,
    val pageUrl: String,
    val apkUrl: String,
    val sha256Url: String,
  )

  /**
   * 比较两个版本号，返回 [latest] 是否比 [current] 新。
   *
   * 按点分段做**数值**比较（`0.10.0 > 0.9.0`，字符串比较会错），段数不一致时短的一方补 0。
   * 非数字段按 0 处理 —— 我们自己发版，tag 一直是 `vX.Y.Z`；把这条说清楚是为了**不要**
   * 以后有人拿它去比 `1.0.0-beta` 那种带预发布标记的版本（那需要另一套规则）。
   */
  fun isNewerVersion(current: String, latest: String): Boolean {
    val a = parseVersion(current)
    val b = parseVersion(latest)
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
      val left = a.getOrElse(i) { 0 }
      val right = b.getOrElse(i) { 0 }
      if (right != left) return right > left
    }
    return false
  }

  private fun parseVersion(raw: String): List<Int> =
    raw.trim().removePrefix("v").substringBefore('-').split('.').map { part -> part.toIntOrNull() ?: 0 }

  /**
   * 查最新 release。网络 / 解析失败都归到 [UpdateCheck.Failed]，由 UI 如实显示。
   *
   * 这里的失败面太宽（网络异常、HTTP 状态、JSON 形状），逐类捕获只会漏掉没预料到的那种；
   * 而语义就是「查不成 → 如实说查不成」，所以显式标注泛捕获。与 `DshStreamer` 的同类取舍一致。
   */
  @Suppress("TooGenericExceptionCaught")
  fun check(currentVersion: String): UpdateCheck {
    return try {
      val body = getText(API_LATEST)
      val release = parseRelease(Json.parseToJsonElement(body).jsonObject) ?: return UpdateCheck.UpToDate(currentVersion)
      if (isNewerVersion(currentVersion, release.version)) {
        UpdateCheck.Available(currentVersion, release)
      } else {
        UpdateCheck.UpToDate(currentVersion)
      }
    } catch (error: Exception) {
      // 只报类型：异常消息里可能带 URL（URL 里没有凭据，但也没必要进日志）。
      UpdateCheck.Failed(error.javaClass.simpleName)
    }
  }

  /**
   * 从 release JSON 里挑出要的东西。
   *
   * `assets` 里同时有 `.apk` 与 `.apk.sha256`，按名字配成对；**任何一个缺失就返回 null**，
   * 调用方据此当成「没有可用更新」—— 宁可说「已是最新」，也不下载一个没有哈希的包。
   */
  internal fun parseRelease(json: JsonObject): Release? {
    val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
    val page = json["html_url"]?.jsonPrimitive?.content ?: return null
    val assets = json["assets"]?.jsonArray ?: return null
    val urls = assets.associate { asset ->
      val name = asset.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
      name to asset.jsonObject["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
    }
    val apkName = urls.keys.firstOrNull { it.startsWith(APK_PREFIX) && it.endsWith(APK_SUFFIX) } ?: return null
    val shaName = "$apkName.sha256"
    val apkUrl = urls[apkName]?.takeIf { it.isNotBlank() } ?: return null
    val shaUrl = urls[shaName]?.takeIf { it.isNotBlank() } ?: return null
    return Release(
      tag = tag,
      version = tag.removePrefix("v"),
      pageUrl = page,
      // URL 里的 tag 是本仓库自己的 release，不是用户输入；不拼到本地路径上。
      apkUrl = apkUrl,
      sha256Url = shaUrl,
    )
  }

  /**
   * 下载并校验。**任何一步不过都抛异常**，调用方据此不给安装入口。
   *
   * @param onProgress 已下载字节 / 总字节（总字节未知时为 -1），只用来更新 UI。
   */
  fun download(context: Context, release: Release, onProgress: (Long, Long) -> Unit): File {
    val expected = getText(release.sha256Url).trim().substringBefore(' ').lowercase()
    require(expected.length == 64) { "SHA256_MALFORMED" }

    val target = File(context.cacheDir, "update-${release.version}.apk")
    val connection = open(release.apkUrl)
    try {
      copyBody(connection, target, onProgress)
    } finally {
      connection.disconnect()
    }

    if (sha256(target) != expected) {
      target.delete()
      error("SHA256_MISMATCH")
    }
    if (!signerMatches(context, target)) {
      target.delete()
      error("SIGNER_MISMATCH")
    }
    return target
  }

  /**
   * 把响应体抄进文件。
   *
   * 拆成两个函数只是为了让嵌套深度留在 detekt 的门槛内（函数 → use → use → 调用，
   * 而不是函数 → use → use → while → if）。这不是为规则而规则：五层嵌套里漏写一个
   * 「读到末尾就退出」是很难看出来的。
   */
  private fun copyBody(connection: HttpURLConnection, target: File, onProgress: (Long, Long) -> Unit) {
    connection.inputStream.use { input ->
      target.outputStream().use { output -> pump(input, output, connection.contentLengthLong, onProgress) }
    }
  }

  private fun pump(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    total: Long,
    onProgress: (Long, Long) -> Unit,
  ) {
    val buffer = ByteArray(64 * 1024)
    var written = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      output.write(buffer, 0, read)
      written += read
      onProgress(written, total)
    }
  }

  /**
   * 下载到的 APK 是否与**当前安装包**同一个签名。
   *
   * 这是整条更新链路上唯一挡得住「换了个包冒充更新」的一步：`getPackageArchiveInfo` 读的是
   * 文件本身的签名，与已安装包的签名逐字节比。对不上就拒 —— 换签名 = 另一个 App，
   * 装上去旧数据也读不到。
   */
  fun signerMatches(context: Context, apk: File): Boolean {
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    // 读不出来的情况一律当成「对不上」—— 这条路径上只有 fail closed 是对的。
    val downloaded =
      runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)?.signingInfo }
        .getOrNull()
    val installed =
      runCatching { context.packageManager.getPackageInfo(context.packageName, flags).signingInfo }
        .getOrNull()
    val left = downloaded?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    val right = installed?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
    return left.isNotEmpty() && left == right
  }

  /** 交给系统安装器的意图。调用方要先确认「安装未知应用」已授权（见 [canInstall]）。 */
  fun installIntent(context: Context, apk: File): Intent {
    val uri: Uri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    return Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  /** Android 8+ 需要用户为「这个来源」打开安装权限；没打开时引导去系统设置。 */
  fun canInstall(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

  fun unknownSourcesIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context))

  private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

  private fun getText(url: String): String {
    val connection = open(url)
    return try {
      connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      connection.disconnect()
    }
  }

  private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      // GitHub API 要求 User-Agent；不带会被 403。
      setRequestProperty("User-Agent", "tappet-updater")
      setRequestProperty("Accept", "application/vnd.github+json")
      instanceFollowRedirects = true
    }

  private fun sha256(file: File): String {
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

  private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }
}

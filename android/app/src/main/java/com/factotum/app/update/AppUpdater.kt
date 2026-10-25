package com.factotum.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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
 *
 * 这里只管「哪个版本、装什么」；**取字节（含断点续传）在 [UpdateDownloader]**，
 * 流程编排在 [UpdateManager]。三者的失败面不一样，混在一个 object 里时
 * detekt 的「一个 object 最多 20 个函数」会先炸 —— 那条规则在这里是对的。
 */
object AppUpdater {
  private const val API_LATEST = "https://api.github.com/repos/asdukw/factotum/releases/latest"
  private const val APK_PREFIX = "factotum-"
  private const val APK_SUFFIX = "-arm64-v8a.apk"

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
      val body = UpdateHttp.getText(API_LATEST)
      val release = parseRelease(Json.parseToJsonElement(body).jsonObject) ?: return UpdateCheck.UpToDate(currentVersion)
      if (isNewerVersion(currentVersion, release.version)) {
        UpdateCheck.Available(currentVersion, release)
      } else {
        UpdateCheck.UpToDate(currentVersion)
      }
    } catch (error: Exception) {
      // 翻译成可行动的一句话（连不上 = 开 VPN；404 = 重试没用）。**不报原始异常消息**：
      // 消息里可能带 URL —— 虽然 URL 里没有凭据，也没必要进界面与日志。
      UpdateCheck.Failed(UpdateHttp.failReason(error))
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

  /**
   * 去系统里打开「安装未知应用」。
   *
   * `FLAG_ACTIVITY_NEW_TASK` 不能省：调用方（[UpdateManager]）拿的是 **application context**
   * —— 它没有 Activity 栈，不带这个 flag 会直接 `ActivityNotFoundException`，
   * 用户看到的是「更新没完成：NO_SETTINGS」（模拟器实测踩过一次）。
   */
  fun unknownSourcesIntent(context: Context): Intent =
    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")
}

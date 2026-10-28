package com.zoneu.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新逻辑里**可以离开设备验证**的部分：版本比较与 release 解析。
 *
 * 下载 / 校验 / 安装那几步要 Context 与真实 APK，属于设备测试；这里钉的是两条最容易静默出错的：
 * 版本号按**数值**比（`0.10.0 > 0.9.0`），以及 `assets` 里 apk 与 `.sha256` 的配对 ——
 * 配不上时必须返回 null（宁可说「已是最新」，也不下载一个没有哈希的包）。
 */
class AppUpdaterTest {
  @Test
  fun `version comparison is numeric not lexicographic`() {
    assertTrue(AppUpdater.isNewerVersion("0.9.0", "0.10.0"))
    assertTrue(AppUpdater.isNewerVersion("0.6.0", "0.7.0"))
    assertFalse(AppUpdater.isNewerVersion("0.7.0", "0.6.0"))
    assertFalse(AppUpdater.isNewerVersion("0.7.0", "0.7.0"))
  }

  @Test
  fun `version comparison tolerates v prefix and short forms`() {
    assertTrue(AppUpdater.isNewerVersion("v0.6.0", "v0.6.1"))
    assertFalse(AppUpdater.isNewerVersion("1.0", "1.0.0"))
    assertTrue(AppUpdater.isNewerVersion("1.0", "1.0.1"))
  }

  @Test
  fun `release parsing pairs the apk with its sha256`() {
    val release = parse(SAMPLE)
    assertEquals("v0.7.0", release?.tag)
    assertEquals("0.7.0", release?.version)
    assertEquals("https://example.test/zoneu-v0.7.0-arm64-v8a.apk", release?.apkUrl)
    assertEquals("https://example.test/zoneu-v0.7.0-arm64-v8a.apk.sha256", release?.sha256Url)
  }

  @Test
  fun `release without a sha256 asset is rejected`() {
    // 只有 apk、没有 .sha256：不能下 —— 哈希是这条路唯一的内容校验。
    assertNull(parse(SAMPLE_NO_SHA))
  }

  @Test
  fun `release without an apk asset is rejected`() {
    assertNull(parse(SAMPLE_NO_APK))
  }

  @Test
  fun `legacy app artifacts are never offered as zoneu updates`() {
    assertNull(parse(SAMPLE.replace("zoneu-", "factotum-")))
    assertNull(parse(SAMPLE.replace("zoneu-", "tappet-")))
  }

  private fun parse(json: String) = AppUpdater.parseRelease(Json.parseToJsonElement(json).jsonObject)

  private companion object {
    private const val APK_URL = "https://example.test/zoneu-v0.7.0-arm64-v8a.apk"

    val SAMPLE =
      """
      {
        "tag_name": "v0.7.0",
        "html_url": "https://example.test/releases/v0.7.0",
        "assets": [
          {"name": "zoneu-v0.7.0-arm64-v8a.apk", "browser_download_url": "$APK_URL"},
          {"name": "zoneu-v0.7.0-arm64-v8a.apk.sha256", "browser_download_url": "$APK_URL.sha256"}
        ]
      }
      """

    val SAMPLE_NO_SHA =
      """
      {
        "tag_name": "v0.7.0",
        "html_url": "https://example.test/releases/v0.7.0",
        "assets": [
          {"name": "zoneu-v0.7.0-arm64-v8a.apk", "browser_download_url": "$APK_URL"}
        ]
      }
      """

    val SAMPLE_NO_APK =
      """
      {
        "tag_name": "v0.7.0",
        "html_url": "https://example.test/releases/v0.7.0",
        "assets": [
          {"name": "zoneu-v0.7.0-arm64-v8a.apk.sha256", "browser_download_url": "$APK_URL.sha256"}
        ]
      }
      """
  }
}

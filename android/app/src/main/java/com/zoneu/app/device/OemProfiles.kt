package com.zoneu.app.device

import android.os.Build
import com.zoneu.app.BuildConfig

/** Android 侧无障碍服务的组件名：adb 启用与系统设置都是按它走的。 */
const val ZONEU_ACCESSIBILITY_COMPONENT =
  BuildConfig.APPLICATION_ID + "/com.zoneu.app.accessibility.ZoneuAccessibilityService"

/**
 * 一个 ROM / OEM 的适配档。
 *
 * 通用能力走 `DeviceBridge`；这里只放**各 ROM 不同的部分**：怎么启用服务、
 * 有哪些已知限制。新 ROM 只加一个 profile，不改探针 / 执行层 / UI 逻辑。
 *
 * ColorOS 是第一个实测样本，不是特例：OnePlus / OPPO / Realme 共用 oplus 体系，
 * 后续按实测再补 HyperOS / MIUI / HarmonyOS 等。
 */
data class OemProfile(
  val id: String,
  val displayName: String,
  /** 命中该 profile 的 Build.MANUFACTURER / BRAND 关键词（小写）。 */
  val buildAliases: List<String>,
  /** 系统设置里手动启用的路径（各 ROM 文案不同）。 */
  val manualEnablePath: String,
  /** 该 ROM 上已知的执行层限制。 */
  val constraints: List<String>,
) {
  /** adb 启用 / 复位命令；组件名是统一的，与 ROM 无关。 */
  fun adbEnableCommands(): List<String> =
    listOf(
      "settings put secure enabled_accessibility_services $ZONEU_ACCESSIBILITY_COMPONENT",
      "settings put secure accessibility_enabled 1",
    )

  fun adbDisableCommands(): List<String> =
    listOf(
      "settings put secure enabled_accessibility_services \"\"",
      "settings put secure accessibility_enabled 0",
    )
}

object OemProfiles {
  val COLOR_OS =
    OemProfile(
      id = "coloros",
      displayName = "ColorOS / oplus（ColorOS 是首个实测样本）",
      buildAliases = listOf("oneplus", "oppo", "oplus", "realme", "coloros"),
      manualEnablePath = "设置 → 无障碍 → 已下载的服务 → zoneu 无障碍服务（侧载应用可能需先「允许受限设置」）",
      constraints =
        listOf(
          "后台进程会被冻结：设备操作必须配前台服务",
          "侧载应用处于受限设置：UI 启用前需「允许受限设置」；adb 写入实测可用",
          "pm grant / appops set 被加固拦截，不能依赖",
        ),
    )

  val GENERIC =
    OemProfile(
      id = "generic",
      displayName = "通用 Android",
      buildAliases = emptyList(),
      manualEnablePath = "设置 → 无障碍 → 已下载的服务 → zoneu 无障碍服务",
      constraints = listOf("后台限制未知：首次接入该 ROM 时按探针流程实测"),
    )

  private val KNOWN =
    listOf(
      COLOR_OS,
      // 后续 ROM 在这里登记：HyperOS / MIUI / EMUI / HarmonyOS / 原生 Android ...
    )

  /**
   * 按 Build 特征选 profile；传入参数是为了让这段映射能在 JVM 单测里直接跑。
   */
  fun detect(
    manufacturer: String = Build.MANUFACTURER,
    brand: String = Build.BRAND,
  ): OemProfile {
    val key = "$manufacturer $brand".lowercase()
    return KNOWN.firstOrNull { profile -> profile.buildAliases.any { it in key } } ?: GENERIC
  }

  fun current(): OemProfile = detect()
}

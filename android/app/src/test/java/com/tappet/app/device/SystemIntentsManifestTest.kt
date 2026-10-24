package com.tappet.app.device

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `set_alarm` 依赖清单里的 `com.android.alarm.permission.SET_ALARM`。
 *
 * AOSP / Pixel 的 DeskClock 把处理 `ACTION_SET_ALARM` 的 `HandleSetApiCalls` 声明成
 * `android:permission="com.android.alarm.permission.SET_ALARM"`，不声明这条权限，
 * `startActivity` 直接抛 SecurityException（模拟器实测，见 [SystemIntents.setAlarm] 的注释）。
 * 它是 `protectionLevel=normal`，装包即授、不弹框 —— 所以「不声明」在厂商时钟上
 * 只是**碰巧**能跑，换台设备就静默失效。
 *
 * 这类「两处必须一致、错了不报错」的约束在本仓库一律用单测钉住（同 NodeRuntimeProfileTest）。
 * 找不到清单就跳过而不是通过：跳过在报告里看得见，不会伪装成绿。
 */
class SystemIntentsManifestTest {
  private val manifest = File("src/main/AndroidManifest.xml")

  @Test
  fun `manifest declares the alarm permission DeskClock requires`() {
    assumeTrue("仓库里没有 AndroidManifest.xml（不是完整检出），跳过", manifest.isFile)
    val text = manifest.readText()

    assertTrue(
      "清单里没有 com.android.alarm.permission.SET_ALARM —— AOSP 时钟会以 SecurityException 拒绝 set_alarm",
      text.contains("android:name=\"com.android.alarm.permission.SET_ALARM\""),
    )
  }
}

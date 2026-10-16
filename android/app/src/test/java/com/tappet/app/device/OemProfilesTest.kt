package com.tappet.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemProfilesTest {
  @Test
  fun `oneplus maps to coloros profile`() {
    assertEquals("coloros", OemProfiles.detect("OnePlus", "OnePlus").id)
  }

  @Test
  fun `oppo and realme map to coloros profile`() {
    assertEquals("coloros", OemProfiles.detect("OPPO", "OPPO").id)
    assertEquals("coloros", OemProfiles.detect("realme", "realme").id)
  }

  @Test
  fun `unknown vendor falls back to generic`() {
    assertEquals("generic", OemProfiles.detect("Google", "google").id)
    assertEquals("generic", OemProfiles.detect("Xiaomi", "Xiaomi").id)
  }

  @Test
  fun `coloros adb commands enable the tappet component and disable cleanly`() {
    val enable = OemProfiles.COLOR_OS.adbEnableCommands()
    val disable = OemProfiles.COLOR_OS.adbDisableCommands()
    assertTrue(enable.any { it.contains(TAPPET_ACCESSIBILITY_COMPONENT) })
    assertTrue(enable.any { it.contains("accessibility_enabled 1") })
    assertTrue(disable.any { it.contains("enabled_accessibility_services \"\"") })
    assertTrue(disable.any { it.contains("accessibility_enabled 0") })
  }
}

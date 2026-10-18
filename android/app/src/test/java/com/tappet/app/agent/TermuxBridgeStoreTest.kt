package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux 桥配置的判定，纯 JVM 测试（Store 本体要 Context + Keystore，起不来）。
 *
 * 这里钉的是**静默失败**：写错了不报错，只表现为「设置页说我配过，但 agent 用不了」。
 */
class TermuxBridgeStoreTest {
  @Test
  fun `blank url falls back to default`() {
    assertEquals(TermuxBridgeStore.DEFAULT_URL, normalizeUrl(null))
    assertEquals(TermuxBridgeStore.DEFAULT_URL, normalizeUrl(""))
    assertEquals(TermuxBridgeStore.DEFAULT_URL, normalizeUrl("   "))
  }

  @Test
  fun `saved url wins and is trimmed`() {
    assertEquals("http://127.0.0.1:9000", normalizeUrl("  http://127.0.0.1:9000  "))
  }

  @Test
  fun `enabled requires both url and token`() {
    val base = TermuxBridgeStore.DEFAULT_URL
    assertTrue(TermuxBridgeStore.State(url = base, hasToken = true).enabled)
    assertFalse(TermuxBridgeStore.State(url = base, hasToken = false).enabled)
    assertFalse(TermuxBridgeStore.State(url = "", hasToken = true).enabled)
  }

  /**
   * 启动脚本是送给 Termux 的**唯一一段代码**，写错了只会在设备上以「桥没起来」的形式现形。
   * 硬要求（每条都是实测踩出来的）：
   * - token 走 argv，不拼进脚本正文
   * - **自己 export PATH**：RUN_COMMAND 是非交互 shell，环境里连 `PREFIX` 都是空的
   * - 用**绝对路径**调 python、显式 export HOME
   * - **输出重定向到 bridge.log**：不然任何 traceback 都看不见
   * - **不许出现 `pkill -f`**：脚本被 `bash -lc <脚本文本>` 拉起，脚本文本就在 argv 里，
   *   正文里写什么模式都会匹配到脚本自己 —— 实测被这个坑掉过三轮排查
   */
  @Test
  fun `start script passes token via argv and survives its own command line`() {
    val script = TermuxBridgeLauncher.startScript()
    assertTrue(script.contains("\"\$1\""))
    assertTrue(script.contains(".tappet-bridge.pid"))
    assertTrue("必须自己摆正 PATH", script.contains("export PATH=/data/data/com.termux/files/usr/bin"))
    assertTrue("必须显式 export HOME", script.contains("export HOME=/data/data/com.termux/files/home"))
    assertTrue("必须用绝对路径调 python", script.contains("/data/data/com.termux/files/usr/bin/python"))
    assertTrue("必须把输出重定向到日志", script.contains(">> \"\$HOME/bridge.log\" 2>&1"))
    assertFalse("pkill -f 会匹配到脚本自己的命令行、把脚本杀掉", script.contains("pkill"))
  }

  @Test
  fun `stale bridge is killed by scanning proc, skipping self and parent`() {
    val script = TermuxBridgeLauncher.startScript()
    assertTrue("要扫 /proc", script.contains("for proc in /proc/[0-9]*"))
    assertTrue("要跳过自己", script.contains("/proc/\$\$"))
    assertTrue("要跳过父进程", script.contains("/proc/\$PPID"))
    assertTrue("要按命令行精确匹配", script.contains("cmdline"))
  }

  @Test
  fun `start arguments put the script before the token`() {
    val args = TermuxBridgeLauncher.startArguments("deadbeef")
    assertEquals(4, args.size)
    assertEquals("-lc", args[0])
    assertEquals("tappet-bridge", args[2])
    assertEquals("deadbeef", args[3])
  }
}

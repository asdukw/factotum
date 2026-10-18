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
   * 硬要求：token 走 argv（不拼进脚本正文）、先按 pidfile 杀旧桥、兜底的 pkill 必须用
   * **绝对路径**（脚本正文里是 `python "$HOME/bridge.py"`，用裸模式会匹配到脚本自己的
   * 命令行，把自己杀掉）、最后 exec 让 python 接管进程。
   */
  @Test
  fun `start script passes token via argv and kills by pidfile`() {
    val script = TermuxBridgeLauncher.startScript()
    assertTrue(script.contains("\"\$1\""))
    assertTrue(script.contains(".tappet-bridge.pid"))
    assertTrue(script.contains("exec python"))
  }

  @Test
  fun `pkill fallback uses an absolute path so it cannot match the script itself`() {
    val script = TermuxBridgeLauncher.startScript()
    val pkillLine = script.lineSequence().first { it.contains("pkill") }
    assertTrue("兜底的 pkill 必须带绝对路径：$pkillLine", pkillLine.contains("/data/data/com.termux/files/home/bridge.py"))
    assertFalse("裸的 bridge.py 模式会匹配到脚本自己的命令行", pkillLine.contains("\"bridge.py\""))
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

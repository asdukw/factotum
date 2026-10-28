package com.zoneu.app.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * proot 命令行的纯 JVM 单测。
 *
 * 参数错一个，真机上的表现是「guest 起来了但什么都没发生」或者 `bash: not found`，
 * 而且没有任何一条日志会指向参数本身。所以这里把「必须有的选项」和「guest 命令必须是
 * argv 的结尾」钉住。
 */
class ProotCommandTest {
  @get:Rule val temp = TemporaryFolder()

  private fun paths(): ProotCommand.Paths {
    val base = temp.newFolder("proot")
    return ProotCommand.Paths(
      launcher = File(temp.root, "libproot.so"),
      loader = File(temp.root, "libprootloader.so"),
      rootfs = File(base, "rootfs"),
      tmp = File(base, "tmp"),
      shm = File(base, "shm"),
      resolvConf = File(base, "resolv.conf"),
      caCerts = File(base, "ca-certificates.crt"),
    )
  }

  @Test
  fun `启动器与必需选项在前，guest 命令在最后`() {
    val paths = paths()
    val argv = ProotCommand.build(paths, listOf("/bin/sh", "-lc", "echo hi"))

    assertEquals(paths.launcher.absolutePath, argv.first())
    assertTrue(argv.contains("-0"))
    assertTrue(argv.contains("--kill-on-exit"))
    assertTrue(argv.windowed(2).contains(listOf("-r", paths.rootfs.absolutePath)))
    assertTrue(argv.windowed(2).contains(listOf("-w", "/root")))
    assertTrue(argv.contains("--link2symlink"))
    assertEquals(listOf("/bin/sh", "-lc", "echo hi"), argv.takeLast(3))
  }

  @Test
  fun `共享内存与 l2s 绑到宿主绝对路径`() {
    val paths = paths()

    val argv = ProotCommand.build(paths, listOf("/bin/sh"))

    // .l2s 必须是 host:guest 同一个绝对路径 —— dpkg 在 guest 里回读这个目录。
    val l2sBind = "${paths.l2s.absolutePath}:${paths.l2s.absolutePath}"
    assertTrue(argv.windowed(2).contains(listOf("-b", l2sBind)))
    assertTrue(argv.windowed(2).contains(listOf("-b", "${paths.shm.absolutePath}:/dev/shm")))
    assertTrue(paths.l2s.isDirectory)
  }

  @Test
  fun `不存在的主机目录不加 bind`() {
    val paths = paths()
    // resolv.conf 还没生成时不该出现这条 bind，否则 proot 会对着空路径报错退出。
    val argv = ProotCommand.build(paths, listOf("/bin/sh"))

    assertTrue(argv.none { it.endsWith(":/etc/resolv.conf") })
  }
}

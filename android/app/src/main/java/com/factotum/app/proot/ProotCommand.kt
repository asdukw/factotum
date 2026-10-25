package com.factotum.app.proot

import java.io.File

/**
 * 一次 proot 调用的完整命令行 —— **纯函数**，不碰 Android，单测直接钉住。
 *
 * 为什么值得单独一层：proot 的参数只要错一个，现象是「guest 起来了但什么都没发生」或
 * 「bash: not found」，真机上一轮一轮试代价太高。这里把「哪些参数、按什么顺序、
 * 哪些 bind 存在才加」变成可断言的列表。
 *
 * 参数选择的依据（照 Termux 的 proot-distro 与 DSHA 的实测配置，别凭印象改）：
 * - `-0` 让 guest 看到 root 身份。**这不是提权**：内核仍按应用真实 UID 与 SELinux 执行。
 * - `-r` + `-w /root`：rootfs 与工作目录。
 * - `--kill-on-exit`：宿主进程消失时把 guest 一起带走。少了它，App 被杀手后 rootfs 里
 *   会留一堆孤儿 `bash`/`node`，而停止按钮只杀得到 launcher。
 * - `--link2symlink`：app 私有目录禁 `link(2)`（SELinux），dpkg 建硬链接会直接失败。
 *   `PROOT_L2S_DIR` 指向 rootfs 内的 `.l2s`，并且**必须绑到同一个宿主绝对路径**
 *   （proot 在 guest 里回读这个目录，路径对不上就报文件不存在）。
 * - `/dev`、`/proc`、`/sys`、`/system`、`/apex`：glibc 程序与 Android 的 linker/APEX 库要用。
 *   源不存在时**跳过**，不是硬写 —— 不同机型/ROM 的 `/apex` 挂载点不保证一致。
 */
object ProotCommand {
  /** [ProotRuntime] 准备阶段算出来的固定路径。 */
  data class Paths(
    val launcher: File,
    val loader: File,
    val rootfs: File,
    val tmp: File,
    val shm: File,
    val resolvConf: File,
    val caCerts: File,
  ) {
    val l2s: File get() = File(rootfs, ".l2s")
  }

  /** 固定顺序的 proot 选项；[guestCommand] 追加在最后。 */
  fun build(paths: Paths, guestCommand: List<String>): List<String> {
    val argv = mutableListOf(paths.launcher.absolutePath, "-0", "--kill-on-exit")
    argv += listOf("-r", paths.rootfs.absolutePath, "-w", "/root")
    for (bind in HOST_BINDS) {
      val source = File(bind.first)
      if (source.exists()) argv += listOf("-b", "${source.absolutePath}:${bind.second}")
    }
    paths.shm.mkdirs()
    argv += listOf("-b", "${paths.shm.absolutePath}:/dev/shm")
    // DNS：Android 没有 /etc/resolv.conf，guest 里的 apt 却只认它（内容由 ProotRuntime 生成）。
    if (paths.resolvConf.isFile) argv += listOf("-b", "${paths.resolvConf.absolutePath}:/etc/resolv.conf")
    // bookworm-slim 里没有 ca-certificates：guest 自己的 HTTPS 会报「error setting certificate file」。
    // 直接把 Android 的 CA 库拼成 bundle 绑进去，省掉首启联网装包。
    if (paths.caCerts.isFile) {
      argv += listOf("-b", "${paths.caCerts.absolutePath}:/etc/ssl/certs/ca-certificates.crt")
    }
    paths.l2s.mkdirs()
    argv += listOf("-b", "${paths.l2s.absolutePath}:${paths.l2s.absolutePath}")
    argv += "--link2symlink"
    argv += guestCommand
    return argv
  }

  private val HOST_BINDS =
    listOf(
      "/dev" to "/dev",
      "/proc" to "/proc",
      "/sys" to "/sys",
      "/system" to "/system",
      "/apex" to "/apex",
    )
}

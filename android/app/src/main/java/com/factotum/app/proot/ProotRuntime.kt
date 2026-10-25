package com.factotum.app.proot

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 内嵌的 Debian（`proot` + rootfs），**不依赖 Termux**。
 *
 * 命令从 `DeviceBridgeServer` 的 `run_command` 动作进来，走**本进程自己的 SELinux 域**，
 * 所以可执行的入口只能来自 `nativeLibraryDir`：
 *
 * - `libproot.so` / `libprootloader.so`：由 `tools/fetch-proot.ps1` 放进 jniLibs，
 *   AGP 的 `useLegacyPackaging` 会把它们解压到 `nativeLibraryDir`（见 `app/build.gradle.kts`）。
 * - `libtalloc.so.2` / `libandroid-shmem.so`：proot 的运行时依赖，**文件名必须精确**，
 *   但 jniLibs 只收 `*.so` —— 所以在 [prepare] 里复制成正确名字，靠 `LD_LIBRARY_PATH` 找到。
 * - rootfs 里的 `/bin/sh` 从 App 私有目录执行。**实测可行**（2026-10-25，PLZ110 / Android 16）：
 *   guest 的 dash / dpkg / curl 都从 `filesDir` 起得来 —— 旧文档里「W^X 不许执行可写目录」
 *   的说法只对启动器那一层有意义，别套到 guest 上（见 `docs/linux-on-phone.md`）。
 *
 * rootfs 是 Docker 的 `debian:bookworm-slim`（arm64）单层，`tools/fetch-proot.ps1` 拉到
 * `assets/proot-rootfs.bin`（gzip 的 tar，扩展名故意不是 `.gz`）。首次使用时解到 `files/proot/rootfs`，版本号变了就重解。
 */
object ProotRuntime {
  /**
   * rootfs 的身份。**必须与 `assets/proot-runtime.json` 里的 `rootfs` 一致** ——
   * 换了 rootfs 而这里没改，设备上会继续用旧解压结果（或者反过来，每次启动都重解一次）。
   */
  const val ROOTFS_VERSION = "sha256:41b2195c355367079ebad200f9591fe3c2cb886c3b1e67abaaf2090dc11c04d9"

  private const val TAG = "FactotumProot"
  private const val ROOTFS_ASSET = "proot-rootfs.bin"
  private const val MAX_OUTPUT_BYTES = 256 * 1024

  data class Result(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
  }

  private val lock = Any()

  @Volatile private var prepared = false

  /** 首次调用会解 rootfs（约 27MB，几秒到十几秒）；之后的调用只读一个版本标记文件。 */
  fun prepare(context: Context) {
    if (prepared) return
    synchronized(lock) {
      if (prepared) return
      prepareLocked(context.applicationContext)
      prepared = true
    }
  }

  /**
   * 在 Debian 里跑一条命令，返回退出码与输出。
   *
   * 调用方负责放到 `Dispatchers.IO`；这里是阻塞的。超时先 `destroy()`，`--kill-on-exit`
   * 会让 proot 把 guest 一起带走 —— 没有它就只杀得到 launcher。
   */
  fun run(context: Context, command: String, timeoutMs: Long = 60_000): Result {
    prepare(context)
    val paths = paths(context)
    val argv = ProotCommand.build(paths, listOf("/bin/sh", "-lc", command))
    val process = ProcessBuilder(argv).apply { environment().putAll(environment(context, paths)) }.start()
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    // 两条流必须同时读：只读一条、另一条写满管道缓冲，guest 会卡死在 write 上。
    val reader = Thread { pump(process.inputStream, stdout) }.apply {
      isDaemon = true
      start()
    }
    val errReader = Thread { pump(process.errorStream, stderr) }.apply {
      isDaemon = true
      start()
    }
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) process.destroy()
    reader.join(2_000)
    errReader.join(2_000)
    val exit = if (finished) process.exitValue() else -1
    Log.i(TAG, "run done exit=$exit timedOut=${!finished} out=${stdout.size()} err=${stderr.size()}")
    return Result(exit, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()), !finished)
  }

  internal fun paths(context: Context): ProotCommand.Paths {
    val base = File(context.filesDir, "proot")
    val native = File(context.applicationInfo.nativeLibraryDir)
    return ProotCommand.Paths(
      launcher = File(native, "libproot.so"),
      loader = File(native, "libprootloader.so"),
      rootfs = File(base, "rootfs"),
      tmp = File(base, "tmp"),
      shm = File(base, "shm"),
      resolvConf = File(base, "resolv.conf"),
      caCerts = File(base, "ca-certificates.crt"),
    )
  }

  internal fun environment(context: Context, paths: ProotCommand.Paths): Map<String, String> {
    val lib = File(context.filesDir, "proot/lib").absolutePath
    paths.tmp.mkdirs()
    return mapOf(
      // proot 默认去 Termux 的 $PREFIX 找 loader；不设这个变量就是「loader 找不到」。
      "PROOT_LOADER" to paths.loader.absolutePath,
      "PROOT_TMP_DIR" to paths.tmp.absolutePath,
      "PROOT_L2S_DIR" to paths.l2s.absolutePath,
      // libtalloc.so.2 / libandroid-shmem.so 由 prepare() 放在这里；nativeLibraryDir 也带上，
      // 与 DSHA 的布置一致（proot 自己的依赖有可能落在那一侧）。
      "LD_LIBRARY_PATH" to lib + ":" + context.applicationInfo.nativeLibraryDir,
      "HOME" to "/root",
      "TERM" to "xterm-256color",
      "LANG" to "C.UTF-8",
      "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )
  }

  /**
   * 解压 + 依赖布局 + DNS，全部幂等。
   *
   * 版本标记用**独立文件**而不是目录的 mtime：解压到一半被杀，mtime 会看起来「已完成」，
   * 而标记文件是最后一步写的。
   */
  private fun prepareLocked(context: Context) {
    val paths = paths(context)
    val base = paths.rootfs.parentFile ?: error("rootfs 路径异常")
    File(base, "lib").mkdirs()
    paths.tmp.mkdirs()
    copyNative(context, "libtalloc.so", File(base, "lib/libtalloc.so.2"))
    copyNative(context, "libandroidshmem.so", File(base, "lib/libandroid-shmem.so"))
    writeResolvConf(paths.resolvConf)
    writeCaCerts(paths.caCerts)
    val marker = File(base, "rootfs.version")
    val installed = marker.takeIf { it.isFile }?.readText()?.trim()
    if (installed == ROOTFS_VERSION && File(paths.rootfs, "usr/bin/sh").exists()) return
    Log.i(TAG, "extracting rootfs $ROOTFS_VERSION")
    paths.rootfs.deleteRecursively()
    val opened = context.assets.open(ROOTFS_ASSET)
    val files = ProotArchive.extract(opened, paths.rootfs, androidHooks)
    marker.writeText(ROOTFS_VERSION)
    Log.i(TAG, "rootfs ready files=$files")
  }

  /** jniLibs 只收 `*.so`；设备上要求的文件名（`libtalloc.so.2`）只能靠复制。 */
  private fun copyNative(context: Context, name: String, target: File) {
    val source = File(context.applicationInfo.nativeLibraryDir, name)
    if (!source.isFile) error("缺少 $name（nativeLibraryDir=${source.parent}）")
    if (target.isFile && target.length() == source.length()) return
    source.copyTo(target, overwrite = true)
  }

  /**
   * Android 没有 `/etc/resolv.conf`，guest 的 apt 却只认这个文件。
   *
   * 这一版写**公共 DNS**，不读系统的：读 [android.net.LinkProperties] 要
   * `ACCESS_NETWORK_STATE`，而 AGENTS.md §6 要求加权限先问。要跟随系统 / 私有 DNS
   * （企业网络、运营商 DNS 才解得开的域名）再加那条权限，换成 LinkProperties。
   */
  private fun writeResolvConf(target: File) {
    val dns = listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")
    target.writeText(dns.joinToString("") { "nameserver " + it + "\n" })
  }

  /**
   * Debian slim 镜像不带 `ca-certificates`，而 Android 自己有一整套（APEX 里那 145 个 PEM）。
   * 拼成一个 bundle 绑进 guest，HTTPS 就能用，不用首启联网装包。
   *
   * 两个路径都试：Android 14+ 在 `/apex/com.android.conscrypt/cacerts`，更早的只在 `/system` 下。
   * 都读不到就跳过这层（guest 里的 curl 会明确报 77，不是静默失败）。
   */
  private fun writeCaCerts(target: File) {
    val sources =
      listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
        .map(::File)
        .firstOrNull { dir -> dir.isDirectory && dir.listFiles()?.isNotEmpty() == true }
        ?: return
    val bundle = sources.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
    if (bundle.isEmpty()) return
    target.outputStream().buffered().use { out ->
      for (cert in bundle) out.write(cert.readBytes())
    }
    Log.i(TAG, "ca bundle certs=${bundle.size}")
  }

  private val androidHooks =
    object : ProotArchive.Hooks {
      override fun symlink(target: String, link: File) {
        link.parentFile?.mkdirs()
        try {
          Os.symlink(target, link.absolutePath)
        } catch (_: ErrnoException) {
          // 已存在就够了（同一个 rootfs 被解两次），其他错误也交给后半段「文件缺失」去暴露。
        }
      }

      override fun chmod(file: File, mode: Int) {
        Os.chmod(file.absolutePath, mode)
      }
    }

  private fun pump(stream: InputStream, sink: ByteArrayOutputStream) {
    val buffer = ByteArray(1 shl 14)
    try {
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) return
        // 截断而不是无限吃内存：命令输出不是给模型看的日志，超长部分本来也没用。
        if (sink.size() < MAX_OUTPUT_BYTES) sink.write(buffer, 0, minOf(read, MAX_OUTPUT_BYTES - sink.size()))
      }
    } catch (_: Exception) {
      // 进程被超时杀掉时管道会断；不是错误。
    }
  }
}

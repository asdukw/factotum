# 手机上的 Linux：内嵌 proot + Debian

**什么时候读**：改 `tools/fetch-proot.ps1`、`proot/`、`DeviceBridgeServer` 的 `run_command`、
插件 `run_command`，或要动 rootfs / 依赖布局之前。

agent 能在手机的**文件系统**上干活：APK 里带一份 Debian rootfs，`proot` 在 zoneu 自己的进程
域里把它跑起来，命令经设备桥的 `run_command` 动作进来。**不装 Termux、不要 root、不过无障碍** ——
它改的是文件，不是屏幕。

## 1. 装配

```powershell
.\tools\fetch-proot.ps1    # Termux 的 proot/loader/依赖 → jniLibs；debian:bookworm-slim 镜像层 → assets
.\tools\bundle-dsh.ps1     # 插件改动之后（插件源码随仓库提交）
```

| 产物 | 来源（摘要钉死在脚本里） |
|---|---|
| `jniLibs/arm64-v8a/libproot.so` | Termux `proot 5.1.107.101` 的 `usr/bin/proot`（改名） |
| `jniLibs/arm64-v8a/libprootloader.so` | 同一包的 `usr/libexec/proot/loader` |
| `jniLibs/arm64-v8a/libtalloc.so` / `libandroidshmem.so` | `libtalloc 2.5.0` / `libandroid-shmem 0.7` |
| `assets/proot-rootfs.bin` | Docker Hub `debian:bookworm-slim` linux/arm64 的单一层（26.8 MiB） |

产物全部在 `.gitignore` 内，不入库（与 libnode / sherpa 同一个规矩）。

## 2. 运行

`ProotRuntime` 首次使用时把 rootfs 解到 `files/proot/rootfs`，之后每条命令一次
`proot -0 --kill-on-exit -r <rootfs> … /bin/sh -lc <cmd>`。

- **proot 本体必须在 `nativeLibraryDir`**：jniLibs 由 `useLegacyPackaging` 解出来，
  入口从这里 exec（`app/build.gradle.kts` 的注释）。
- **guest 二进制从 `filesDir` 执行没有问题**。在 OnePlus 15T（PLZ110 / Android 16 /
  targetSdk 36）实测：dash / dpkg / curl 都从 rootfs 起得来。旧文档里「Android 10+ 的 W^X
  不许执行可写目录」的说法只对启动器那一层有意义，别套到 guest 上（见 `docs/pitfalls.md`）。
- **依赖文件名必须精确**：`libtalloc.so.2` / `libandroid-shmem.so` 而 jniLibs 只收 `*.so` →
  运行期复制成正确名字，用 `LD_LIBRARY_PATH` 找到。
- **资产名不能带 `.gz`**：aapt2 会把 `.gz` 资产解压并去掉后缀，运行时按原名打不开；用 `.bin`。
- **`--link2symlink` + `.l2s`**：app 私有目录禁 `link(2)`，dpkg 建硬链接会直接失败；
  `.l2s` 必须绑到同一个宿主绝对路径，`PROOT_L2S_DIR` 指向它。
- **DNS 是写死的公共解析器**（223.5.5.5 / 119.29.29.29 / 1.1.1.1）：跟随系统 DNS 要
  `ACCESS_NETWORK_STATE`，还没加。企业网络 / 运营商私有域名需要先加这条权限。
- **CA 用 Android 自己的**：bookworm-slim 不带 ca-certificates，准备阶段把
  `/apex/com.android.conscrypt/cacerts`（145 个 PEM）拼成 bundle 绑到 `/etc/ssl/certs/ca-certificates.crt`。
- **每条命令一个独立会话**：cwd / 环境变量 / 后台进程都不保留。常驻服务要自己
  `nohup … > log 2>&1 &`；v1 还没有常驻会话与端口转发。
- **没有 docker / systemd / iptables**（proot 不是真内核）。**AVF 那条路在这台机器上走不通**：
  OnePlus 15T 的 gunyah 只支持 protected VM，而 Android 16 的「终端」要 non-protected VM，
  启动即报 `Non-protected VMs are not supported on this device`。

## 3. 工具面与失败分类

- `run_command` 由插件注册，条件是宿主注入 `ZONEU_PROOT=1`（`NodeRuntime` 在 Node 启动时设）。
  命令走**同一个设备桥**的 `POST /action`（`action=run_command`），不另开端口、不另发 token。
- 输出由 App 侧排版（`$ cmd` / `exit=… | Nms` / stdout / `[stderr]`），每流最多 16 KiB，
  留头留尾。**退出码非 0 不是工具错误** —— 模型要靠它判断下一步。
- **两种失败要分开**：`SHELL_UNAVAILABLE`（rootfs / proot 没起来）重试无用，插件按工具错误抛；
  命令自己失败（非 0 退出、超时）返回文本，由模型处理。
- **每条命令过审批缝**（`tools/pre-execute` → `ask`）：审计 `reason` 只写「执行 shell 命令」，
  命令原文只进 `displayReason`（面板可见、不落盘）。默认逐条问；「设置 → 执行层 → 写操作审批
  = 全允许」后 answerer 直接回 `allowed-once`，不再弹面板。

## 4. 验证

```powershell
# 冒烟：解 rootfs + 打印 debian 版本 / 架构 / 身份（输出只在 --ez logPayload true 时打印）
adb shell am broadcast -n com.zoneu.app/.node.NodeDebugReceiver `
  -a com.zoneu.app.debug.NODE --es op PROOT --ez logPayload true
# 结果看 logcat：ZoneuProot / ZoneuNode
```

实测基线：解压 3262 个文件 0.7 s；单条命令 ≈100 ms；`apt-get update` 9.3 MB / 12 s；
`apt-get install curl` 成功；`curl https://deb.debian.org/` → 200；执行后无 proot/guest 孤儿进程。

**ColorOS 会推迟后台广播**：App 不在前台时 `am broadcast` 返回成功但 receiver 不跑 ——
测之前先 `monkey` 拉到前台。

## 5. 排障

| 现象 | 原因 / 处理 |
|---|---|
| `SHELL_UNAVAILABLE: 缺少 libproot.so` | `fetch-proot.ps1` 没跑，或 jniLibs 没被 `useLegacyPackaging` 解出来 |
| `proot` 起来后报 `loader was not found` | `PROOT_LOADER` 没指向 `nativeLibraryDir/libprootloader.so` |
| `bash: not found` / `Permission denied` | 解压时符号链接或模式位没保留（`ProotArchive` 的两个单测盯着） |
| guest 里 DNS 不解析 | 网络屏蔽了公共 DNS，或需要私有域名 → 加 `ACCESS_NETWORK_STATE` 读 LinkProperties |
| guest 里 HTTPS 报 `error setting certificate file` | CA bundle 没绑上（`writeCaCerts` 读不到 Android 的 cacerts） |
| `apt` 装包报链接错误 | `--link2symlink` / `PROOT_L2S_DIR` / `.l2s` 绑定不一致 |
| `run_command` 不在工具表里 | 宿主没注入 `ZONEU_PROOT=1`（旧插件/旧 App），或插件版本与桥不匹配 |

# 构建、装配与门禁

AGENTS.md 只留命令；本文是正常路径的完整版。按**现象**排障看 [pitfalls.md](pitfalls.md)，
发版与签名看 [releasing.md](releasing.md)，运行时产物为什么不入库看 [dsh-android.md](dsh-android.md) §5。

## 0. 仓库结构

```
android/                 唯一的运行时（Kotlin / Compose）
  app/src/main/java/com/zoneu/app/
    agent/ accessibility/ bridge/ connector/ data/ device/ node/ voice/ ui/
  app/src/main/cpp/       JNI（node::Start + stdout 转发）
  app/src/test/           JVM 单测
  app/src/androidTest/    真机测试
tools/                    fetch-libnode, fetch-sherpa, fetch-proot, bundle-dsh, check-sensitive,
                          install-debug, audit/（任务轨迹）, dsh-phone/（launcher、profile、patch、插件）
docs/                     深度文档 + releases/<tag>.md
```

`libnode.so` 和 dsh 运行时不入库，由 `tools/` 按 pin 的版本装配（见下文）。

## 1. 环境

| 项 | 值 |
|---|---|
| JDK | 21（`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`） |
| Node | 24 + npm，在 PC 上跑装配脚本 |
| Android SDK | compileSdk 37 |
| NDK | `ndk;29.0.14206865`（经 `sdkmanager`） |
| CMake | `cmake;3.31.6`（经 `sdkmanager`） |

## 2. 装配顺序

首次检出、以及 `tools/**` 或运行时 pin 变更后，按顺序跑：

```powershell
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so（从 NDK 取）
.\tools\fetch-sherpa.ps1                  # sherpa 原生库 + silero VAD 模型（只服务 ASR）
.\tools\bundle-dsh.ps1                    # vendored dsh -> assets/dsh-runtime.zip
.\tools\fetch-proot.ps1                   # 内嵌 proot + Debian rootfs
```

- `.ps1` 必须用 **`pwsh` 7** 跑，不能用 `powershell` 5.1：5.1 把 zip 条目名写成反斜杠，
  设备解不出目录树，App 以 `ERR_MODULE_NOT_FOUND` 起不来。
- 装配脚本只认 `HTTP_PROXY` / `HTTPS_PROXY`（本机默认 `127.0.0.1:7897`）；代理关掉时
  npm 会报 `ECONNREFUSED 127.0.0.1:7897`。
- `libnode.so` 与 dsh 运行时（`assets/dsh-runtime.zip`、`libnode/`、`jniLibs/`）**不入库**，
  由 `tools/` 按 pin 的版本生成（见 `.gitignore`）。

### 插件与 profile

插件在 `tools/dsh-phone/plugins/`，随本仓库一起提交（不再有 submodule）。
`bundle-dsh.ps1` 用真实 parser 校验 profile——Node 跑在进程内，一份坏 YAML 会让 App 直接起不来。
改完插件或 profile 后必须重新 `bundle-dsh.ps1` 并重装 APK（运行时在首次启动时解包），
再在真机跑一个真实任务。

## 3. 构建与测试

从 `android/` 执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain

# 只跑一个测试类
.\gradlew.bat :app:testDebugUnitTest --tests "com.zoneu.app.device.ScreenSnapshotRenderTest"
```

- 代理参数要整条加引号或放进 `GRADLE_OPTS`：PowerShell 会把不加引号的
  `-Dhttps.proxyHost=...` 拆成 `-Dhttps` 和 `.proxyHost=...`。
- 测试：`src/test`（JVM，类名以 `Test` 结尾）或 `src/androidTest`（真机，类名以
  `AndroidTest` 结尾），路径镜像包名；行为变更在同一次提交里带上测试。
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（约 160 MB）。
- 模拟器：`fetch-libnode.ps1 -Abi x86_64` + `assembleDebug "-Pzoneu.abis=x86_64"`（参数要加引号）。
  **跑完把 jniLibs 切回 arm64**，否则下一份真机包缺 `libc++_shared.so`。

## 4. 门禁

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain
.\gradlew.bat spotlessApply     # 修格式
```

| 工具 | 管什么 | 配置 | 失败怎么办 |
|---|---|---|---|
| ktlint（Spotless） | 格式 | `android/.editorconfig` | `spotlessApply` |
| detekt | 坏味道 / 复杂度 | `android/config/detekt/detekt.yml` | 带理由放宽规则；**不往 `baseline.xml` 里加** |
| Android Lint | API 级别 / manifest / 资源 | `android/app/lint.xml` | error 挡 CI，warning 不挡 |

- Spotless 从 **Gradle 项目目录**找 `.editorconfig`，不是仓库根。
- ktlint 的 `ktlint_*` 属性不经过 Spotless，要在 `editorConfigOverride` 里再写一遍。

## 5. CI

`.github/workflows/ci.yml` 永远先跑变更范围判定：代码变更跑门禁 + `:app:testDebugUnitTest`；
纯文档变更跳过这些步骤，但仍上报 required checks（skipped 计为通过）。`assemble.yml` 在
`tools/**`、`app/src/main/cpp/**` 或 Gradle 文件变更时重跑整条装配链（libnode → dsh → JNI → APK）。
`release.yml` 只在 `v*` tag 上、在 `release` environment（人工批准）里构建并签名。

发版前至少跑：门禁三条、`:app:testDebugUnitTest`、`check-sensitive.ps1 -SelfTest` 与
`-ApkPath <包>`。

# Tappet AGENTS.md

本文件是给 coding agent 的项目记忆：**先读完再动手**。以下均为实测结论与硬规则，
不要凭直觉推翻。深度信息在 `docs/`，本文件只放硬规则与可直接执行的命令。

## 项目概览

Tappet 是**运行在 Android 上的通用 mobile agent**：LLM 通过工具调用操作手机上的任意
App 完成任务，不做单一 App 的专用适配。仓库唯一运行时是 Android App（Kotlin）。

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| 数据 | Room + SQLCipher（会话 / 消息 / 工具调用），API key 由 Android Keystore 封装 |
| agent runtime | App 内嵌 Node 24（nodejs-mobile fork）+ dsh（DeepSeek Harness）phone profile |
| 设备执行层 | `AccessibilityService`（控件树优先、截图兜底）+ `DeviceBridgeServer`（127.0.0.1 + token） |
| 工具接入 | dsh 插件 `dsh-plugin-android-tools`（9 个通用原语），独立公开仓库，经 HTTP 桥 → `DeviceAction`；旧 `chat-tools` 已从仓库整体移除（不挂载/不打包） |

- 开发机：Windows + PowerShell 7；设备：一加 15T（PLZ110，Android 16 / API 36，ColorOS）。
- **方向红线（2026-10-03 定稿）**：不采集通知、不做 QQ / 微信聊天记录管线、不做知识图谱、
  无 Python 侧、无 PC / Electron 客户端。相关代码已删除，**不要重新引入**。
  手写的 `LlmClient` 工具协议 / `AgentLoop` / 对话集成已暂停/废弃，**不要继续完善**。
- 动手前读对应文档：
  - 动 agent runtime → `docs/dsh-android.md`（决策、P0–P2 实测、各阶段结论）
  - 动无障碍执行层 → `docs/android-accessibility.md`（系统探测、四问 runbook）
  - 现状与路线 → `README.md`；**技术路线畅想（未验证的假设）** → `docs/roadmap.md`

## 命名（2026-10-12）

项目原名 **Memex**。因定位是**通用 mobile agent**（而非记忆 / 知识库），已更名为 **Tappet**。
这次是**彻底改名，连设备身份一起换**：源码包与 `applicationId`、环境变量（`TAPPET_*`）、
Gradle 属性（`-Ptappet.abis`）、日志 tag（`TappetProbe` / `TappetNode`）、资源名、
Gradle 工程名、CMake 工程名、JNI 符号全部换新，**没有遗留**。

**换 `applicationId` 的后果（必须记住）**：SQLCipher 口令由 Android Keystore 封装
（硬件封装、不可导出、按 uid 隔离），换包等于换 uid —— 设备上旧包 `com.memex.app` 里的
会话 / 消息 / 工具调用，新包**读不到，也没有导出通道可救**。这次是主动接受丢历史；
以后遇到同类改动同样要想清楚。旧包不卸载就一直占着存储，`adb uninstall com.memex.app` 即可。

**存储身份共四处，任何一次改名都必须一起改**，漏一处库就读不出来：

| 位置 | 作用 |
|---|---|
| `app/build.gradle.kts` 的 `applicationId` | 设备上的应用身份（uid） |
| `TappetDatabaseProvider.DATABASE_NAME` | 库文件名 |
| `PassphraseVault` 的 `prefsName` | 封装口令的 SharedPreferences 文件 |
| `PassphraseVault` 的 `keyAlias` | Keystore 里的密钥别名 |

组件名统一是 `com.tappet.app/...`：`-n com.tappet.app/.node.NodeDebugReceiver`、
`run-as com.tappet.app`、`dumpsys package com.tappet.app`。
`OemProfiles.TAPPET_ACCESSIBILITY_COMPONENT` 读 `BuildConfig.APPLICATION_ID`，会自己跟随。

## Git 历史（2026-10-12 发布前重建，看这里不要以为仓库是新的）

公开发布前做过一次**历史重建**：对外历史的**根提交**是重建出来的，其树与重建前的
HEAD 逐字节相同（比过 tree hash），**它之前的提交都不存在了**。**别把「历史很短」
当成仓库刚建** —— 代码和文档都是有来由的，来由在被剥离的旧历史里。

为什么剥离：旧历史里不只有已删除的旧管线代码，还有**第三方的真实数据** ——
m3 试点报告里的真实群名/群号与某位群成员的真实昵称及其私人信息、schema 文档里的
真实 NT UID 与逐会话行数；**而且多条提交信息里也写着这些标识**（所以只删文件不够）。

- 完整旧历史（106 提交）在**仓库外**：`../memex-history-backup-2026-10-12.bundle`
  （`git clone <bundle> <dir>` 可取回）。本地回顾用它，**不随发布**。
- 因此：`docs/m3-pilot-report.md`、`src/life_graph/**`、`tools/m0_*.ps1` 这些都是
  **历史之外**的东西，`git log -- <path>` 查不到不等于从未存在。
- 以后若再往仓库里写任何来自真实数据的内容（调研、报告、fixture、schema 实测），
  **先脱敏再提交** —— 标识一进 git 就跟着每个 clone 走，事后只能重写历史。

## 仓库结构

```
android/                        唯一运行时（Kotlin / Compose）
  app/src/main/java/com/tappet/app/
    agent/                      会话状态 + DshStreamer（dsh 事件流→聊天）+ 已暂停的手写 LLM 客户端
    accessibility/              AccessibilityService（执行层实现）
    bridge/                     DeviceBridgeServer（本地 HTTP 桥）
    data/                       Room + SQLCipher 持久化、Keystore 口令
    device/                     通用设备契约：DeviceAction / DeviceBridge / OemProfiles
    node/                       NodeRuntime：解压 dsh 运行时、注入 env、JNI 启动
    ui/                         Compose 界面（聊天 / 首页 / 四问探针）
  app/src/main/cpp/             JNI（node::Start + stdout 转发到 logcat）
  app/src/test/                 JVM 单测
  app/src/androidTest/          真机（instrumented）测试
tools/                          PC 侧脚本与 dsh 适配层
  fetch-libnode.ps1             拉取 pinned libnode + libc++_shared
  bundle-dsh.ps1                装配 vendored dsh → assets/dsh-runtime.zip
  audit/                        任务审计回路（取证层；判断交给 tappet-auditor agent）
  dsh-phone/launcher.mjs        绕开 dsh boot()/CLI loader 的启动器
  dsh-phone/patch-runtime.mjs   Android 适配补丁（flock 放行、jsonl 发布改 rename）
  dsh-phone/plugins/            git submodule（独立公开仓库，源码唯一源头）
    android-tools/                https://github.com/asdukw/dsh-plugin-android-tools（9 个通用原语）
  dsh-phone/android-attachments/ 纯 JS 的附件存储（Android 没 sharp，替换 attachment-local）
  dsh-phone/profile/            phone profile（cordis.patch.yml）
docs/                           深度文档
  roadmap.md                   技术路线畅想（方向性设想，未验证）
```

`libnode.so`、dsh 运行时（`assets/dsh-runtime.zip`、`assets/nodejs/`、`libnode/`、
`jniLibs/`）都**不入库**，由 `tools/` 脚本按 pinned 版本装配（在 `.gitignore` 内）。

插件源码在独立公开仓库，由 git submodule 钉 commit 引用（当前只引入 `android-tools`，
旧的 `chat-tools` submodule 已移除）；克隆后先
`git submodule update --init --recursive`。插件改动先提交到插件仓库，再在 tappet
更新 submodule 指针；发版由插件仓库的 `v*` tag workflow 完成：GitHub Release 附
`npm pack` tarball（安装：`dsh plugin add ./<pkg>-<ver>.tgz` 或
`github:asdukw/<repo>#v<ver>`）；npm 发布是同一 workflow 里的 gated job，首个版本需
本地 `npm publish` 并在 npmjs.com 配 trusted publisher 后用 repo variable
`NPM_PUBLISH_READY=true` 启用（无需 secret）。

## 环境准备

- **JDK 21**（必须）：`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`
- Android SDK：compileSdk 36；`ndk;29.0.14206865` 与 `cmake;3.31.6`（`sdkmanager` 安装）
- PC Node 24 + npm（装配 dsh 运行时用；实测 `C:\nvm4w\nodejs\node.exe`）
- 代理约定：本地代理 `127.0.0.1:7897`（脚本中硬编码；换了要改脚本）。代理以
  `HTTP_PROXY`/`HTTPS_PROXY` 环境变量全局注入；代理未开时 npm 会
  `ECONNREFUSED 127.0.0.1:7897`，临时绕过：当前 shell 清空这两个变量后再执行。

```powershell
# 首次准备：libnode（含 NDK 里的 libc++_shared.so）与 vendored dsh
.\tools\fetch-libnode.ps1
.\tools\bundle-dsh.ps1

# API key 放在 android/local.properties（gitignored）。**只有 debug 变体**在构建期把它
# 注入 BuildConfig；release 变体固定空串（见 app/build.gradle.kts 的注释）：
#   tappet.agent.apiKey=...        （也可用环境变量 TAPPET_AGENT_API_KEY）
#   tappet.agent.baseUrl / model / reasoningEffort / proxyHost / proxyPort
```

## 构建

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain   # 在 android/ 下执行
```

- **代理必须放 `GRADLE_OPTS` 环境变量**——行内 `-D` 会被 `gradlew.bat` 拆坏。
- 依赖下载不通时，改阿里云镜像 `https://maven.aliyun.com/repository/public`（实测秒下）；
  GitHub 资源（libnode）走 7897 代理。
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（含内嵌 Node 与 dsh 运行时，约 160MB）。
- 模拟器（x86_64）调试：先取 x86_64 libnode，再用 Gradle 属性切 ABI（默认仍是真机 arm64）：

```powershell
.\tools\fetch-libnode.ps1 -Abi x86_64     # 产出 android/app/libnode/bin/x86_64 + jniLibs/x86_64
.\gradlew.bat :app:assembleDebug -Ptappet.abis=x86_64 --console=plain   # 在 android/ 下执行
```
- 改了 `tools/dsh-phone/` 下的 launcher / profile / 插件后，必须重跑 `bundle-dsh.ps1`
  并重装 APK 才会生效（运行时打包在 assets 里；APK 更新后首次启动自动重解压）。

## 测试

- 单测（JVM，快速，改 Kotlin 后必跑）：`.\gradlew.bat :app:testDebugUnitTest --console=plain`
  - 单个类：`.\gradlew.bat :app:testDebugUnitTest --tests "com.tappet.app.device.ScreenSnapshotRenderTest"`
- 真机测试（`androidTest/`，覆盖 Room/SQLCipher、Compose UI）：
  **先手动装包再单独跑测试任务**，不要用 Gradle 自动安装（ColorOS 会拦全新安装）：

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
# 按下方「真机」一节重新启用无障碍服务
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

- 本仓库无 CI；验证 = 本地构建 / 单测通过，或真机验证。
- 新增/修改行为要同步补对应层的测试（`src/test` 或 `src/androidTest`）。

### 任务审计回路（替代「人工跑一遍翻日志凭印象」）

取证层与判断层分开：`tools/audit/` 是确定性脚本，`.claude/agents/tappet-auditor.md`
是读产物出报告的只读子 agent。

```powershell
node tools/audit/selftest.mjs      # 离线自检（zstd 逐帧解码，不需要设备）
node tools/audit/run-suite.mjs     # 跑固定任务集，每个跑完自动采集
node tools/audit/collect.mjs --label <名字>   # 只采集最新会话
```

- 产物在 `tools/audit/runs/`（已 gitignore）：`events.jsonl`（每步一行）、
  `metrics.json`、以及累积的 `runs/index.json`。**含屏幕正文，按敏感数据对待**；
  审计报告里不得出现正文，只能写结构（长度 / 节点数 / 类名 / 坐标）。
- 任务集在 `tools/audit/suite.json`。**改任务文本 = 换任务，历史数据作废**，
  加场景请追加新 id；默认只放只读 / 可逆任务（审计要跑很多遍）。
- 包名与 dsh 会话目录名都**不硬编码**（现读 `build.gradle.kts` 的 applicationId）。
  上一轮改名时有一批手工脚本因为硬编码旧包名一起失效，而且是**静默**失效 ——
  报出来像「没有 session 目录」，看着像设备问题。要留存的东西放 `tools/` 或 `docs/`，别放 `tmp/`。
- 跨运行才能看出「工具设计问题」这类结论，所以 `index.json` 要留着累积，别删。

## 真机（一加 15T / ColorOS）

- **不要走 Gradle 的自动安装通道**：设备上原本没有此包时会被 ColorOS 拦，挂 ~5 分钟后
  报 `Starting 0 tests` + `Failed to install split APK(s)`。覆盖安装实测正常。
  正确流程：`assembleDebug` → `adb install -r`（~10 秒）。
- **`adb install -r` 会摘掉已启用的无障碍服务**，每次装完重新启用：

```powershell
adb shell settings put secure enabled_accessibility_services com.tappet.app/.accessibility.TappetAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

- `pm grant` / `appops set` 会被 ColorOS 加固拦截（uid 2000 无权限），别反复试；
  App 目前只需要 `INTERNET` 权限。
- ColorOS 首次启用/重装后弹「保持开启」安全弹窗（3 秒倒计时），不点可能被关闭。
- `uiautomator dump` 会顶掉无障碍服务约 1 秒，用 adb 自动化时避开。
- ColorOS 会冻结后台进程：设备操作必须配前台服务（正式化见 docs P3）。
- logcat 关键 tag：`TappetProbe`（执行层）、`TappetNode`（Node 宿主）、
  `TappetAudit`（**审计标记**，机器读的 key=value；`tools/audit` 按它过滤，
  改名要同步 `AUDIT_TAG`）、`NODEJS-MOBILE`（Node **stderr** 诊断；stdout 是 JSON-RPC 协议，不落日志）。

**debug-only 广播入口**（release 不含）——用于不经 UI 触发、可复现的真机验证：

```powershell
# 启动常驻运行时 + SDK initialize（只跑一次；之后可连续任务）
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver `
  -a com.tappet.app.debug.NODE --es op START

# 提交一个任务（可连续多次，不需要 force-stop）；--ez logPayload true 才打印助手正文
adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op TASK --es task '任务文本'"
# 需要干净会话时加 --es session <id>

# 无障碍「四问」探针
adb shell am broadcast -n com.tappet.app/.accessibility.ProbeCommandReceiver `
  -a com.tappet.app.debug.PROBE --es capability READ_TREE   # SCREENSHOT / SET_TEXT / TAP

# 直接驱动执行层原子动作（目标 App 在前台时用）
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver `
  -a com.tappet.app.debug.ACTION --es action READ_SCREEN
```

- 带空格的参数（如 `--es task '...'`）必须把整条 `am broadcast` 再用 `adb shell "..."`
  包一层引号，否则会被设备 shell 拆词。
- ColorOS 会 defer 后台广播、`install -r` 后又会重置无障碍开关：广播前先把 App
  拉到前台（`monkey`），装完等进程起来后再 `settings put secure` 启用无障碍。

## 架构与改动入口

- **加一个设备动作 = 四处同一原子语义**：`device/DeviceAction.kt`（定义）+
  `TappetAccessibilityService`（执行）+ `bridge/DeviceBridgeServer`（HTTP 映射）+
  `tools/dsh-phone/plugins/android-tools/index.mjs`（`ctx.tools` 注册，含给模型看的
  描述；独立公开仓库，先在插件仓库提交再更新 submodule 指针）。
  插件改完 → `bundle-dsh.ps1` → 重装 APK。
- 现有 9 个桥动作：`read_screen` / `screenshot` / `tap_node(ref)` / `tap_point(x,y)` /
  `long_press(ref|x,y,duration_ms)` / `type_text` / `press(back|home|recents|enter)` /
  `launch_app(query)` / `swipe(direction)`（wait 已下线：等待靠模型思考与重复 read_screen）。
  `press_back` 只保留为桥兼容别名；工具描述统一写「先读屏、操作后再确认、每次只做一个原子动作」。
- **任务期触摸探索（按任务开关）**：部分 App（实测微信 8.0.78）只在
  `isTouchExplorationEnabled()` 为真时暴露控件树，所以任务期间由 `DshStreamer` /
  `NodeDebugReceiver` 调 `TappetAccessibilityService.setTouchExplorationForTask(true)`，`finally` 关掉。
  开启期间 `dispatchGesture` 会被系统吞掉，点击/长按/上下滚动自动改走 `performAction`
  语义动作；关掉后手势恢复。细节与实测见 `docs/android-accessibility.md` §9。
- **通用原语边界**：Tappet phone profile 只挂 `dsh-plugin-android-tools`；`chat-tools` 已整体
  移除（不挂载、不打包、无 submodule）。聊天/购物等业务语义由模型组合基础动作。
- **契约与 ROM 解耦**：`ui/probe` 只依赖 `DeviceBridge`；换 ROM 只加一个 `OemProfile`。
  不写 App 专用逻辑——QQ 只是试验任务的目标 App，不是适配对象。
- 桥只监听 `127.0.0.1` + 每进程随机 token，只暴露 `POST /action`；不在设备上安装插件。
- **改 dsh 运行时不要用 `boot()`/CLI loader**（硬依赖无 Android 产物的
  `node-addon-require-builtin`）：走 `launcher.mjs`（纯组合函数取 rows + Cordis 直接
  mount）；Android 上 `patch-runtime.mjs` 放行 flock、把 jsonl 硬链接发布改为 rename。
- 内嵌 Node 是宿主 App 进程内单实例、常驻形态：launcher 挂
  `@deepseek-ai/dsh-sdk-jsonrpc-server`，Kotlin 经 stdin/stdout 当 SDK client
  （`initialize` / `session/prompt`，事件走 `session.event` / `session.status`）。
  **stdout 是协议独占**，phone profile 不能挂 stdout logger，诊断走 stderr。
  可连续任务，不需要 force-stop；但**不要发 `shutdown`、不要 `process.exit()`**
  （会 FORTIFY/SIGABRT 掉整个 App）。跨 App 任务需前台服务保活。

## 数据与隐私红线

1. **密钥永不入库、不进日志、不打印**：`android/local.properties`（gitignored）；
   dsh 的 key 经 env `TAPPET_LLM_KEY` 注入，日志与提交信息不得出现。
2. **屏幕与聊天正文不落日志**：日志只记计数 / 类名 / 坐标 / 错误码；读屏 payload 只在
   给模型的工具结果里。debug 广播的 `--ez logPayload true` 仅供调试，用完不要留在脚本里。
3. `tappet.db`（会话 / 消息 / 工具调用）用 SQLCipher 加密，口令由 Keystore 封装：
   **卸载即永久丢失，外部不可解、不可拷**。任何卸载 / 清数据 / 重装前先确认已导出。
4. SQLCipher 原生库不会自动加载：`TappetDatabaseProvider` 必须显式
   `System.loadLibrary("sqlcipher")`（删旧代码时丢过一次，表现为 `nativeOpen` 的
   UnsatisfiedLinkError、界面显示「本地存储不可用」）。
5. **不采集他人数据**：通知采集与聊天记录管线已整体删除；如需重新引入任何采集能力，
   先在 `docs/` 写清边界与隐私闸门，再动代码。

## 提交纪律（2026-10-03 用户约定）

- **完成一个可验证的里程碑时自行提交，不要攒大改动等用户开口。** 判据：构建 / 单测通过，
  或真机验证完成，即提交一个内聚的 commit。
- 提交信息沿用仓库风格：中文 conventional commit（`feat(android): ...`、`docs: ...`、
  `fix(repo): ...`），正文写清改了什么与验证方式；不含密钥与个人数据。
- 未验证的半成品不提交；提交后保持工作区干净、可解释。
- 本仓库**无远端，不 push**。

## 常见坑速查

| 现象 | 原因 / 处理 |
|---|---|
| 全新安装 Gradle 挂 5 分钟后失败 | ColorOS 拦 `adb install` 通道 → 手动 `adb install -r` |
| 装完读屏失效 | `install -r` 摘掉无障碍服务 → 重新 `settings put secure` 启用 |
| `nativeOpen` UnsatisfiedLinkError | 忘加载 SQLCipher → `TappetDatabaseProvider` 显式 `loadLibrary` |
| 任务结束 App 崩溃（FORTIFY/SIGABRT） | 内嵌 Node 调了 `process.exit()`（含 SDK 的 `shutdown`）→ 只记录、不退出 |
| 广播 TASK 后 App 被 ANR 杀 | 别用 `goAsync` 等任务完成（Broadcast Timeout 60s）→ 广播立即返回，任务异步跑 |
| 广播 START/TASK 没反应 | ColorOS defer 后台广播 → 先 `monkey` 拉起 App 再广播 |
| `install -r` 后启用无障碍又变 null | ColorOS 在包替换后重置 → 等 App 进程起来后再 `settings put secure` |
| JSON-RPC 偶发不响应 | stdout 混入了日志 → phone profile 不得挂 stdout logger；诊断一律 stderr |
| `adb shell am broadcast --es task "a b"` 丢参数 | 整条命令再包一层引号：`adb shell "am broadcast ... 'a b'"` |
| dsh 启动报 `node-addon-require-builtin` | 用了 `boot()`/loader → 改用 `launcher.mjs` |
| dsh 会话写文件报 flock / link 错误 | 未打补丁 → 跑 `patch-runtime.mjs`（flock 放行、jsonl 改 rename） |
| 内嵌 Node 编译失败 `unknown type name 'concept'` | Node 24 头文件要 C++20 → `CMAKE_CXX_STANDARD 20` |
| 读屏只返回 0–1 个节点（微信） | 该 App 隐藏控件树：微信 8.0.78 只在「触摸探索」为真时启动的进程里暴露节点 → 任务期间自动开触摸探索，目标 App 需在模式开启后启动进程；进程已在跑就只能请用户配合 |
| 触摸探索开启时点击/滑动全部无效 | 探索模式会把 `dispatchGesture` 注入的手势当探索事件吞掉 → 走 `performAction` 语义动作（服务里已自动切换） |
| 任务期间用户点屏幕变成「双击才生效」 | 触摸探索常开导致 → 只在任务期间开关（`TappetAccessibilityService.setTouchExplorationForTask`），`finally` 里必须关 |
| 截图报 `Could not load the "sharp" module` | 生效的是 dsh 自带的 `attachment-local`（依赖 sharp，无 Android 产物）→ profile 里禁用它、挂 `dsh-android-attachments`；两个都挂会看谁后注册 |
| 模型侧 `tools: []`、只能对着 persona 猜工具名 | 工具 schema 编译失败（如对象根漏写 `additionalProperties`）→ `defineTool` 抛错会**整个插件的工具全丢**；用 `npm test` 里的 `real-schema.mjs`（真实 defineTool）在 PC 侧拦住 |
| 拉 dsh 会话文件解压失败 | `adb shell cat` 会把二进制里的 `\n` 翻成 `\r\n` → 用 `adb exec-out run-as com.tappet.app cat ...`；会话是**一帧一条记录**的追加文件，Node 的 zstd 不跨帧，需按 RFC 8878 帧头手工切帧后逐帧解（会话在 `files/dsh-home/sessions/--data-user-0-.../`，条目录自带时间戳好定位） |
| `dlopen failed: libc++_shared.so not found` | 重跑 `tools/fetch-libnode.ps1`（会从 NDK sysroot 复制到 jniLibs） |
| dsh 报 `session ... already exists`（-32603） | 进程重启后复用磁盘上的会话 id；dsh SDK 只有 create 无 resume → `NodeRuntime` 加进程级 runtimeTag 后缀再映射回来，别去掉 |
| 模拟器上 App 起不来 / 缺 libnode | 只装配了 arm64 → `fetch-libnode.ps1 -Abi x86_64` + `-Ptappet.abis=x86_64` |
| Gradle 代理不生效 | 行内 `-D` 被 gradlew.bat 拆坏 → 放 `GRADLE_OPTS` 环境变量 |
| release 包跑任务立刻报缺 API key | 刻意的：release 不注入密钥（BuildConfig 是编译期常量、明文进 dex）→ 要用就装 debug 包，或等设置页 |
| 想确认发布物里没有密钥 | `strings` 扫 APK 的 `classes*.dex`，或直接 grep 到空 |

# Tappet — AGENTS.md

给 coding agent 的项目记忆。**先读 §0–§3 再动手**；细节按 §7 的索引读 `docs/`，
每份文档都写清了「什么时候读」。这里只放硬规则、可执行命令和改动入口。

## 0. 项目定位与方向红线

Tappet 是**运行在 Android 上的通用 mobile agent**：LLM 通过工具调用操作手机上的任意 App
完成任务，不做单一 App 的专用适配。仓库唯一运行时是 Android App（Kotlin）。

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| 数据 | Room + SQLCipher（会话 / 消息 / 工具调用），API key 由 Android Keystore 封装 |
| agent runtime | App 内嵌 Node 24（nodejs-mobile fork）+ dsh（DeepSeek Harness）phone profile |
| 设备执行层 | `AccessibilityService`（控件树优先、截图兜底）+ `DeviceBridgeServer`（127.0.0.1 + token） |
| 工具接入 | dsh 插件 `dsh-plugin-android-tools`（10 个通用原语 + 可选 `run_command`），独立公开仓库，经 HTTP 桥 → `DeviceAction`；另有只读邮箱连接器 |
| 手机上的 Linux | Termux + proot Debian，`tools/termux/bridge.py`（127.0.0.1 + token）→ 插件 `run_command`（不经无障碍） |
| native | JNI（`app/src/main/cpp/native-lib.cpp`）：Kotlin ↔ `node::Start`，需要 NDK 编译 |

- 开发机：Windows + PowerShell 7；设备：一加 15T（PLZ110，Android 16 / API 36，ColorOS）。
- **方向红线（2026-10-03 定稿）**：不采集通知、不做 QQ / 微信聊天记录管线、不做知识图谱、
  无 Python 侧、无 PC / Electron 客户端。相关代码已删除，**不要重新引入**。
  手写的 `LlmClient` 工具协议 / `AgentLoop` / 对话集成已暂停废弃，**不要继续完善**。
- 动手前读对应文档：动 agent runtime → `docs/dsh-android.md`；动执行层 →
  `docs/android-accessibility.md`；路线畅想（未验证）→ `docs/roadmap.md`。

## 1. 命令

环境：**JDK 21**（`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`）、PC 上的 Node 24 + npm、
Android SDK（compileSdk 36）、`ndk;29.0.14206865`、`cmake;3.31.6`（后三个用 `sdkmanager` 装）。

### 首次准备

```powershell
git submodule update --init --recursive   # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1                 # libnode（含 NDK 里的 libc++_shared.so）
.\tools\fetch-sherpa.ps1                  # 本地朗读引擎 native 库 + silero VAD 模型
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip
```

### 构建 + 单测（在 `android/` 下执行）

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
# 只跑一个测试类
.\gradlew.bat :app:testDebugUnitTest --tests "com.tappet.app.device.ScreenSnapshotRenderTest"
```

### 门禁（改完代码必跑；CI 与本地是同一套）

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain
.\gradlew.bat spotlessApply       # 格式能自动改的就自动改
```

| 工具 | 管什么 | 配置 | 出错怎么办 |
|---|---|---|---|
| ktlint（经 Spotless） | 格式 | `android/.editorconfig` | `spotlessApply` 自动改 |
| detekt | 坏味道 / 复杂度 | `android/config/detekt/detekt.yml` | 先看规则要不要放宽（写清理由）；**`baseline.xml` 是历史欠账快照，不许往里加** |
| Android Lint | API 级别 / 清单 / 资源 | `android/app/lint.xml` | **错误必须修；警告不挡 CI** |

两个踩过的坑：Spotless 的 `.editorconfig` 查找根是 **Gradle 工程目录**（写仓库根它看不见）；
ktlint 的规则属性（`ktlint_*`）经 Spotless 传不进规则，只能在 `editorConfigOverride` 里再给一次。

### 真机（先手动装包，不要用 Gradle 自动安装）

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
# install -r 会摘掉已启用的无障碍服务，装完必须重新启用：
adb shell settings put secure enabled_accessibility_services com.tappet.app/.accessibility.TappetAccessibilityService
adb shell settings put secure accessibility_enabled 1
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

### 任务审计

```powershell
node tools/audit/selftest.mjs                  # 离线自检（zstd 逐帧解码，不需要设备）
node tools/audit/run-suite.mjs                 # 跑固定任务集，每个跑完自动采集
node tools/audit/collect.mjs --label <名字>     # 只采集最新会话
```

- 取证层（`tools/audit/`）与判断层（`.claude/agents/tappet-auditor.md`）分开。
- 产物在 `tools/audit/runs/`（已忽略）：**含屏幕正文，按敏感数据对待**；审计报告里不得出现正文，
  只能写结构（长度 / 节点数 / 类名 / 坐标）。
- 任务集在 `tools/audit/suite.json`：**改任务文本 = 换任务，历史数据作废**；加场景请追加新 id，
  默认只放只读 / 可逆任务。`runs/index.json` 要留着累积。
- 包名与会话目录名**不硬编码**；要留存的东西放 `tools/` 或 `docs/`，别放 `tmp/`。
- 采集器与指标细节见 `tools/audit/README.md`。

### 环境注意（每条都踩过）

- **代理必须放 `GRADLE_OPTS` 环境变量** —— 行内 `-D` 会被 `gradlew.bat` 拆坏。
  依赖下载不通时改阿里云镜像 `https://maven.aliyun.com/repository/public`。
- **跑 `.ps1` 用 `pwsh` 7，不要用 `powershell` 5.1**：5.1 打的 zip 条目名是反斜杠，设备上
  解不出目录树，App 报 `ERR_MODULE_NOT_FOUND`（看着像依赖没装）。脚本末尾有自检。
- **`-Ptappet.abis=x86_64` 必须加引号**：PowerShell 会拆成两个参数，报
  `Task '.abis=x86_64' not found`。
- `tools/` 下的装配脚本只认 `HTTP_PROXY` / `HTTPS_PROXY`（默认跟随 127.0.0.1:7897），
  没有就走直连；代理没开时 npm 会 `ECONNREFUSED 127.0.0.1:7897`。
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（约 160MB）。
- 模拟器（x86_64）：`fetch-libnode.ps1 -Abi x86_64` + `"-Ptappet.abis=x86_64"`；
  **用完切回 arm64**，否则下次真机包缺 `libc++_shared.so`。
- **CI 绿 ≠ 能用**：执行层、内嵌 Node 启动、真实任务只能在真机上验证。

## 2. 改动工作流

### 2.1 改 Kotlin（任何行为改动）

1. 改代码，并补 `src/test`（或 `src/androidTest`）的测试 —— **改了行为必须有测试**。
2. `:app:testDebugUnitTest` → 门禁三条命令。
3. 涉及界面 / 数据库 / 权限的，按 §1 真机流程验证。

### 2.2 改 dsh 运行时 / profile / 插件

1. 插件在 `tools/dsh-phone/plugins/`（submodule，独立公开仓库）→ **先在插件仓库提交**，
   再在 tappet 更新 submodule 指针。
2. `.\tools\bundle-dsh.ps1` —— 它用**真解析器**验 profile；YAML 写坏时 dsh 启动即报错，
   而内嵌 Node 与宿主同进程，报错就是整个 App 崩。
3. `:app:testDebugUnitTest`，然后**重装 APK**（运行时打包在 assets 里；APK 更新后首次启动
   自动重解压）。
4. 插件发版由插件仓库的 `v*` tag workflow 完成（GitHub Release 附 `npm pack` tarball）。

### 2.3 加 / 改设备动作 = 四处同一原子语义

`device/DeviceAction.kt`（定义）+ `TappetAccessibilityService`（执行）+
`bridge/DeviceBridgeServer`（HTTP 映射）+ 插件 `tools/dsh-phone/plugins/android-tools/index.mjs`
（`ctx.tools` 注册，含给模型看的描述）。插件改完 → `bundle-dsh.ps1` → 重装 APK。

模型可见的 10 个工具：`read_ui_tree` / `tap_node(ref)` / `long_press(ref,duration_ms)` /
`capture_screenshot()` / `tap_target(screenshot_id,x1,y1,x2,y2)` /
`long_press_target(同前+duration_ms)` / `type_text` / `press(back|home|recents|enter)` /
`launch_app(query)` / `swipe(direction)`。描述统一写「先读屏、操作后再确认、每次只做一个原子动作」。
配了 Linux 后多一个 `run_command`；连了邮箱后多 3 个只读工具（`mail_list` / `mail_search` /
`mail_read`）。

- **坐标契约（2026-10-17）**：模型侧唯一坐标系 = 最近一次 `capture_screenshot` 的**图像像素**
  （1080×2344 → 943×2047），永不换算、永不碰屏幕像素；`device/ScreenFrame.kt` 的 `toScreen`
  是唯一换算点。截图有状态（`STALE_SCREENSHOT`），越界直接拒（`OUT_OF_SCREENSHOT`，不 clamp）。
  细节与真机验证见 `docs/android-accessibility.md` §9。
- **输入文字（2026-10-20）**：控件树优先，树不可读时走无障碍文本注入通道
  （`flagInputMethodEditor`，API 33+，不需要切输入法）。五个失败码各对应一个下一步：
  `text_no_channel` = 先 `tap_target` 点一下输入框；`text_flag_missing` = 升级后重新启用无障碍。
  细节见 `docs/android-accessibility.md` §10。

### 2.4 加 / 改提供商、模型

四处：`LlmPreset` → 设置页 → `AgentConfigStore` → `NodeRuntime` + profile。
改完 `bundle-dsh.ps1` + 重装 APK。**端点 / 调用风格 / 模型必须成套**，`PROVIDER` 键逐字匹配。
完整规则与排障见 `docs/providers.md`。

### 2.5 其他子系统：先读文档，再动手

| 改什么 | 先读 | 改完必做 |
|---|---|---|
| 连接器（Gmail 等） | `docs/email-connector.md` | 插件名三处一致 + `bundle-dsh.ps1` + 重装 |
| 联网搜索 | `docs/web-search.md` | `bundle-dsh.ps1` + 重装；搜索模型必须实测 |
| 语音（朗读 / 输入） | `docs/voice.md` | 跑单测；真机按文档 §9 验 |
| 应用内更新 | `docs/app-updater.md` | `UpdateDownloaderResumeTest` |
| 手机上的 Linux | `docs/termux.md` | 插件仓库先提交 + `bundle-dsh.ps1` |
| 记忆 / 上下文注入 | `docs/memory.md` | — |
| 定时任务 | `docs/scheduled-tasks.md` | — |
| `ask_user_question` | `docs/user-questions.md` | — |
| 端侧推理 | `docs/on-device-inference.md` | — |

### 2.6 发版

顺序不能反：改 `versionCode/versionName` → 写 `docs/releases/<tag>.md` + `CHANGELOG.md`
并提交 → 打 `v*` tag。**包内版本号来自 `build.gradle.kts`，不是 tag。**
完整流程、签名材料与 CI 说明见 `docs/releasing.md`。

## 3. 硬规则

### 3.1 安全与隐私（红线，不做例外）

1. **密钥永不入库、不进日志、不进构建产物**：唯一来源是设置页 → `SecretVault`（Keystore 封装），
   运行时经 env `TAPPET_LLM_KEY` 交给 dsh。构建期注入那条路已删除（`AGENT_API_KEY` 不存在），
   所以「包里有密钥」在结构上不可能发生。
2. **屏幕与聊天正文不落日志**：只记计数 / 类名 / 坐标 / 错误码；读屏 payload 只出现在给模型的
   工具结果里。`--ez logPayload true` 仅供调试，用完不要留在脚本里。
3. **视觉路径把整屏图像发给模型服务商**（含屏幕上的一切正文）：与读屏 payload 同级 ——
   只进给模型的工具结果，不落日志、不落盘（附件落在 App 私有目录、内容寻址）；
   但**别把它写成「只记结构」**。
4. `tappet.db` 用 SQLCipher 加密，口令由 Keystore 封装：**卸载即永久丢失，外部不可解、不可拷**。
   任何卸载 / 清数据 / 重装前先确认已导出。
5. `TappetDatabaseProvider` 必须显式 `System.loadLibrary("sqlcipher")`（删旧代码时丢过一次，
   表现为 `nativeOpen` 的 UnsatisfiedLinkError、界面「本地存储不可用」）。
6. **不采集他人数据**：通知采集与聊天记录管线已整体删除；如需重新引入任何采集能力，
   先在 `docs/` 写清边界与隐私闸门。邮箱连接器与联网搜索就是照这条走的。

发布前自检：

```powershell
.\tools\check-sensitive.ps1                 # 扫工作树 + 全部 git 历史
.\tools\check-sensitive.ps1 -ApkPath <包>   # 要对外发包时，连包一起扫
.\tools\check-sensitive.ps1 -SelfTest       # 先证明「扫描器确实会命中」
```

- 输出**不打印密钥本体**，只报定位；`exit 1` = 有命中。
- 它认不出「真实标识」——那组启发式只报警不判失败，判断得靠人。
- **`-SelfTest` 不是可选项**：不确认扫描器能命中，空结果就只是「碰巧通过」。

### 3.2 运行时与执行层

- **契约与 ROM 解耦**：执行层只依赖 `DeviceBridge` 与 `OemProfile`；换 ROM 只加一个
  `OemProfile`。不写 App 专用逻辑 —— QQ / 微信只是试验任务的目标 App，不是适配对象。
- **桥只监听 `127.0.0.1` + 每进程随机 token**，只暴露 `POST /action`；不在设备上安装插件。
- **任务期不开触摸探索**（2026-10-17）：探索会吞掉 `dispatchGesture` 的手势，且救不了已启动的
  App；树不可读时走视觉路径。机制保留供实验（`setTouchExplorationForTask` + debug 广播
  `AGENT_MODE`），但 **`touchExplorationActive` 的语义兜底分支一个字都不能删** —— 用户自己开着
  TalkBack 时全局触摸探索恒为真，那些 `performAction` 分支是读屏用户唯一可用的路径。
- **改 dsh 运行时不要用 `boot()` / CLI loader**（硬依赖无 Android 产物的
  `node-addon-require-builtin`）：走 `launcher.mjs`；`patch-runtime.mjs` 放行 flock、把 jsonl
  硬链接发布改为 rename，并把进程内的 `agent/assistant-stream` 转成 `session.stream` 通知
  （补丁 4：没有它，回复只能整段出现）。补丁幂等，锚点失配直接报错，别静默跳过。
- **内嵌 Node 是进程内单实例、常驻形态**：stdout 是 JSON-RPC 协议独占（profile 不能挂 stdout
  logger，诊断走 stderr）；可连续任务、不需要 force-stop，但**不要发 `shutdown`、不要
  `process.exit()`**（会 FORTIFY/SIGABRT 掉整个 App）。跨 App 任务需前台服务保活。
- **配置切换走运行时重载，不是重启进程**：宿主写 `dsh-home/runtime-config.json`（非密）+
  `runtime.reload`；launcher 卸载整棵 cordis 根后重新 mount（实测 ~0.2–1s，同一 pid），并在
  mount 前用配置覆盖 `process.env`。密钥在 Node 启动时预置成 `TAPPET_LLM_KEY_<ID>`，
  配置里的 `TAPPET_LLM_KEY_FROM` 只做 env 拷贝 —— **密钥不落盘**。重载后必须重新 `initialize`
  并轮换 session 命名空间（`runtimeTag`），否则会撞上磁盘上已有的同名 dsh 会话。

### 3.3 提交纪律（2026-10-03 用户约定；2026-10-21 明确）

- **完成一个可验证的里程碑时自行提交**：构建 / 单测通过，或真机验证完成，即提交一个内聚的
  commit；不要攒大改动等用户开口。
- **一律在新分支上开发，不在 master 上直接改、直接提交**：先
  `git switch -c <feat|fix|docs>/<主题>`（开 worktree 本身就满足这一条）。master 上的每个提交
  都应当是**从分支合并进来的、已完成的一步**，不是半成品。
- **有 hook 兜底**：`tools/hooks/pre-commit` 在 HEAD 是 `master` 时拒绝提交（合并 / cherry-pick /
  revert 产生的提交除外；`core.hooksPath` 指向 `tools/hooks`，对所有 worktree 一起生效）。
  确实要在 master 上提交时用 `git commit --no-verify`，并说明理由。
- **开发完成之后合回 master**：本地 `git merge --no-ff <分支>` 再推，或开 PR（需要别人看时）。
  **管理员可以直推 master**，直推的是合并后的结果；非 admin 的贡献者仍受分支保护。
- **合并 PR 与 push 必须由用户逐次明确授权**：「改完就提交」是默认动作，
  「开 PR / 合并 / 推上去」不是。
- 提交信息：中文 conventional commit（`feat(android): ...`、`docs: ...`、`fix(repo): ...`），
  正文写清**改了什么与验证方式**；不含密钥与个人数据。未验证的半成品不提交；
  提交后保持工作区干净、可解释。
- 历史已经公开：一旦推过，重写历史就只剩「重写 + 强推」这条既难看又有代价的路。
  **不该公开的字面量（真实标识、密钥、本机路径）只要在 push 前发现，就地重写，别攒着。**

## 4. 命名与存储身份（改名红线）

改名前先把这张表看一遍 —— **漏一处，库或配置就静默读不出来**。

| 位置 | 作用 |
|---|---|
| `app/build.gradle.kts` 的 `applicationId` | 设备上的应用身份（uid） |
| `TappetDatabaseProvider.DATABASE_NAME` | 库文件名 |
| `PassphraseVault` 的 `prefsName` | 封装口令的 SharedPreferences 文件 |
| `PassphraseVault` 的 `keyAlias` | Keystore 里的密钥别名 |

另两组同类身份：

- `SecretVault` 的 `prefsName` / `keyAlias` 与 `AgentConfigStore.PREFS_NAME`；
  **提供商 id 也是存储身份**（`LlmPreset.id`）：`provider.<id>.*` 与
  `tappet-agent-llm-key-v1-<id>`。
- 连接器：`tappet_connector_settings`（非密 prefs）、`tappet_connector_secret-<id>`、
  `tappet-connector-<id>-v1`，以及 `<id>_client_id` / `<id>_client_secret` / `<id>_account`
  三个键。**常量本身带前导下划线，别去掉**（拼错过一次，得到 `gmailclient_id`）。

组件名统一 `com.tappet.app/...`；`OemProfiles.TAPPET_ACCESSIBILITY_COMPONENT` 读
`BuildConfig.APPLICATION_ID`，会自己跟随。

**Git 历史（2026-10-12 发布前重建）**：对外历史的根提交是重建出来的，之前的提交都已不存在 ——
**别把「历史很短」当成仓库刚建**。完整旧历史在仓库外
（`../memex-history-backup-2026-10-12.bundle`）。**以后往仓库写任何来自真实数据的内容，
先脱敏再提交** —— 标识一进 git 就跟着每个 clone 走。旧包 `com.memex.app` 若还在设备上，
其数据新包读不到，`adb uninstall com.memex.app` 即可。

## 5. 仓库结构

```
android/                        唯一运行时（Kotlin / Compose）
  app/src/main/java/com/tappet/app/
    agent/                      会话状态 + DshStreamer + LlmPreset（提供商目录）
    accessibility/              AccessibilityService（执行层实现）
    bridge/                     DeviceBridgeServer / ConnectorBridgeServer（两个本地 HTTP 桥）
    connector/                  连接器：OAuth（PKCE + 回环）+ SecretVault 凭据 + 只读边界
    data/                       Room + SQLCipher、Keystore 口令、SecretVault
    device/                     通用设备契约：DeviceAction / DeviceBridge / OemProfiles
    node/                       NodeRuntime：解压 dsh、注入 env、JNI 启动
    voice/                      朗读 + 语音输入（VAD / 识别 / 回填输入框）
    ui/                         Compose 界面（聊天 / 首页 / 设置）
  app/src/main/cpp/             JNI（node::Start + stdout 转发到 logcat）
  app/src/test/                 JVM 单测
  app/src/androidTest/          真机测试
tools/
  fetch-libnode.ps1             拉 pinned libnode + 从 NDK sysroot 复制 libc++_shared
  fetch-sherpa.ps1              拉 sherpa-onnx 预编译 Android 库
  bundle-dsh.ps1                装配 vendored dsh → assets/dsh-runtime.zip（含 profile 校验）
  check-sensitive.ps1           发布前自检（工作树 + 全历史 + 可选 APK）
  audit/                        任务审计取证层（判断交给 tappet-auditor）
  termux/                       Termux 桥 + 一键装配
  dsh-phone/                    launcher / profile / 补丁 / 离线单测 / 插件
docs/                           深度文档 + releases/<tag>.md
```

`libnode.so`、dsh 运行时（`assets/dsh-runtime.zip`、`libnode/`、`jniLibs/`）都**不入库**，
由 `tools/` 脚本按 pinned 版本装配（在 `.gitignore` 内）。

## 6. 真机速查

- **不要走 Gradle 的自动安装通道**：设备上原本没有此包时会被 ColorOS 拦，挂 ~5 分钟后报
  `Starting 0 tests` + `Failed to install split APK(s)`。正确流程：`assembleDebug` →
  `adb install -r -g`（`-g` 顺带保住 Termux 的 `RUN_COMMAND` 授权）。
- **`adb install -r` 会摘掉已启用的无障碍服务**，装完重新启用（命令见 §1）。
- `pm grant` / `appops set` 会被 ColorOS 加固拦截；App 只需要 `INTERNET`。
- **无障碍只能用户自己开**（`WRITE_SECURE_SETTINGS` 是系统签名权限，Android 有意不允许应用
  自授权）。应用侧引导在 `AccessibilityStatus` + 聊天页提示卡 + 设置页：**只有真的有一次设备
  动作因此失败过**才提示；码走 `BridgeHealth`，不去匹配中文文案。
- ColorOS 首次启用 / 重装后弹「保持开启」安全弹窗（3 秒倒计时），不点可能被关闭；也会 defer
  后台广播 —— 广播前先把 App 拉到前台（`monkey`）。
- `uiautomator dump` 会顶掉无障碍服务约 1 秒，用 adb 自动化时避开。
- logcat 关键 tag：`TappetDevice`（执行层）、`TappetNode`（Node 宿主）、`TappetAudit`
  （审计标记，机器读的 `key=value`；改名要同步 `AUDIT_TAG`）、`NODEJS-MOBILE`（Node stderr；
  stdout 是协议，不落日志）。

debug 广播（release 不含）：

```powershell
# 启动常驻运行时（只跑一次；之后可连续任务）
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op START
# 提交任务（可连续多次；--ez logPayload true 才打印助手正文）
adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op TASK --es task '任务文本'"
# 直接驱动执行层原子动作（目标 App 在前台时用）
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action READ_SCREEN
# 视觉路径：取一张截图，再按截图的图像像素点
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action CAPTURE_FRAME
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action TAP_TARGET --es screenshot_id f1 --ei x1 400 --ei y1 1000 --ei x2 543 --ei y2 1047
```

其余入口（`TEXT_CHANNEL` / `IME_TEXT` / `IME_ENTER` / `TERMUX` / 语音 `op ASR|VAD`）见
`docs/android-accessibility.md` §10、`docs/termux.md`、`docs/voice.md` §9。
带空格的参数必须把整条 `am broadcast` 再用 `adb shell "..."` 包一层。

## 7. 排障与文档索引

### 先查这 12 条

完整表在 `docs/pitfalls.md`（按主题分组；提供商 / 更新 / Termux / 发版的坑在各自文档）。

| 现象 | 原因 / 处理 |
|---|---|
| 全新安装 Gradle 挂 5 分钟后失败 | ColorOS 拦 `adb install` 通道 → 手动 `adb install -r` |
| 装完读屏失效 / 无障碍变 null | `install -r` 摘掉无障碍 → 重新 `settings put secure` |
| `dlopen failed: libc++_shared.so not found` | 重跑 `fetch-libnode.ps1`；确认落在 `jniLibs/<abi>/` |
| 任务结束 App 崩溃（FORTIFY/SIGABRT） | 调了 `process.exit()`（含 SDK `shutdown`）→ 只记录、不退出 |
| 广播后卡在 `debug task start` | cached app freezer 冻住进程 → 先 `am start` 拉到前台 |
| JSON-RPC 偶发不响应 | stdout 混入日志 → profile 不挂 stdout logger |
| dsh 启动报 `bad indentation`，App 崩 | profile 的 `!!js` 对象字面量没加引号 → 加引号 |
| 读屏没有一个可读节点（微信） | 树被隐藏 → 走视觉路径（`EMPTY_TREE_HINT`） |
| 按图点击偏左偏上约 12.6% | 用 `tap_target` 的图像像素，别用屏幕像素 |
| 树不可读时 `type_text` 失败 | 先看失败码；`text_no_channel` → 先 `tap_target` 点输入框 |
| 装完 App 起不来 `ERR_MODULE_NOT_FOUND` | 用 PowerShell 5.1 跑了 `bundle-dsh.ps1` → 用 `pwsh` 7 重跑 |
| 推送被拒 `GH013` | 分清 PR 规则还是 push protection；后者是 GitHub 认为有密钥 |

### 文档索引

| 文档 | 什么时候读 |
|---|---|
| `docs/dsh-android.md` | 动 agent runtime、内嵌 Node、dsh 装配之前（含各阶段实测结论） |
| `docs/android-accessibility.md` | 动执行层、视觉路径、文本注入通道、换设备 / ROM 之前 |
| `docs/providers.md` | 动提供商 / 模型 / 模态 / 运行时路由之前 |
| `docs/voice.md` | 动语音之前：引擎取舍、模型授权、VAD 三个阈值、真机怎么验 |
| `docs/email-connector.md` | 动连接器之前：边界、隐私闸门、OAuth 注册步骤 |
| `docs/web-search.md` | 动联网搜索之前：两道门（协议门 / 真搜门）的实测、隐私闸门 |
| `docs/app-updater.md` | 动应用内更新之前：续传的三个细节、验证方式 |
| `docs/termux.md` | 动手机上的 Linux 之前：token 投递、脚本三条硬约束、ColorOS 生命周期 |
| `docs/memory.md` | 动记忆 / 上下文注入之前：dsh 的三件机制与移动端移植缺口 |
| `docs/scheduled-tasks.md` | 动定时任务之前：Android 没有 cron、精确 / 不精确两档、隐私边界 |
| `docs/user-questions.md` | 动 `ask_user_question` 之前：waterfall 注册顺序、答案语义 |
| `docs/on-device-inference.md` | 动端侧模型 / 推理引擎之前：设备实测、`ADSP_LIBRARY_PATH` 坑 |
| `docs/releasing.md` | 动 workflow、发版、改签名材料之前 |
| `docs/pitfalls.md` | 排障时先搜这里 |
| `docs/roadmap.md` | 想推进方向时（**未验证的假设**，不是承诺） |
| `docs/releases/<tag>.md` | 发版说明的写法与历史 |
| `README.md` / `CHANGELOG.md` | 对外介绍与对外可见的变化 |

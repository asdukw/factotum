# Tappet — AGENTS.md

给 coding agent 的项目记忆：**先读完再动手**。这里只放硬规则与可直接执行的命令，
都是实测结论，别凭直觉推翻；来由与实测数据在 `docs/`，本文件末尾有索引。

## 项目概览

Tappet 是**运行在 Android 上的通用 mobile agent**：LLM 通过工具调用操作手机上的任意 App
完成任务，不做单一 App 的专用适配。仓库唯一运行时是 Android App（Kotlin）。

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| 数据 | Room + SQLCipher（会话 / 消息 / 工具调用），API key 由 Android Keystore 封装 |
| agent runtime | App 内嵌 Node 24（nodejs-mobile fork）+ dsh（DeepSeek Harness）phone profile |
| 设备执行层 | `AccessibilityService`（控件树优先、截图兜底）+ `DeviceBridgeServer`（127.0.0.1 + token） |
| 工具接入 | dsh 插件 `dsh-plugin-android-tools`（10 个通用原语 + 可选的 `run_command`），独立公开仓库，经 HTTP 桥 → `DeviceAction`；另有只读邮箱连接器 |
| 手机上的 Linux | Termux + proot Debian，`tools/termux/bridge.py`（127.0.0.1 + token）→ 插件 `run_command`（不经无障碍） |
| native | JNI（`app/src/main/cpp/native-lib.cpp`）：Kotlin ↔ `node::Start`，需要 NDK 编译 |

- 开发机：Windows + PowerShell 7；设备：一加 15T（PLZ110，Android 16 / API 36，ColorOS）。
- **方向红线（2026-10-03 定稿）**：不采集通知、不做 QQ / 微信聊天记录管线、不做知识图谱、
  无 Python 侧、无 PC / Electron 客户端。相关代码已删除，**不要重新引入**。
  手写的 `LlmClient` 工具协议 / `AgentLoop` / 对话集成已暂停废弃，**不要继续完善**。
- 动手前读对应文档：动 agent runtime → `docs/dsh-android.md`；动执行层 →
  `docs/android-accessibility.md`；路线畅想（未验证）→ `docs/roadmap.md`。

## 快速上手

环境：**JDK 21**（`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`）、PC 上的 Node 24 + npm、
Android SDK（compileSdk 36）、`ndk;29.0.14206865`、`cmake;3.31.6`（后三个用 `sdkmanager` 装）。

```powershell
# 首次准备：libnode（含 NDK 里的 libc++_shared.so）与 vendored dsh
git submodule update --init --recursive   # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1
.\tools\bundle-dsh.ps1

# 构建 + 单测（在 android/ 下执行）
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- **代理必须放 `GRADLE_OPTS` 环境变量** —— 行内 `-D` 会被 `gradlew.bat` 拆坏。
  依赖下载不通时改阿里云镜像 `https://maven.aliyun.com/repository/public`（实测秒下）。
- **跑 `.ps1` 脚本用 `pwsh`（PowerShell 7），不要用 `powershell`（5.1）**：5.1 跑在 .NET
  Framework 上，`bundle-dsh.ps1` 打的 zip 条目名会变成反斜杠，装到设备上解不出目录树、
  App 直接起不来（报的是 `ERR_MODULE_NOT_FOUND`，看着像依赖没装）。脚本末尾现在有自检。
- `tools/` 下的装配脚本只认 `HTTP_PROXY` / `HTTPS_PROXY`（默认跟随本机 127.0.0.1:7897），
  没有就走直连（CI 如此）。代理没开时 npm 会 `ECONNREFUSED 127.0.0.1:7897` ——
  临时绕过：当前 shell 清空这两个变量再跑。
- 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（含内嵌 Node 与 dsh 运行时，约 160MB）。
- 模拟器（x86_64）：先取 x86_64 的 libnode，再切 ABI（默认仍是真机 arm64）：

```powershell
.\tools\fetch-libnode.ps1 -Abi x86_64
.\gradlew.bat :app:assembleDebug "-Ptappet.abis=x86_64" --console=plain
```

- **`-Ptappet.abis=x86_64` 必须加引号**：PowerShell 会把不加引号的 `-Ptappet.abis=x86_64` 拆成
  `-Ptappet` 与 `.abis=x86_64`，Gradle 报 `Task '.abis=x86_64' not found`（与 `GRADLE_OPTS` 那条同类）。
- 用完记得切回真机 ABI：`tools/fetch-libnode.ps1`（默认 arm64）。**别让 jniLibs 停在 x86_64** ——
  下次默认构建出的 APK 会缺 arm64 的 `libc++_shared.so`，装到手机上 `dlopen` 失败。

## 开发工作流

- **改了 `tools/dsh-phone/` 下的 launcher / profile / 插件** → 必须重跑 `bundle-dsh.ps1`
  并重装 APK 才生效（运行时打包在 assets 里；APK 更新后首次启动自动重解压）。
  `bundle-dsh.ps1` 会先用**真解析器**验一遍 profile：overlay 的 YAML 写坏时 dsh 启动即报错，
  而内嵌 Node 与宿主同进程 —— 报错就是整个 App 崩。
- **改插件**（`tools/dsh-phone/plugins/`，submodule → 独立公开仓库）：先在插件仓库提交，
  再在 tappet 更新 submodule 指针。插件发版由插件仓库的 `v*` tag workflow 完成
  （GitHub Release 附 `npm pack` tarball；npm 发布是同一 workflow 里的 gated job）。
- **改设置页 / 端点预选** → 下次启动 App 才生效（内嵌 Node 的 env 与 profile 只在启动时读一次）。
- **改 Kotlin 后必跑单测**；改了行为要同步补 `src/test` 或 `src/androidTest` 的测试。

## 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain          # JVM 单测（快，改 Kotlin 后必跑）
.\gradlew.bat :app:testDebugUnitTest --tests "com.tappet.app.device.ScreenSnapshotRenderTest"
```

真机测试（`androidTest/`，覆盖 Room/SQLCipher 与 Compose UI）：**先手动装包再单独跑测试任务**，
不要用 Gradle 自动安装（ColorOS 会拦全新安装）：

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
# 重新启用无障碍（见「真机」一节），然后：
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

### 任务审计回路（替代「人工跑一遍翻日志凭印象」）

取证层与判断层分开：`tools/audit/` 是确定性脚本，`.claude/agents/tappet-auditor.md`
是读产物出报告的只读子 agent。

```powershell
node tools/audit/selftest.mjs                  # 离线自检（zstd 逐帧解码，不需要设备）
node tools/audit/run-suite.mjs                 # 跑固定任务集，每个跑完自动采集
node tools/audit/collect.mjs --label <名字>     # 只采集最新会话
```

- 产物在 `tools/audit/runs/`（已 gitignore）：**含屏幕正文，按敏感数据对待**；
  审计报告里不得出现正文，只能写结构（长度 / 节点数 / 类名 / 坐标）。
- 任务集在 `tools/audit/suite.json`：**改任务文本 = 换任务，历史数据作废**，加场景请追加新 id；
  默认只放只读 / 可逆任务。`runs/index.json` 要留着累积 —— 跨运行才看得出「工具设计问题」。
- 包名与会话目录名**不硬编码**（现读 `build.gradle.kts` 的 applicationId）：上一轮改名时，
  一批硬编码旧包名的脚本是**静默**失效的，报出来像设备问题。要留存的东西放 `tools/` 或 `docs/`，别放 `tmp/`。

## 代码风格与门禁

CI 与本地跑的是同一套命令，**改完代码在 `android/` 下跑这三条**：

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain   # 格式 / 坏味道 / Android 专有检查
.\gradlew.bat spotlessApply                                         # 格式能自动改的就自动改
```

| 工具 | 管什么 | 配置 | 出错怎么办 |
|---|---|---|---|
| ktlint（经 Spotless） | 格式 | `android/.editorconfig` | `spotlessApply` 自动改 |
| detekt | 坏味道 / 复杂度 | `android/config/detekt/detekt.yml` | 先看规则要不要放宽（写清理由）；历史欠账进 `baseline.xml` |
| Android Lint | API 级别 / 清单 / 资源 | `android/app/lint.xml` | 错误必须修；警告只进报告 |

- **Lint 的错误挡 CI，警告不挡**：几十条警告里多数是「有新版本可用」，升成错误等于逼着
  每次上游发版都改一遍构建脚本。
- **detekt 的 `baseline.xml` 是历史欠账快照，不是免检区**：它只挡新增，**不许往里加**；
  真要加，先在提交信息里说明为什么这次必须放宽。
- 两个踩过的坑：Spotless 的 `.editorconfig` 查找根是 **Gradle 工程目录**（写仓库根它看不见）；
  ktlint 的规则属性（`ktlint_*`）经 Spotless 传不进规则，只能在 `editorConfigOverride` 里再给一次。
- Lint / detekt 的结果以 SARIF 传到仓库 Security 标签页，不用点进 CI 日志翻。

## CI 与发版

四个 workflow（`.github/workflows/`）：

| workflow | 触发 | 干什么 |
|---|---|---|
| `ci.yml` | push master / PR | 静态检查、单测 + androidTest 编译、离线自检（审计 selftest + profile 真解析器校验） |
| `assemble.yml` | 装配相关路径变动时 | 拉 libnode → 装配 dsh → NDK 出 debug 包 → **验产物结构**（native 库在不在、运行时资产是不是真 zip） |
| `codeql.yml` | push / PR / 每周 | 语义级安全扫描 |
| `release.yml` | 推 `v*` tag | **先过门禁** → 装配 → 签名出包 → 验签 + 扫包内密钥 → 发 release |

- **CI 绿 ≠ 能用**：无障碍执行层、内嵌 Node 启动、真实任务只能在真机上验证。
  日常 CI 不装 NDK（那 ~1.5GB 只有 `assemble.yml` 与 `release.yml` 需要，按版本缓存）。
- **出包之后要验产物本身**，不能只看退出码：v0.1.0 第一次出的 APK 构建成功、装到设备上才炸
  （Linux 的 GNU tar 写不了 zip，产物是改了后缀的 tar），第二次又缺 `libc++_shared.so`。
  `assemble.yml` 就是为这两次教训加的。

**发版**（顺序不能反，`release.yml` 在 tag 上一次性把包与说明发出去）：

1. 改 `android/app/build.gradle.kts` 的 `versionCode` / `versionName` —— **包内版本号来自这里，
   不是来自 tag**，漏改会打出「tag 叫 `v0.6.0`、装到设备上却报 `0.5.0`」的包；
2. 写 `docs/releases/<tag>.md` 并更新 `CHANGELOG.md`，**先提交再打 tag** —— `release.yml` 只读
   仓库里已有的那份说明文件，没有就退回 `--generate-notes`；
3. 打 `v*` tag → 出签名 APK，附 `tappet-<tag>-arm64-v8a.apk` 与 `.sha256`。

| 签名材料 | 位置 |
|---|---|
| release keystore | `C:\Users\asdukw\keys\tappet-release.jks`（**仓库外**，RSA 4096 / 10000 天） |
| 本机配置 | `android/local.properties`（gitignored）的 `tappet.release.storeFile/storePassword/keyAlias/keyPassword` |
| CI | GitHub secrets：`TAPPET_KEYSTORE_BASE64` / `TAPPET_KEYSTORE_PASSWORD` / `TAPPET_KEY_ALIAS` / `TAPPET_KEY_PASSWORD` |

- **keystore 丢了就再也发不出能覆盖安装的更新**（换签名 = 另一个 App，设备上旧包的库读不到）。
  与 SQLCipher 口令同性质：**先备份再折腾**，别只留一份在开发机上。
- 本机也能出签名包（读取顺序：环境变量 > local.properties）。
- 发之前至少跑一遍：门禁三条命令 + 单测，以及 `tools/check-sensitive.ps1 -ApkPath <包>`。

## 安全与隐私红线

1. **密钥永不入库、不进日志、不打印，也不进构建产物**：唯一来源是设置页 →
   `SecretVault`（Keystore 封装），运行时经 env `TAPPET_LLM_KEY` 交给 dsh。
   构建期注入那条路已删除（`AGENT_API_KEY` 不存在了），所以「包里有密钥」在结构上不可能发生 ——
   而不是靠人记得别发包。
2. **屏幕与聊天正文不落日志**：只记计数 / 类名 / 坐标 / 错误码；读屏 payload 只出现在
   给模型的工具结果里。debug 广播的 `--ez logPayload true` 仅供调试，用完不要留在脚本里。
3. **视觉路径的感知面更大**：树不可读时改走截图，交给模型的是**整屏图像**（含屏幕上的一切正文），
   比结构化读屏大得多 —— 这一步就是把屏幕内容发给模型服务商。它和读屏 payload 同级：只进给模型的
   工具结果，不落日志、不落盘（附件落在 App 私有目录、内容寻址）；但**别把它写成「只记结构」**。
4. `tappet.db` 用 SQLCipher 加密，口令由 Keystore 封装：**卸载即永久丢失，外部不可解、不可拷**。
   任何卸载 / 清数据 / 重装前先确认已导出。
5. SQLCipher 原生库不会自动加载：`TappetDatabaseProvider` 必须显式 `System.loadLibrary("sqlcipher")`
   （删旧代码时丢过一次，表现为 `nativeOpen` 的 UnsatisfiedLinkError、界面「本地存储不可用」）。
6. **不采集他人数据**：通知采集与聊天记录管线已整体删除；如需重新引入任何采集能力，
   先在 `docs/` 写清边界与隐私闸门，再动代码。**邮箱连接器就是照这条走的** ——
   边界与闸门在 `docs/email-connector.md`（只读、不落盘、不后台轮询、正文不进日志）。

发布前自检：

```powershell
.\tools\check-sensitive.ps1                 # 扫工作树 + 全部 git 历史
.\tools\check-sensitive.ps1 -ApkPath <包>   # 要对外发包时，连包一起扫
.\tools\check-sensitive.ps1 -SelfTest       # 先证明「扫描器确实会命中」
```

- 输出**不打印密钥本体**，只报定位；`exit 1` = 有命中。
- 它认不出「真实标识」——那组启发式只报警不判失败，判断得靠人（历史真出过这类泄漏）。
- **`-SelfTest` 不是可选项**：不确认扫描器能命中，空结果就只是「碰巧通过」。

## 架构与改动入口

### 加一个设备动作 = 四处同一原子语义

`device/DeviceAction.kt`（定义）+ `TappetAccessibilityService`（执行）+
`bridge/DeviceBridgeServer`（HTTP 映射）+ `tools/dsh-phone/plugins/android-tools/index.mjs`
（`ctx.tools` 注册，含给模型看的描述；独立公开仓库，先在插件仓库提交再更新 submodule 指针）。
插件改完 → `bundle-dsh.ps1` → 重装 APK。

**模型可见的 10 个工具**（插件 v0.5.0）：`read_screen` / `tap_node(ref)` / `long_press(ref,duration_ms)` /
`capture_frame()` / `tap_target(frame_id,x1,y1,x2,y2)` / `long_press_target(同前+duration_ms)` /
`type_text` / `press(back|home|recents|enter)` / `launch_app(query)` / `swipe(direction)`。
工具描述统一写「先读屏、操作后再确认、每次只做一个原子动作」。
配了手机上的 Linux 之后**再多一个 `run_command`**（真 shell，见「手机上的 Linux」一节）。
用户连上邮箱后**另有 3 个只读工具**（`mail_list` / `mail_search` / `mail_read`），
由 `dsh-plugin-gmail` 提供，不经过无障碍（见「加一个连接器」一节）。

桥里另有 `screenshot` 与 `tap_point` 两个**屏幕像素**动作，只给 debug 广播与旧脚本用 ——
**不在模型工具面里**：模型看到的坐标一律是帧的图像像素（见下），混进屏幕像素就是系统性点偏。

### 坐标契约：模型只说「帧的图像像素」（2026-10-17）

交给模型的截图会被缩到最长边 2048（1080×2344 的屏 → **943×2047**），而手势要的是屏幕像素。
两者以前没有任何声明的换算，模型连屏幕尺寸都看不到（工具结果只交 `summary` 文本）——
按图给的坐标会系统性偏约 12.6%，**而且不报错**。

现在把换算钉在一处：`device/ScreenFrame.kt` 的纯函数 `toScreen(x,y)`
（`round(offset + x*screenW/imageW)`，再夹到 `screenW-1`）。规则：

- **模型侧唯一坐标系** = 最近一次 `capture_frame` 那张图的图像像素；模型永不换算、永不碰屏幕像素。
- `capture_frame` 的 payload 带 `frameId / width / height / screenWidth / screenHeight / offsetX / offsetY`，
  插件把它渲染成**模型可见的图例文本**（模型只看得见文本，尺寸不写进文本它就拿不到）。
- 屏幕尺寸取**缩放前的捕获位图**，不是 `displayMetrics`（分屏 / 系统栏会让后者对不上）；
  两者不一致时打 `ev=frame_mismatch` 审计标记。
- 帧是**有状态的**：界面变了旧帧作废（`STALE_FRAME`），必须重新取帧；bbox 越界直接拒（`OUT_OF_FRAME`），
  **不 clamp** —— clamp 会把「给错坐标」变成「点了个别的地方」。
- 动作后验证：再截一张算 8×8 指纹比对，只回 `changed` 布尔**不回图**（图会进历史并被每轮重发）。
  `changed=false` 时 `ok` 仍是 true（`NO_VISIBLE_CHANGE`）—— 它是提示，不是失败。
- **视觉路径不解决「输入文字」**：`type_text` / `press(enter)` 都走控件树找输入框，树不可读时没有通道，
  只能如实告知用户做不到（自建 IME 是 roadmap §2.5 的事，还没做）。

### 加一个提供商 = 目录 + 存储 + 运行时

`agent/LlmPreset.kt`（端点 / 调用风格 / 默认（已验证）模型 / 推理格式）
→ 设置页 `ui/settings/`（列表 / 详情 / 模型选择；新增目录项会自动出现）
→ `AgentConfigStore`（按 `provider.<id>.*` 存覆盖；每个提供商一把 `SecretVault` 密钥）
→ `NodeRuntime`（注入 `TAPPET_LLM_API` / `_BASE_URL` / `_MODEL` / `_MODELS` /
`_THINKING_FORMAT` / `_ZEN`，以及 `PROVIDER` 常量）
→ `tools/dsh-phone/profile/cordis.patch.yml`（单条 provider 路由，端点来自 env）。

- **`NodeRuntime.PROVIDER` 必须与 profile 里的 provider 键逐字相同**：漏改一处时报的是
  `no adapter registered for provider "xxx"`，看着像运行时缺插件（有单测钉住）。
- **端点、调用风格、模型必须成套**：OpenAI 是 `openai-responses`，Anthropic 是
  `anthropic-messages`，DeepSeek / OpenCode Go / OpenRouter / 自定义是 `openai-completions`。
  风格拿错不会在启动时报错，只会在第一次请求时以 404/400 的样子炸出来。
- **不维护模型清单**（2026-10-17）：[LlmPreset] 只留一个 `defaultModel`，用途是「已验证」
  组合判定与运行时兜底，**不是界面候选列表**。模型来自端点的 `GET /models`（详情 → 模型 →
  获取；结果落盘 `provider.<id>.remote_models`），拉不到的端点由用户手填 id。
- **模态由构建期索引决定**：`assets/model-modalities.json` 是 `bundle-dsh.ps1` 从 pi-ai 目录
  生成的（~60KB），运行时 `TAPPET_LLM_MODELS` 与运行时声明共用。顺序：当前 provider 条目 >
  跨 provider **结论一致**的同 id 条目 > text（欠声明只是提前拒图，过度声明会在请求中途被
  端点拒）。界面**不显示**可读图 / 仅文本标签（用户要求）。索引是生成物（gitignore），
  没跑过 `bundle-dsh.ps1` 的开发构建会全部按 text 声明。
- **模型清单是动态的，且必须包含当前选中的模型**：路由键 `tappet` 不是 pi-ai 目录里的
  provider，适配器不会从安装目录补模型 —— 未声明的 id 报 `UNKNOWN_MODEL`（**不是**退化成
  纯文本）。`ProviderConfig.llmModelsJson` 按选中模型生成 `TAPPET_LLM_MODELS`。profile 里
  只留一条 fallback，供 PC 侧校验与没有 env 的直接运行。
- **同一时间只启用一个提供商**：内嵌运行时每个进程只连一个端点。「启用」互斥，
  「全部停用」是显式状态（`ENABLED_NONE`），不会回落到默认提供商再把旧 key 用起来。
  启用 / 选中模型都要求三样齐全：端点、模型、密钥；新装的提供商是 0 个模型。
- 改了 profile 就要重跑 `bundle-dsh.ps1` 并重装 APK；`check-profile.mjs` 会真的执行
  `!!js` 表达式（含 `TAPPET_LLM_MODELS` 的动态清单）。**只有 OpenCode Go 的目录默认组合
  在真机上跑通过**；用户改过端点 / 模型 / 调用风格后，界面自动撤掉「已验证」。
- **可用模型列表**（`provider.<id>.models`）：设置页从端点返回的清单勾选，或手填 id；卡片上的
  「N 个模型」= 可用模型数。聊天页底部的模型条打开选择 sheet，选中后
  `setModel + setEnabled + NodeRuntime.reload`，立即生效。**重载会换 dsh 会话命名空间**：
  界面历史保留，但模型上下文从下一条消息重新开始（dsh SDK 只有 create、无 resume）。
- 清密钥 / 停用：密钥 env 只在 Node 启动时读入，**改 key 需要重启 App**；模型 / 提供商切换
  会 reload，不需要重启。

### 加一个连接器 = 宿主授权 + 本地桥 + 只读工具（2026-10-18 起）

连接器是**第二类数据源**：不经过无障碍，直接从用户的云端账号读数据。首版是 Gmail
（Gmail API，**只读**）。**动连接器之前先读 `docs/email-connector.md`** ——
那里写着边界、隐私闸门、提示注入的现状与 Google 侧的注册步骤；
要加写操作，先补危险动作闸门（roadmap §3.2）。

四处同一件事：

| 位置 | 管什么 |
|---|---|
| `connector/GoogleOAuth.kt` | 纯协议：PKCE / 授权 URL / 回环请求行 / 令牌解析。scope 常量 `GMAIL_SCOPE` 在这里 |
| `connector/GmailAuth.kt` + `ConnectorStore.kt` | 网络与存储：回环监听器 + 浏览器、refresh token 进 SecretVault、access token 只在内存 |
| `bridge/ConnectorBridgeServer.kt` | 只发短命 access token 的本地桥，**独立于设备桥**（理由见类注释） |
| `tools/dsh-phone/plugins/gmail/` | 模型可见的三个只读工具；Gmail 的 REST 语义全在这里 |

- **状态（2026-10-18，v0.5.0）**：授权全流程（浏览器同意 → 回环回调 → 落库）与 `mail_list`
  真实读到邮件都在一加 15T 上跑通，但**只在一个账号上验过**。两个已知边界：预置的 OAuth 客户端
  停在 Google **测试模式**（只有白名单账号能授权，refresh token 实测 7 天到期），发布包给别人用
  仍得让对方填自己的客户端 id；授权期间 App 进程必须活着（最长 10 分钟持有回环 socket），
  被杀就得重来。
- **令牌的信任边界**：refresh token 永不离开 Kotlin；插件每次调用向桥要一个 access token
  （`{"action":"access_token","connector":"gmail"}`）。所以「插件被注入」也拿不到长期凭据。
- **桥不代理云端请求**：只发令牌。否则一处 API 会被劈成「宿主知道路径、插件知道工具描述」两半。
- **没配连接器时工具照样挂着**，每次调用回 `NOT_CONNECTED` —— 工具面随配置忽隐忽现会让模型的
  行为不可预测，如实报错更好。
- **授权面比能力面宽**（2026-10-18 决定）：申请的是 `gmail.modify`（读 + 写 + 发），
  而工具面**目前仍然只有只读三个**。理由是 Google 对 installed apps 不支持增量授权，
  扩权要所有已连接用户重走同意页，所以一次要齐。
  **代价要记住**：只读 scope 下「插件被攻破也发不出邮件」是 Google 兜的；换成 modify 之后
  这条边界只剩**插件里那个唯一的 `gmailGet`（出网全是 GET）** 在守。加写操作之前先补
  危险动作闸门（roadmap §3.2），并同时改插件测试与 `docs/email-connector.md`。
- **插件名三处逐字相同**：`plugins/gmail/package.json` 的 `name`、profile 的 insert `name`、
  `bundle-dsh.ps1` 的 `Package`。对不上时运行时少一组工具，报错只有一句「找不到模块」——
  `NodeRuntimeProfileTest` 钉住了这三处。
- 插件改动同样要 `bundle-dsh.ps1` + 重装 APK 才生效。新连接器（Outlook 等）= 新的 Keystore
  别名 + 桥里加一个分支 + 新的插件，**不要复用 Gmail 的别名**。
- **客户端 id 与密码是预置的，但走构建期注入、不入库**（`ConnectorDefaults.kt` 读 `BuildConfig`）：
  本地读 `android/local.properties` 的 `tappet.connector.gmailClientId` / `...gmailClientSecret`，
  发布由 `release.yml` 从 GitHub Actions secret 注入。目标是「装完点一下就授权」，
  用户不必自己去 Google Cloud 注册应用。留空则退回「要求用户自己填」。
- **为什么不入库**：**GitHub 的 push protection 把 Google OAuth 的 client id 与 client secret
  都判成密钥，对管理员一样拦**（实测：`GH013 Repository rule violations ... Push cannot contain
  secrets`，连 `master` 直推也拦）。私有仓库解决不了 —— 值要参与换码就必须进二进制。
  注意这是**平台规则**，不是「它们是机密」：客户端 id/密码对桌面应用是公开标识
  （Google 原话：桌面应用是公共客户端、保不住秘密），真正的保护是 PKCE。
  `tools/check-sensitive.ps1` 认不出这两个形状（它的 Google 模式是 API key 的 `AIza…`），
  所以**别把它的「未发现密钥」当成可以推**——远端还有一道。
- **`master` 的 Ruleset 管理员确实能直推**（bypass actor 是 `RepositoryRole 5` = Maintain，
  admin 覆盖它）。实测输出是 `Bypassed rule violations ...`。被拒时先看清楚是哪一道：
  **PR 规则**还是 **push protection** —— 两者都报 "repository rule violations"，很容易看错。
- **Google 的设备码流程不支持 Gmail 权限**（只支持 Drive / YouTube / Sign-In），
  所以走的是**回环重定向** `http://127.0.0.1:<随机端口>`。好处：不改 Manifest、不注册签名
  SHA-1、不绑包名 —— 预置一个 client id 就能对所有人生效。

### 加一个联网搜索提供商 = 一处声明（2026-10-18 起）

联网搜索用的是 **dsh 自带的 web seam**，不是本仓库的插件：

| 位置 | 管什么 |
|---|---|
| `agent/LlmPreset.kt` 的 `Endpoint.search` | 该提供商的搜索端点（Anthropic 兼容基址）与模型；`null` = 没验证过 |
| `node/NodeRuntime.kt` | 注入 `TAPPET_WEB_SEARCH_BASE_URL` / `_MODEL` / `_KEY_ENV`（重载时也写进 runtime-config.json） |
| `tools/dsh-phone/profile/cordis.patch.yml` | ① `web-search-deepseek` 的 config 段（端点 / 模型 / 密钥来源全部读宿主注入）；② **宿主层 `- id: tool-web` + `disabled: false`** |
| `tools/dsh-phone/zen-session-fetch.mjs` | 替搜索 provider 补 zen 的 `x-opencode-session`（provider 没有自定义 header 的字段） |

- **工具行必须挂在宿主层，不能放进 agent preset 的 `plugins:`**：`dsh-web-app`（本 profile 的
  第二个 bundle）把 `dsh-base` 的宿主级 `tool-web` 行禁用了，改成「按 preset 组合」。照它的样子
  放进 `preset-phone` 里**组合结果里有那一行，但工具进不了模型的工具表**，而且日志里一个错都没有
  （2026-10-18 真机踩过）。宿主层 `disabled: false` 才对。
- **`task done ... tools=N` 的 `N` 是这一轮的工具调用次数，不是注册了几个工具** —— `tools=0`
  只说明模型没调工具。别拿它当可用工具数（我误判过一次）。
- **`web_search` / `web_fetch` 两个工具与提示词指引来自 `dsh-tool-web`** ——
  **不要再写一个自己的搜索插件**（写过一次，删了）。要加能力先看 dsh 有没有。
- **端点与模型是两套，不能从 `TAPPET_LLM_API` 推**：zen 聊天走 `openai-completions`，
  而它的原生 `web_search` 在 Anthropic 兼容的 `/messages` 上。基址**不含** `/messages`，
  provider 自己追加（写成带 `/messages` 的会得到 `.../messages/messages` → 一个看不懂的 404）。
- **「接受工具」不等于「会真的搜索」**：网关不校验模型是否真支持宿主 `web_search`。
  实测 zen 的 `deepseek-v4.1-flash` 收下工具、回 200、`input_tokens` 只有 50 —— 一次都没搜，
  然后给一段凭记忆编的话。所以搜索模型必须**单独指定并实测**，别拿当前聊天模型凑。
- **换协议比换模型划算**：`claude-haiku-5-5` 在 zen 的 `/responses` 上是 400
  `ModelProtocolUnsupported`，走 Anthropic 兼容的 `/zen/go/v1/messages` 却完全可用
  （`web_search_requests: 1`，input ~13k，比 `/responses` 那条的 17k–44k 便宜）。
- **没有可用 provider 时工具照样挂着**，回结构化的 `WEB_PROVIDER_UNAVAILABLE` ——
  与连接器同一条理由。可用性检查就是「密钥在不在」，所以不支持的提供商把
  `TAPPET_WEB_SEARCH_KEY_ENV` 指到一个解析不到的变量名即可，profile 里不需要分支。
- **不接搜索 API**：Bing Web Search API 已退役、Google Custom Search JSON API 已对新客户关闭
  （现有客户 2027-01-01 前迁移）。边界与实测见 `docs/web-search.md`。
### 手机上的 Linux = Termux 桥 + run_command（2026-10-18 起）

agent 能在手机的**文件系统**上干活：Termux 里跑 proot Debian，桥把「执行一条命令、拿回
stdout / stderr / 退出码」暴露在 127.0.0.1 上，插件的 `run_command` 打过去。装配见
`tools/termux/setup.sh`（幂等，跑一次）。**这条路不经过无障碍** —— 它改的是文件，不是屏幕。

- **token 由 Tappet 生成，不是用户抄的**。Tappet 读不到 Termux 的私有目录（跨 uid），
  但反过来可以 —— 经 Termux 的 `RUN_COMMAND` 把 token 送过去（`TermuxBridgeLauncher`）。
  token 进 SecretVault、地址进 prefs；**故意不进 `runtime-config.json`**（那个文件是明文），
  代价是改 token 要重启 App（与换 API key 同一条规矩）。
- **`RUN_COMMAND` 是 `dangerous` 权限**，必须用户在弹窗点一次，ColorOS 拦 `pm grant`。
  **`adb install -r` 会把它清掉**，但 **`adb install -r -g` 会带上** —— 开发期重装一律加 `-g`，
  省掉每次重新授权。
- **送进 Termux 的那段脚本有三条硬约束**（每条都是实测踩出来的，单测钉住）：
  ① **不许用 `pkill -f`** —— 脚本是被 `bash -lc <脚本文本>` 拉起的，**脚本文本就在 argv 里**，
  正文里写的任何模式都会匹配到脚本自己，pkill 把脚本自己杀掉（token 写完了、后面的行一行没跑、
  日志也没被碰过，外面只看到「桥没起来」——这个坑查了三轮）。要杀旧桥就扫 `/proc` 比命令行，
  并跳过自己与父进程。② **自己 export `HOME` / `PATH`** —— RUN_COMMAND 是非交互 shell，
  环境里连 `PREFIX` 都是空的，不摆正的话 `python` 根本找不到。③ **输出重定向到 `~/bridge.log`**
  —— RUN_COMMAND 的 stdout/stderr 没人接管，任何 traceback 都会消失。
- **验证这类脚本必须复现真实调用形态**：把脚本放在文件里跑（`bash x.sh`）脚本文本不在 argv 中，
  pkill 自杀这类问题**复现不出来**。要么按 `bash -lc <文本> name token` 跑，要么直接在真机上验。
- **ColorOS 会冻结、也会直接杀掉后台的 Termux**，桥跟着一起没。冻结表现为「端口还在 `LISTEN`
  但连 SYN 都不回」（连接超时）；被杀表现为「连接被拒 + `ServiceRecord` 的 `app=null`」。
  前者要在系统设置里放开 Termux 的后台运行；后者要靠 startService 冷启动 Termux，
  所以轮询窗口给到 45 秒（实测 `am start` 拉起它就要 ~6 秒）。`TermuxBridgeLauncher` 把
  「连接超时」与「没人监听」分成 `FROZEN` / `FAILED` 两种状态 —— 前者重试无用，后者才是配置问题。
- **换掉旧桥**：先按 pidfile 杀（`bridge.py` 启动时写），再扫 `/proc` 比命令行兜底
  （早于 pidfile 那版启动的桥没有 pidfile）——**不要用 `pkill -f`**，理由见上。
- **`/health` 不鉴权、`/ping` 鉴权**：前者回答「端口上有没有人」，后者回答「是不是我这把 token」。
  桥在跑但 token 不对时**必须重启它**，否则每条命令都 401，现象看起来像「桥坏了」。
- **`run_command` 只在注入了 `TAPPET_TERMUX_URL` / `_TOKEN` 时才注册**：没配这套的用户不该在
  工具表里看到一个必然失败的工具（每轮占 schema 预算，还会把模型引向死路）。
- **桥是无状态的**：每条命令一次独立的 `proot-distro login`（实测 200–430ms），cwd / 环境变量 /
  后台进程都不保留。要跑常驻服务得自己 `nohup ... > 日志 2>&1 &`（v1 的长驻会话还没做）。
- **没有 docker / systemd / iptables**（proot 不是真内核）。**AVF 那条路在这台机器上走不通**：
  OnePlus 15T 的 gunyah 只支持 protected VM，而 Android 16 的「终端」要 non-protected VM，
  启动即报 `Non-protected VMs are not supported on this device`。
- 涉及四处：`tools/termux/`（桥 + 装配）、`agent/TermuxBridgeStore|Launcher`、设置页
  「执行层 → Linux 环境」、插件 `run_command`（先在插件仓库提交再更新 submodule 指针）。
  **debug 广播 `op TERMUX`** 可在无 UI 的情况下验证启动器 —— 但授权那一下仍要用户点。

### 几条不许碰的约束

- **契约与 ROM 解耦**：执行层只依赖 `DeviceBridge` 与 `OemProfile`；换 ROM 只加一个 `OemProfile`。
  不写 App 专用逻辑 —— QQ / 微信只是试验任务的目标 App，不是适配对象。
- **桥只监听 `127.0.0.1` + 每进程随机 token**，只暴露 `POST /action`；不在设备上安装插件。
- **任务期不开触摸探索**（2026-10-17 改，原先按任务开关）：部分 App（实测微信 8.0.78）
  只在 `isTouchExplorationEnabled()` 为真时暴露控件树，但这条路已经不走 ——
  ① 探索一开，`dispatchGesture` 注入的手势就被系统当探索事件吞掉，而树不可读时没有节点
  可走 `performAction`，等于「只能看不能点」，偏偏树不可读正是要走视觉的时候；
  ② 它救不了已经在跑的 App：那个状态是目标 App 进程启动时采样一次并缓存的。
  所以 `DshStreamer` / `NodeDebugReceiver` **不再**调 `setTouchExplorationForTask`，
  树不可读时走视觉路径（`docs/android-accessibility.md` §9）。
  机制本身保留（`setTouchExplorationForTask` + debug 广播 `AGENT_MODE`）供实验用；
  **`touchExplorationActive` 的语义兜底分支一个字都不能删** —— 用户自己开着 TalkBack 时
  全局触摸探索恒为真、`dispatchGesture` 一样被吞，那些 `performAction` 分支是读屏用户
  唯一可用的路径。
- **改 dsh 运行时不要用 `boot()` / CLI loader**（硬依赖无 Android 产物的
  `node-addon-require-builtin`）：走 `launcher.mjs`；Android 上 `patch-runtime.mjs` 放行 flock、
  把 jsonl 硬链接发布改为 rename，**并把进程内的 `agent/assistant-stream` 转成 `session.stream`
  通知**（补丁 4：dsh 自己只发 step 级 `assistant/message`；没有这条，回复只能整段出现）。
  补丁是幂等的、锚点失配会直接报错，别静默跳过。
- **内嵌 Node 是进程内单实例、常驻形态**：stdout 是 JSON-RPC 协议独占（profile 不能挂 stdout logger，
  诊断走 stderr）；可连续任务、不需要 force-stop，但**不要发 `shutdown`、不要 `process.exit()`**
  （会 FORTIFY/SIGABRT 掉整个 App）。跨 App 任务需前台服务保活。
- **配置切换走运行时重载，不是重启进程**：宿主写 `dsh-home/runtime-config.json`（**非密**）+
  `runtime.reload` 信号；launcher 卸载整棵 cordis 根后重新 mount（实测 ~0.2–1s，同一 pid），
  并在 mount 前用配置覆盖 `process.env`。密钥在 Node 启动时预置成 `TAPPET_LLM_KEY_<ID>`，
  配置里的 `TAPPET_LLM_KEY_FROM` 只做 env 拷贝 —— **密钥不落盘**。重载后必须重新 `initialize`
  并轮换 session 命名空间（`runtimeTag`），否则会撞上磁盘上已有的同名 dsh 会话。
  换 API Key 仍是启动时读入：改 key 需要重启 App（或等下次进程启动）。

## 命名与存储身份（改名红线）

项目原名 **Memex**，2026-10-12 因定位是通用 mobile agent 更名为 **Tappet**（含 `applicationId`、
环境变量、Gradle 属性、日志 tag、CMake 工程名、JNI 符号）。旧包 `com.memex.app` 若还在设备上，
其数据新包读不到，`adb uninstall com.memex.app` 即可。

**存储身份共四处，任何一次改名都必须一起改**，漏一处库就读不出来：

| 位置 | 作用 |
|---|---|
| `app/build.gradle.kts` 的 `applicationId` | 设备上的应用身份（uid） |
| `TappetDatabaseProvider.DATABASE_NAME` | 库文件名 |
| `PassphraseVault` 的 `prefsName` | 封装口令的 SharedPreferences 文件 |
| `PassphraseVault` 的 `keyAlias` | Keystore 里的密钥别名 |

**另一组同类身份**：`SecretVault` 的 `prefsName` / `keyAlias` 与 `AgentConfigStore.PREFS_NAME`。
**提供商 id 也是存储身份**：`provider.<id>.*` 与每个提供商各自的 Keystore 别名
（`tappet-agent-llm-key-v1-<id>`）都带着 `LlmPreset.id`；改 id = 该提供商的配置与密钥静默读不回来。
改这几个的后果轻一些（丢的是用户填的 API key，不是聊天库），但同样静默 ——
表现出来只是「设置页说我配过，但用起来像没配」。

**连接器又是一组**：`tappet_connector_settings`（非密 prefs）、`tappet_connector_secret-<id>`
（SecretVault 的 prefsName）、`tappet-connector-<id>-v1`（Keystore 别名），以及
`<id>_client_id` / `<id>_client_secret` / `<id>_account` 三个键，**都带着连接器 id 且带下划线**
（拼错过一次：`connectorId + "client_id"` 得到的是 `gmailclient_id`，与文档写的对不上 ——
现在常量本身带前导下划线，别去掉）。改名 = 设备上已完成的 OAuth
授权静默失效，表现是「设置页说连过，但三个邮件工具全报未连接」。

组件名统一 `com.tappet.app/...`；`OemProfiles.TAPPET_ACCESSIBILITY_COMPONENT` 读
`BuildConfig.APPLICATION_ID`，会自己跟随。

**Git 历史（2026-10-12 发布前重建）**：对外历史的根提交是重建出来的，之前的提交都已不存在 ——
**别把「历史很短」当成仓库刚建**。剥离的原因是旧历史里有第三方真实数据（群名/群号、真实昵称、
NT UID），且**多条提交信息里也写着**。完整旧历史在仓库外
（`../memex-history-backup-2026-10-12.bundle`）。**以后往仓库写任何来自真实数据的内容，
先脱敏再提交** —— 标识一进 git 就跟着每个 clone 走。

## 真机（一加 15T / ColorOS）

- **不要走 Gradle 的自动安装通道**：设备上原本没有此包时会被 ColorOS 拦，挂 ~5 分钟后报
  `Starting 0 tests` + `Failed to install split APK(s)`。正确流程：`assembleDebug` → `adb install -r`。
- **`adb install -r` 会摘掉已启用的无障碍服务**，每次装完重新启用：

```powershell
adb shell settings put secure enabled_accessibility_services com.tappet.app/.accessibility.TappetAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

- `pm grant` / `appops set` 会被 ColorOS 加固拦截（uid 2000 无权限），别反复试；App 只需要 `INTERNET`。
- **无障碍只能用户自己开**（`WRITE_SECURE_SETTINGS` 是系统签名权限，Android 有意不允许应用自授权；
  `adb shell settings put` 那条是 shell 权限，与应用无关）。应用侧的引导在 `AccessibilityStatus` +
  聊天页提示卡 + 设置页「无障碍（执行层）」一段：**只有真的有一次设备动作因此失败过**才提示
  （纯聊天不打扰），码走 `BridgeHealth`，不去匹配中文文案。
- ColorOS 首次启用 / 重装后弹「保持开启」安全弹窗（3 秒倒计时），不点可能被关闭；
  也会 defer 后台广播 —— 广播前先把 App 拉到前台（`monkey`）。
- `uiautomator dump` 会顶掉无障碍服务约 1 秒，用 adb 自动化时避开。
- logcat 关键 tag：`TappetDevice`（执行层）、`TappetNode`（Node 宿主）、
  `TappetAudit`（**审计标记**，机器读的 key=value，`tools/audit` 按它过滤，改名要同步 `AUDIT_TAG`）、
  `NODEJS-MOBILE`（Node **stderr**；stdout 是协议，不落日志）。

**debug-only 广播入口**（release 不含）—— 不经 UI 触发、可复现的真机验证：

```powershell
# 启动常驻运行时 + SDK initialize（只跑一次；之后可连续任务）
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver `
  -a com.tappet.app.debug.NODE --es op START

# 提交任务（可连续多次，不需要 force-stop）；--ez logPayload true 才打印助手正文
adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op TASK --es task '任务文本'"

# 拉起手机上的 Linux 桥（等价于设置页「启用」；授权弹窗那一步仍要用户点）
# 结果看 logcat 的 TappetNode: termux bridge: <状态>
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver `
  -a com.tappet.app.debug.NODE --es op TERMUX

# 直接驱动执行层原子动作（目标 App 在前台时用）
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver `
  -a com.tappet.app.debug.ACTION --es action READ_SCREEN

# 视觉路径：取一帧，再按帧的图像像素点 —— 这一对是坐标换算的确定性判据
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver `
  -a com.tappet.app.debug.ACTION --es action CAPTURE_FRAME
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver `
  -a com.tappet.app.debug.ACTION --es action TAP_TARGET `
  --es frame_id f1 --ei x1 400 --ei y1 1000 --ei x2 543 --ei y2 1047
```

**帧契约怎么验**（2026-10-17 起，尚无真机记录 —— 验完把结论写回
`docs/android-accessibility.md` §9）：

1. **换算**（确定性，不依赖模型）：`CAPTURE_FRAME` 的 summary 应报「图像 943×2047｜屏幕 1080×2344」；
   对某个已知元素 `TAP_TARGET`，logcat 里换算出的屏幕坐标要落在该元素的 `getBoundsInScreen` 内
   （用 `READ_SCREEN` 复核）。建议先开「开发者选项 → 指针位置」，点击坐标肉眼可见。
   再故意用过期 `frame_id` → 应报 `STALE_FRAME`；`x2` 超出图像宽 → 应报 `OUT_OF_FRAME`。
2. **动作生效**：对会翻页/开新页的按钮 `TAP_TARGET`，要求 `changed=true` 且页面确实变了；
   `NO_VISIBLE_CHANGE` 只说明「看着没变」，不判失败。
3. **空树端到端**：微信已在后台运行时提交「打开微信，点开第一个会话」，工具序列应是
   `capture_frame → tap_target`（不再是「读屏 1 个节点后停下请用户强停」）。
4. **回归**：系统设置这类可读树的 App 仍应走 `read_screen + tap_node`，不该掉进视觉路径。

带空格的参数必须把整条 `am broadcast` 再用 `adb shell "..."` 包一层引号，否则会被设备 shell 拆词。

## 提交纪律（2026-10-03 用户约定；2026-10-18 改为 PR 流程）

- **完成一个可验证的里程碑时自行提交**，不要攒大改动等用户开口。判据：构建 / 单测通过，
  或真机验证完成，即提交一个内聚的 commit。
- **master 已开启分支保护，改动走 PR**：从 master 拉分支 → 提交 → 开 PR → CI 三项检查
  （静态检查 / 单元测试 / 离线自检）通过 → 合并。保护是 GitHub **Ruleset**（不是旧版 branch
  protection）：禁删除 / 禁 force push / 要求 PR / 要求三项 CI 且 strict。**仓库管理员被显式
  列为 bypass actor（`bypass_mode: always`），可以直推 master** —— 这是个人项目的刻意选择；
  非 admin 的贡献者仍会被远端拒绝。`v*` tag 的 release 不受影响。
  仓库默认分支叫 **master**（不是 main）。
- **合并 PR 与 push 一样，必须由用户逐次明确授权**：「改完就提交」是默认动作，
  「开 PR / 合并 / 推上去」不是。
- 提交信息沿用仓库风格：中文 conventional commit（`feat(android): ...`、`docs: ...`、
  `fix(repo): ...`），正文写清改了什么与**验证方式**；不含密钥与个人数据。
- 未验证的半成品不提交；提交后保持工作区干净、可解释。
- 历史已经公开：一旦推过，重写历史就只剩「重写 + 强推」这条既难看又有代价的路。
  推论：任何**不该公开的字面量**（真实标识、密钥、本机路径）只要在 push 前发现，就地重写，别攒着。

## 仓库结构

```
android/                        唯一运行时（Kotlin / Compose）
  app/src/main/java/com/tappet/app/
    agent/                      会话状态 + DshStreamer（dsh 事件流→聊天）+ LlmPreset（提供商目录）
    accessibility/              AccessibilityService（执行层实现）
    bridge/                     DeviceBridgeServer / ConnectorBridgeServer（两个本地 HTTP 桥）
    connector/                  云端数据源连接器：OAuth（PKCE + 回环）+ SecretVault 凭据 + 只读边界
    data/                       Room + SQLCipher 持久化、Keystore 口令；crypto/SecretVault 存用户填的 key
    device/                     通用设备契约：DeviceAction / DeviceBridge / OemProfiles
    node/                       NodeRuntime：解压 dsh 运行时、注入 env、JNI 启动
    ui/                         Compose 界面（聊天 / 首页 / 设置）
  app/src/main/cpp/             JNI（node::Start + stdout 转发到 logcat）
  app/src/test/                 JVM 单测      app/src/androidTest/  真机测试
tools/
  fetch-libnode.ps1             拉 pinned libnode + 从 NDK sysroot 复制 libc++_shared 到 jniLibs
  bundle-dsh.ps1                装配 vendored dsh → assets/dsh-runtime.zip（含 profile 校验与模型模态索引）
  check-sensitive.ps1           发布前自检（工作树 + 全历史 + 可选 APK）
  audit/                        任务审计回路（取证层；判断交给 tappet-auditor agent）
  termux/setup.sh               一键装配手机上的 Linux（Termux 侧依赖 + Debian rootfs + 桥）
  termux/bridge.py              桥本体：127.0.0.1 + token，把「跑一条命令」暴露成 HTTP
  dsh-phone/launcher.mjs        绕开 dsh boot()/CLI loader 的启动器（含按需 reload：卸载 cordis 根后 remount）
  dsh-phone/zen-session-fetch.mjs  launcher 的兄弟模块：只对 zen 主机补 x-opencode-session（有单测）
  dsh-phone/preset-diff.mjs     组合对比：把 phone profile 实际 mount 的行与 dsh 官方预置比一遍（移植用）
  dsh-phone/test/               工具的离线单测（CI 的「离线自检」跑）
  dsh-phone/patch-runtime.mjs   Android 适配补丁（flock 放行、jsonl 发布改 rename、assistant-stream → session.stream）
  dsh-phone/check-profile.mjs   profile 的真解析器校验（bundle 与 CI 都跑）
  dsh-phone/modality-index.mjs  从 pi-ai 目录生成 assets/model-modalities.json（bundle-dsh.ps1 调用）
  dsh-phone/rpc-probe.mjs       PC 侧探针：以 SDK client 身份直连 launcher 的 JSON-RPC（验 initialize 与事件通道）
  dsh-phone/android-attachments/  附件存储的纯 JS 实现（替掉依赖 sharp 的 attachment-local）
  dsh-phone/plugins/android-tools/  git submodule（独立公开仓库，10 个模型可见工具）
  dsh-phone/plugins/gmail/      邮箱连接器工具（只读 3 个；令牌走本地连接器桥）
  dsh-phone/plugins/user-questions/  ask_user_question 的 answerer（问题经本地桥进 UI）
  dsh-phone/profile/            phone profile（cordis.patch.yml）
docs/                           深度文档 + releases/<tag>.md（每个版本的发布说明）
```

`libnode.so`、dsh 运行时（`assets/dsh-runtime.zip`、`libnode/`、`jniLibs/`）都**不入库**，
由 `tools/` 脚本按 pinned 版本装配（在 `.gitignore` 内）。

## 常见坑速查

| 现象 | 原因 / 处理 |
|---|---|
| 全新安装 Gradle 挂 5 分钟后失败 | ColorOS 拦 `adb install` 通道 → 手动 `adb install -r` |
| 装完读屏失效 / 启用无障碍又变 null | `install -r` 摘掉无障碍服务 → 等进程起来后 `settings put secure` 重新启用 |
| `nativeOpen` UnsatisfiedLinkError | 忘加载 SQLCipher → `TappetDatabaseProvider` 显式 `loadLibrary` |
| `dlopen failed: libc++_shared.so not found` | 重跑 `tools/fetch-libnode.ps1`；确认文件**确实落在** `jniLibs/<abi>/`（脚本有自检，别只看它打印成功） |
| APK 里有 libnode.so 但没有 libc++_shared.so | AGP 不会自动打包 STL，jniLibs 是唯一来源 → 同上；`assemble.yml` 会拦住 |
| release 包里 `assets/dsh-runtime.zip` 其实是 tar | 打包工具写不了 zip（GNU tar 不支持）→ 用 .NET `ZipFile`；打完必须验头四字节是 `PK` |
| 任务结束 App 崩溃（FORTIFY/SIGABRT） | 内嵌 Node 调了 `process.exit()`（含 SDK 的 `shutdown`）→ 只记录、不退出 |
| 广播 TASK 后 App 被 ANR 杀 | 别用 `goAsync` 等任务完成（Broadcast Timeout 60s）→ 广播立即返回，任务异步跑 |
| 广播 START/TASK 没反应 | ColorOS defer 后台广播 → 先 `monkey` 拉起 App 再广播 |
| JSON-RPC 偶发不响应 | stdout 混入了日志 → phone profile 不得挂 stdout logger；诊断一律 stderr |
| `adb shell am broadcast --es task "a b"` 丢参数 | 整条命令再包一层引号：`adb shell "am broadcast ... 'a b'"` |
| dsh 启动报 `node-addon-require-builtin` | 用了 `boot()`/loader → 改用 `launcher.mjs` |
| dsh 启动报 `bad indentation of a mapping entry`，**App 直接崩** | profile 里 `!!js` 表达式写了对象字面量（YAML 的 plain scalar 不能含 `": "`）→ 整个表达式加双引号；`check-profile.mjs` 提前拦 |
| dsh 报 `no adapter registered for provider "xxx"` | `NodeRuntime.PROVIDER` 与 profile 里的 provider 键不一致 → 两处一起改 |
| 装了 `rememberViewModelStoreNavEntryDecorator()` 后启动即崩 | Nav3 1.0.1 要求配套的 `SavedStateNavEntryDecorator`（该版本没有）→ 别装它；VM 用 Activity 作用域 + `viewModel(key = ...)`（提供商详情即如此，按 providerId 区分） |
| 换服务商后第一次请求 404/400 | 端点、调用风格、模型没成套（Anthropic 是 `anthropic-messages`，不是 OpenAI 兼容层） |
| 手填模型 id 报 `UNKNOWN_MODEL` | profile 没拿到 `TAPPET_LLM_MODELS`（退回了静态 fallback）→ 动态清单必须含选中模型；`ProviderConfig.llmModelsJson` 有单测 |
| 模型不读图 / 被声明成纯文本 | `assets/model-modalities.json` 缺失或没有该 id → 跑 `bundle-dsh.ps1`；索引里没有的 id 按 text 声明（安全方向），不要手改生成物 |
| 端点模型列表拉取报 HTTP 4xx | 密钥与端点不匹配 / 该端点不提供 `/models` → 手填模型 id；列表只是辅助，不影响手填 |
| dsh 报 `provider "tappet" has an empty baseURL`，App 崩 | 启用的自定义提供商端点被清空 → `toAgentConfig` 会回落目录默认并清空 key；若仍出现，检查 `current()` 的 `canEnable` 分支 |
| 设置里清掉 key / 停用后，当前进程仍能发请求 | 密钥 env 只在 Node 启动时读入；换 key 需重启 App。模型 / 提供商切换走 reload，立即生效 |
| 切换模型后模型看不到之前的对话 | 设计如此：reload 换 dsh 会话命名空间，界面历史保留但模型上下文重新开始（SDK 只有 create 无 resume） |
| 重载超时 / 重载后 initialize 失败 | 看 `NODEJS-MOBILE` 的 `[launcher] reload ...` 与 `runtime.ready`；超时 60s 后 `TappetNode` 会报重载失败 |
| 提供商列表显示「缺 Key」 | 该提供商没有密钥；启用 / 选中模型都要求先保存 key（否则会把正在用的那家停掉、App 变成未配置） |
| 装完包跑任务报缺 API key | 密钥由使用者自填：聊天页抽屉 → 设置（构建期不带密钥，设计如此） |
| 设置页保存了却没生效 | 提供商 / 模型在聊天页切换时重载生效；端点、密钥等改动在下次启动或下次切换重载后生效 |
| 设置页显示「已配置」但请求 401 | 那把 key 与所选服务商不匹配 → 换预选或重填 key |
| dsh 会话写文件报 flock / link 错误 | 未打补丁 → 跑 `patch-runtime.mjs`（flock 放行、jsonl 改 rename） |
| dsh 报 `session ... already exists`（-32603） | 进程重启后复用磁盘会话 id；dsh SDK 只有 create 无 resume → `NodeRuntime` 的 runtimeTag 后缀别去掉 |
| 内嵌 Node 编译失败 `unknown type name 'concept'` | Node 24 头文件要 C++20 → `CMAKE_CXX_STANDARD 20` |
| 读屏没有一个可读节点（微信） | 该 App 对无障碍隐藏控件树（只在「触摸探索」为真时启动的进程里暴露，而任务期不再开探索）→ 走视觉路径，由 `EMPTY_TREE_HINT` 引导 |
| 用户开着 TalkBack 时点击/滑动全部无效 | 全局触摸探索恒为真，探索模式吞掉 `dispatchGesture` 的手势 → 走 `performAction` 语义动作（服务里已自动切换，这些分支不能删） |
| 任务期间用户点屏幕变成「双击才生效」 | 触摸探索被开着才会 → 任务路径不再自动开；只有 debug 广播手动开了才可能出现 |
| 按图点击总是偏左偏上约 12.6% | 截图缩到 2048 长边、`tap_point` 收屏幕像素，两套坐标系没换算 → 模型侧一律用帧的图像像素（`tap_target`），换算在 `ScreenFrame.toScreen` |
| `tap_target` 报 `STALE_FRAME` | 帧是有状态的：界面变了旧帧作废 → 重新 `capture_frame` 再给坐标（不是 bug） |
| `tap_target` 报 `OUT_OF_FRAME` | bbox 越出图像范围，**故意不 clamp** → 让模型重给坐标，别改成夹取 |
| `tap_target` 回「界面似乎没有变化」 | 8×8 指纹的启发式：小面积变化（勾选框、行高亮）它看不出来 → 只能读成「可能没点中」，不是「点击失败」 |
| 长视觉任务请求报 `IMAGE_OFFLOAD_REQUIRED` | 请求图像预算 20MiB 吃穿 → profile 里 `image-offload` 已启用（丢最老的图重试）；它**永久丢帧**，模型之后拿不到那张图 |
| 树不可读时 `type_text` 失败 | 没有控件树就没有输入框可写 —— 视觉路径**不解决输入**（自建 IME 见 roadmap §2.5），如实告知用户 |
| 截图报 `Could not load the "sharp" module` | 生效的是 dsh 自带的 `attachment-local`（依赖 sharp，无 Android 产物）→ profile 里禁用它、挂 `dsh-android-attachments` |
| 模型侧 `tools: []`、只能对着 persona 猜工具名 | 工具 schema 编译失败 → `defineTool` 抛错会**整个插件的工具全丢**；用插件仓库 `npm test` 里的 `real-schema.mjs` 在 PC 侧拦住 |
| 拉 dsh 会话文件解压失败 | `adb shell cat` 会把 `\n` 翻成 `\r\n` → 用 `adb exec-out run-as com.tappet.app cat ...`；会话是一帧一条记录，需按 RFC 8878 手工切帧后逐帧解 |
| 模拟器上 App 起不来 / 缺 libnode | 只装配了 arm64 → `fetch-libnode.ps1 -Abi x86_64` + `-Ptappet.abis=x86_64` |
| 推送被拒 `GH013 Repository rule violations` | 先分清是 PR 规则还是 **push protection**（两者措辞一样）→ 后者是 GitHub 认为提交里有密钥，点它给的 unblock 链接或把值改成构建期注入 |
| 发布出的包连不上 Gmail | `release.yml` 的 `TAPPET_GMAIL_CLIENT_ID/SECRET` 两个 secret 没配 → 构建期注入是空的，包里就没有预置值 |
| 授权报 `client_secret is missing` | Google 文档把这项标「Optional」而令牌端点实际要求 → 必须发（桌面应用类型与 Android/iOS 不同） |
| 同意页写着「读取、撰写和发送你的邮件」 | 正常：申请的是 `gmail.modify`（一次要齐，避免以后重授权），但工具面目前只有只读三个 |
| 没开代理时邮件工具报「未授权」 | 已经修了：令牌请求的网络失败现在报 `NETWORK`（连不上 Google 的授权服务），不再被 `.orEmpty()` 吞成空响应、当成授权失效。见 `GmailAuth.postForm` 的注释 |
| 邮件工具回「还没有连接 Gmail」 | 连接器未授权，或 refresh token 已失效被清 → 设置 → 连接器 → Gmail 里重新连接 |
| 邮件工具回「还没有配置 Gmail」 | 没填客户端 ID → 填 Google Cloud 的 OAuth 客户端 ID（类型必须是**桌面应用**）；步骤见 `docs/email-connector.md` §4.2 |
| 授权报 `redirect_uri_mismatch` | OAuth 客户端类型不是「桌面应用」（回环地址只对它开放）→ 重建凭据 |
| 浏览器点完同意后 App 没反应 | 授权全流程真机已跑通；遇到时先看 stderr 里回环监听器有没有收到请求（部分浏览器拦 `127.0.0.1`）。另：授权期间 App 进程被杀就得重来（最长 10 分钟持有 socket） |
| 连上后过一阵又变成未连接 | refresh token 失效（改密码 / 用户在 Google 账号页撤销 / 测试模式下超过 7 天）→ 重连；`invalid_grant` 时插件会主动清凭据 |
| 邮件工具整个不见了 / dsh 起不来 | 插件名三处不一致（profile 的 insert / `bundle-dsh.ps1` 的 Package / 插件 `package.json`）→ 有单测钉住；改完重跑 `bundle-dsh.ps1` |
| 输入框快速打字丢字符 | 每次击键同步 `commit()` 落盘（主线程 fsync）→ 频繁写的字段用 `apply()`，只有需要「写进去没有」答案的凭据才用 `commit` |
| 点了按钮看不到任何反馈 | 错误文案渲染在页面底部、落在屏幕外 → 反馈贴着触发点放 |
| 想确认邮件正文没进日志 | `adb logcat -s TappetConnector TappetAudit` —— 只应有动作名与失败码，不该有主题 / 发件人 / 正文 |
| Gradle 代理不生效 | 行内 `-D` 被 gradlew.bat 拆坏 → 放 `GRADLE_OPTS` 环境变量 |
| `Task '.abis=x86_64' not found` | PowerShell 把 `-Ptappet.abis=x86_64` 拆成两个参数 → 加引号：`"-Ptappet.abis=x86_64"` |
| 装到手机上 `dlopen libc++_shared.so` 失败（刚跑过模拟器） | jniLibs 停在 x86_64 → `tools/fetch-libnode.ps1` 切回 arm64 再出包 |
| `checkDebugAarMetadata` 报某依赖 `requires ... compileSdk 37` | 该依赖要求的最低 compileSdk 高于本项目（36）与 AGP 上限（9.0.1 最高推荐 36）→ **不能单独升它**，得连 AGP + compileSdk 一起迁移。okhttp 5.x 就是这种情况（只被已暂停的客户端用着，所以当初没升） |
| 助手回复整段蹦出来、没有逐字效果 | 运行时补丁 4 没生效（stdout 上没有 `session.stream` 帧）→ 重跑 `tools/bundle-dsh.ps1` 并重装 APK；Kotlin 侧另有 50ms 合并，纯聊天不涉及无障碍 |
| 想确认发布物里没有密钥 | `tools/check-sensitive.ps1 -ApkPath <包>`（或 `strings` 扫 `classes*.dex`，grep 到空） |
| `web_search` 回 `WEB_PROVIDER_UNAVAILABLE` | 当前提供商没有搜索端点（`LlmPreset.Endpoint.search == null`）→ 换 OpenCode Go，或在 `Endpoint.search` 里补一个**实测过**的 |
| `web_search` 报 400 `MissingSessionID` | zen 的会话头没补上 → 确认 `zen-session-fetch.mjs` 进了运行时（重跑 `bundle-dsh.ps1`；它和 `launcher.mjs` 必须一起拷进 cache） |
| 装完启动报 `Cannot find module .../zen-session-fetch.mjs` | 同上一类：launcher 的兄弟模块漏拷 → 重跑 `tools/bundle-dsh.ps1` |
| 搜索回了答案但一个来源都没有 | **真搜门**：模型收下了 `web_search` 但一次都没搜（网关不校验）→ 换实测会搜的模型（zen 上是 claude 系 / gpt-6-luna / grok-4.x） |
| `web_search` 报 404 且路径里有两个 `/messages` | 搜索基址写成带 `/messages` 的了 —— provider 自己会追加，基址只给到 `/v1` |
| 模型调了 `ask_user_question` 之后整轮卡住、会话一直 running | 没有 answerer 接手（或 answerer 排在 `dsh-api-remotes` 后面被它吃掉 —— 它把请求推进没人消费的 remote 队列）。我们的 answerer 用 `{ prepend: true }` 注册；见 `docs/user-questions.md` §2.2 |
| 装完 App 起不来，报 `ERR_MODULE_NOT_FOUND: Cannot find package '@deepseek-ai/cordis'` | **装配脚本是用 Windows PowerShell 5.1 跑的**：它的 .NET Framework 把 zip 条目名写成 `\`，设备侧解不出目录树（解出来是 `node_modules\.bin\cordis` 这种扁平文件名）→ 用 **`pwsh` 7** 重跑 `bundle-dsh.ps1`（脚本末尾现在会自检并 throw） |
| 模型说「没有 web_search 这个工具」 | profile 里的 `tool-web` 行被 `dsh-web-app` 禁用了 → 必须在**宿主层** `- id: tool-web` + `disabled: false`；放进 preset 的 `plugins:` 里不管用 |
| `tool-fs-search`（`glob` / `grep`）报 `ripgrep provider failure` | 三个坑叠在一起：① `@vscode/ripgrep` **没有 android 产物**（12 个 optional dep 只有 darwin/win32/linux）→ 我们交叉编译一份并用 `ripgrep-stub` 顶掉它（`.github/workflows/rg.yml` + `tools/fetch-rg.ps1`）；② 该插件 inject `subprocess`，而 `dsh-subprocess-local` 被禁了（它的 koffi/node-pty 是懒加载的，Android 上**可以**开）；③ **必须 `useLegacyPackaging = true`** —— Android 10+ 的 W^X 不许应用执行自己可写目录里的文件，而 AGP 默认把原生库留在 APK 里 mmap，`nativeLibraryDir` 是空的。别被 `run-as …/rg --version` 骗了：那能跑是因为 run-as 的 SELinux 域还是 shell |
| 想知道「dsh 官方预置有什么、我们缺什么」 | 用 launcher 同一套 API 把组合打出来比：`loadProfileDirectory` + `readProfilePatches` + `composeEntries`，然后对比 `preset-standard` 与 `preset-phone` 的 `config.plugins`（现成脚本可照 `docs/memory.md` §3 写，10 行） |
| 设置页「Linux 环境」一直停在「正在启动…」 | Termux 被 ColorOS 冻了（端口在但连不上）→ 把 Termux 拉到前台，并在系统设置里放开它的后台运行 |
| 启动器报 `NO_PERMISSION`（明明授权过） | `adb install -r` 清掉了 RUN_COMMAND 授权 → **重装用 `adb install -r -g`**，或重新点一次「授权并启用」 |
| 桥的启动脚本「跑了但什么也没发生」（token 写了、日志没动、python 没起） | 多半是脚本被 `pkill -f` 自杀了（脚本文本在 argv 里）→ 别用 `pkill -f`，扫 `/proc` 并跳过自己 |
| 脚本里 `python: command not found` 之类看不见 | RUN_COMMAND 的 stdout/stderr 没人接管 → 输出重定向到 `~/bridge.log` 再看 |
| `run_command` 不出现在工具表里 | 没生成 token（未启用）或 env 没注入 → 设置页点「启用」后**重启 App** |
| 桥在跑但每条命令都 401 | 是别的 token 起的桥（比如手动跑过 setup.sh）→ 点「重新连接」，让它按 pidfile 换掉 |
| `run_command` 报连不上 | Termux 里的桥没在跑 → 打开 Termux 执行 `python ~/bridge.py`；日志在 `~/bridge.log` |

## 深度文档

| 文档 | 什么时候读 |
|---|---|
| `docs/dsh-android.md` | 动 agent runtime、内嵌 Node、dsh 装配之前（含各阶段实测结论） |
| `docs/android-accessibility.md` | 动执行层、换设备 / ROM 之前（系统探测与四问实测记录） |
| `docs/email-connector.md` | 动连接器（邮箱等云端数据源）之前：边界、隐私闸门、OAuth 注册步骤 |
| `docs/web-search.md` | 动联网搜索之前：为什么不用搜索 API、两道门（协议门 / 真搜门）的实测、隐私闸门 |
| `docs/memory.md` | 动记忆 / 上下文注入之前：dsh 的三件机制（指令文件 / `@file` / `@session`）、**移动端移植缺口清单**（preset 组合 diff 实测） |
| `docs/user-questions.md` | 动 `ask_user_question` / 任何「模型等用户回答」的功能之前：waterfall 的注册顺序坑、答案的三条语义、为什么不能只开工具不做 UI |
| `docs/roadmap.md` | 想推进方向时（**未验证的假设**，不是承诺） |
| `docs/releases/<tag>.md` | 发版说明的写法与历史 |
| `README.md` | 对外介绍；`CHANGELOG.md` 记对外可见的变化 |

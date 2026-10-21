<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/tappet_mascot.jpg" width="120" alt="Tappet 吉祥物">
</p>

<h1 align="center">Tappet</h1>

<p align="center">
  <em>运行在 Android 上的通用 mobile agent：LLM 通过工具调用操作手机界面完成任务，不做单一 App 的专用适配。</em>
</p>

<p align="center">
  <a href="https://github.com/asdukw/tappet/actions/workflows/ci.yml"><img src="https://github.com/asdukw/tappet/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/asdukw/tappet/releases"><img src="https://img.shields.io/github/v/release/asdukw/tappet" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · 简体中文
</p>

Tappet 把手机交给模型：读屏、点按、长按、输入、滑动、启动应用 —— **10 个通用 UI 原语**，
模型自己组合它们完成「打开计算器算 37×48」这类任务，而不是给每个 App 写一套脚本。

agent 运行时是内嵌在 App 里的 Node 24 + dsh phone profile；执行层走无障碍服务
（控件树优先、截图兜底），两者经本机回环上的 HTTP 桥连接。

v0.5.0 起它多了**第二条获取信息的通道**：连接器（首版 Gmail，只读），不经过无障碍，
直接读用户自己云端账号里的数据。

> [!IMPORTANT]
> 装完要做三件事才能用：**填自己的 LLM API key**（设置 → 提供商，预选 OpenAI / Anthropic /
> DeepSeek / OpenCode Go / OpenRouter，也可自定义端点）、**开启无障碍服务**（只能由你手动开）、
> **（可选）连接 Gmail**。哪些路径真机验证过、哪些只是「配置对得上」，看下面的状态表。

## 特性

- **10 个 UI 原语**：`read_ui_tree`、`tap_node`、`long_press`、`capture_screenshot`、
  `tap_target`、`long_press_target`、`type_text`、`press`、`launch_app`、`swipe`。
  模型看到的是工具，执行的是 App。
- **视觉兜底**：对无障碍隐藏控件树的 App（实测微信）改走截图。模型给出图像像素里的框，
  `ScreenFrame.toScreen` 是唯一的换算点。截图有状态，越界框直接拒绝，不做夹取。
- **不切输入法也能打字**：Android 13+ 走无障碍自带的文本注入通道，控件树不可读时也能输入；
  控件树可读时仍然优先走语义路径。
- **自带模型**：5 个预选 + 自定义端点。密钥存在 Keystore 封装的 `SecretVault` 里，
  Node 启动时以环境变量传入 —— 不落盘，也不编进 APK。
- **本地语音**：语音输入用 sherpa-onnx + Paraformer，由 silero VAD 切段；标点模型跟着一起装。
  两个模型随安装自动下载，设置页里没有入口。朗读可用系统 TTS 或本地 VITS 音色。
  音频不出设备、不落盘。
- **连接器**：Gmail 只读 —— 列邮件 / 检索 / 读正文。连接器桥与无障碍分离，
  无障碍关掉时邮件工具照常可用。
- **联网搜索**：`web_search` / `web_fetch` 走模型服务商自带的服务器端搜索 ——
  不新增厂商、不新增凭据；网页正文标记为**外部不可信数据**。
- **agent 配套能力**：`ask_user_question`、长期记忆、文件读写 / 编辑与 `glob` / `grep`、
  待办、每天执行的定时任务、系统 intent 辅助工具 `set_alarm` / `add_calendar_event`，
  以及支持续传与签名校验的应用内更新。
- **落盘加密**：聊天记录在 Room + SQLCipher 里，数据库口令由 Keystore 封装。

## 状态

Tappet 对「验到什么程度」说得比较直白：**单测通过 ≠ 真机可用**。

| 能力 | 状态 |
|---|---|
| 聊天界面、持久化、无障碍执行层 | 真机验证（一加 15T / ColorOS） |
| 内嵌 Node 24 + dsh 运行时、连续多轮任务 | 真机验证 |
| LLM 接入 | 只有 OpenCode Go 用真 key 跑通过；其余预选与运行时契约对得上，但还没真正发过请求 |
| 视觉路径 | 坐标换算有单测钉住；模型在图上估坐标准不准尚未真机验证 |
| 语音输入 | 真机验证（VAD 切段与 Paraformer 识别；标点模型跟随安装） |
| 朗读 | 真机验证（系统 TTS、本地 VITS、自动朗读） |
| Gmail 连接器 | 真机验证（完整 OAuth 流程 + 真实读到邮件，仅一个账号） |
| 联网搜索 | 真机验证（仅 OpenCode Go） |
| 记忆、文件工具、`ask_user_question` | Android 16 模拟器端到端验过；尚未真机验证 |
| 定时任务 | 单测通过；到点触发、开机重排、权限降级尚未真机验证 |
| 闹钟与日程 | 模拟器验证预填；尚未真机验证 |
| 应用内更新 | 模拟器验证下载、取消与签名校验；续传有单测与部分模拟器验证 |

## 快速开始

### 安装现成包

[Releases](https://github.com/asdukw/tappet/releases) 里有签名 APK、`.sha256` 与发布说明。
目前只出 `arm64-v8a`。

```bash
adb install -r tappet-v0.13.0-arm64-v8a.apk
sha256sum -c tappet-v0.13.0-arm64-v8a.apk.sha256   # 可选：确认包没被换过
apksigner verify --print-certs tappet-v0.13.0-arm64-v8a.apk
gh attestation verify tappet-v0.13.0-arm64-v8a.apk --repo asdukw/tappet
```

`adb install -r` 会摘掉已启用的无障碍服务，装完重新开一次即可。

APK 由 CI 在 `v*` tag 上用 release keystore 签名，并附带 GitHub build-provenance；
`gh attestation verify` 可以确认它确实由本仓库的 release workflow 构建。密钥不参与构建，
包内不含、也不可能含编译进去的 API key。

> [!NOTE]
> ColorOS 首次启动可能弹「保持开启」对话框，建议同意，否则系统可能冻结进程。
> 无障碍服务只能由用户手动开启。

### 从源码构建

需要 **JDK 21**、PC 上的 **Node 24 + npm**，以及 Android SDK（**compileSdk 36**）、
**NDK 29.0.14206865**、**CMake 3.31.6**（后三个用 `sdkmanager` 装）。

```powershell
git submodule update --init --recursive   # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so
.\tools\fetch-sherpa.ps1                  # 本地 TTS native 库 + silero VAD 模型
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip

$env:JAVA_HOME = '<JDK 21 的安装路径>'
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- `.ps1` 脚本请用 **`pwsh` 7**，不要用 Windows PowerShell 5.1：5.1 写出的 zip 条目名带反斜杠，
  设备上解不出来。
- 装配脚本按 pinned 版本现拉运行时，产物不入库；网络走 `HTTP_PROXY` / `HTTPS_PROXY`。
- 跑模拟器要先取 x86_64 的 libnode，并用 `"-Ptappet.abis=x86_64"` 构建，
  **用完切回 arm64**。
- **真机安装不要走 Gradle 的自动安装通道**：ColorOS 会拦全新安装，挂几分钟后失败。
  正确流程是产包后手动 `adb install -r`，再重新启用无障碍。

## 工作原理

```
Compose UI ── Room/SQLCipher 落库
    │  session/prompt
    ▼
内嵌 Node 24（宿主机进程内单实例）
    └─ dsh phone profile
         ├─ dsh-plugin-android-tools（10 个 UI 原语 + 系统 intent 辅助工具）
         │    │  HTTP POST /action（127.0.0.1 + 每进程随机 token）
         │    ▼
         │  DeviceBridgeServer（App 内）
         │    ▼
         │  AccessibilityService：控件树优先、截图兜底
         │
         └─ dsh-plugin-gmail（连上邮箱后另有 3 个只读邮件工具）
              │  HTTP POST /action（127.0.0.1 + 每进程随机 token）
              ▼
            ConnectorBridgeServer（App 内）—— 只发一个短命的 access token
              ▼
            Gmail API（用户自己的邮箱，只读）
```

连接器这条**不经过无障碍**：无障碍被关掉时设备工具全失效，而邮件工具照常可用。
代价是它是仓库里第一条直接读云端个人数据的通道，所以边界与隐私闸门单独写了一份：
[docs/email-connector.md](docs/email-connector.md)。

几个刻意的选择：

- **不在设备上装插件**：工具经本机回环桥落地，插件源码在独立公开仓库。换 ROM 只加一个
  `OemProfile`，不写 App 专用逻辑 —— QQ / 微信只是试验任务的目标 App。
- **stdout 是协议独占**：`session.event` / `session.status` 走 stdout，诊断一律 stderr，
  否则 JSON-RPC 会被日志污染。
- **配置切换走运行时重载，不是重启进程**：切提供商 / 模型时宿主写一份**非密**的运行时配置，
  让 launcher 卸载整棵 cordis 根后重新 mount（实测约 0.2–1 秒，同一 pid），密钥不落盘。
  只有**换 API key** 需要重启 App —— key 只在 Node 启动时读入 env。
- **每轮只做一个原子动作**：工具描述统一要求「先读屏、操作后再确认」，避免模型连着盲点。

## 隐私与密钥

> [!NOTE]
> 这些不是「以后会做」的承诺，是已经落地的结构性事实 —— 相关的采集管线
> （QQ / 微信聊天记录、知识图谱、通知探针）已从代码与仓库历史里整体移除。

- **密钥只有一个来源**：设置页 → `SecretVault`（Android Keystore 封装）。密文落盘、
  硬件封装不可导出、不进云备份与设备间直传、不回填界面、不进日志，**也不会被编进 APK**。
- **屏幕与聊天正文不落日志**：日志只记计数 / 类名 / 坐标 / 错误码；读屏内容只出现在
  给模型的工具结果里。发往云端的是**你自己配置的那个端点**。
- **视觉路径的感知面更大**：树不可读时改走截图，交给模型的是**整屏图像**（含屏幕上的一切正文），
  比结构化读屏大得多 —— 这一步就是把屏幕内容发给模型服务商。它和读屏 payload 同级：
  只进给模型的工具结果，不落日志、不落盘。
- **邮箱连接器本机不保存邮箱副本**：不落盘、不落日志、不做后台轮询。
  refresh token 进 Keystore 封装的 `SecretVault`，插件只拿短命的 access token，
  从不接触长期凭据。
- **授权面比能力面宽（刻意的）**：申请的是 `gmail.modify`（读 + 写 + 发），而工具面
  **只有只读三个**。理由是 Google 对已安装应用不支持增量授权，扩权要所有已连接用户
  重走同意页，所以一次要齐。代价说清楚：只读 scope 下「插件被攻破也发不出邮件」这条边界
  是 **Google 兜的**，换成 `gmail.modify` 之后只剩**插件里那个唯一的只读出网函数**在守。
  完整取舍见 [docs/email-connector.md](docs/email-connector.md) §1.1。
- **会话库** `tappet.db` 用 SQLCipher 加密，口令由 Keystore 封装：
  **卸载即永久丢失，外部不可解、不可拷**。
- **发布前自检**：`tools\check-sensitive.ps1`（扫工作树 + 全部 git 历史，
  可选连 APK 一起扫；`-SelfTest` 先证明扫描器确实会命中）。

## 开发

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain   # 格式 / 坏味道 / Android Lint
.\gradlew.bat spotlessApply                                        # 自动修格式
.\gradlew.bat :app:testDebugUnitTest                               # JVM 单测
```

- **真机测试**（`androidTest/`，覆盖 Room/SQLCipher 与 Compose UI）：先 `adb install -r`，
  重新启用无障碍服务，再 `.\gradlew.bat :app:connectedDebugAndroidTest`。
- **任务审计回路**：`node tools/audit/run-suite.mjs` 跑固定任务集并自动采集轨迹，
  判断交给 `tappet-auditor` 子 agent。产物（含屏幕正文）按敏感数据对待，不入库。
- 改了 `tools/dsh-phone/` 下的 launcher / profile / 插件，必须重跑 `.\tools\bundle-dsh.ps1`
  并重装 APK 才生效（运行时打包在 assets 里）；插件本身在
  [独立公开仓库](https://github.com/asdukw/dsh-plugin-android-tools)，
  先在那边提交再更新 submodule 指针。

CI 分四个 workflow：`ci.yml`（静态检查 + 单测 + 离线自检）、`assemble.yml`（装配 libnode/dsh
并出包，**只在装配相关路径变动时**跑，且验产物结构）、`codeql.yml`（语义级安全扫描）、
`release.yml`（推 `v*` tag：先过门禁 → 出签名包 → 验签 + 扫包内密钥 → 发 release）。

> [!WARNING]
> **CI 绿 ≠ 能用**。无障碍执行层、内嵌 Node 的启动、真实任务只能在真机上验证。
> CI 不装 NDK（那 ~1.5GB 只有装配与发版两条 workflow 需要）。

## 已知限制

- 只带 **arm64-v8a**（内嵌 Node 运行时就占 65MB，多一个 ABI 体积翻倍）；
  截图能力要求 Android 11+（minSdk 28）。
- 五个预选里**只有 OpenCode Go 用真 key 跑通过**；真机上切换提供商 / 模型并并发任务
  也还没完全验证。
- 部分 App（实测微信 8.0.78）对无障碍隐藏控件树，这时走**视觉路径**。
  Android 13+ 可以通过无障碍文本注入通道输入文字；更低版本仍然输入不了。
- 视觉路径的坐标换算有单测钉住，**模型在图上估坐标准不准还没在真机上验证过**。
- **文件工具不是沙箱**：模型能读写 App 能碰到的全部路径。
- 切换提供商 / 模型会换 dsh 会话命名空间：界面历史保留，但模型上下文从下一条消息重新开始
  （dsh SDK 只有 create、没有 resume）。
- **Gmail 的预置 OAuth 客户端停在 Google 测试模式**：只有白名单里的测试账号能授权，
  且 refresh token 寿命受限（实测 7 天，到期需重连）。别人装这个包想用 Gmail，
  得在设置里换成自己的 OAuth 客户端 ID 与密码。授权期间还要求 App 进程活着
  （最长 10 分钟持有回环 socket），进程被杀就得重来。
- **定时任务、闹钟与日程尚未真机验证**。定时任务只支持「每天一次」；精确闹钟在 Android 12+
  需要单独授权，Android 14 对新装应用默认拒绝。
- 跨 App 长任务依赖前台服务保活，ColorOS 的后台冻结仍有边界情况。
- 项目原名 Memex，2026-10-12 更名（含 `applicationId`）。旧包 `com.memex.app`
  若还在设备上，其数据新包读不到，卸载即可。

## 文档

| 文件 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 给 coding agent 的项目记忆：硬规则、命令、改动入口、完整文档索引 |
| [docs/dsh-android.md](docs/dsh-android.md) | agent runtime 选型与各阶段实测 |
| [docs/android-accessibility.md](docs/android-accessibility.md) | 执行层、视觉路径、文本注入通道 |
| [docs/providers.md](docs/providers.md) | 提供商 / 模型 / 模态 / 运行时路由 |
| [docs/voice.md](docs/voice.md) | 朗读与语音输入：引擎取舍、模型授权、VAD |
| [docs/email-connector.md](docs/email-connector.md) | 连接器的边界与隐私闸门 |
| [docs/web-search.md](docs/web-search.md) | 联网搜索的边界、协议与实测 |
| [docs/app-updater.md](docs/app-updater.md) | 应用内更新：断点续传与校验 |
| [docs/termux.md](docs/termux.md) | 手机上的 Linux：Termux 桥与 `run_command` |
| [docs/releasing.md](docs/releasing.md) | CI 与发版流程、签名材料 |
| [docs/pitfalls.md](docs/pitfalls.md) | 完整排障表 |
| [docs/roadmap.md](docs/roadmap.md) | 技术路线畅想（未验证的假设） |
| [CHANGELOG.md](CHANGELOG.md) | 对外可见的变化 |

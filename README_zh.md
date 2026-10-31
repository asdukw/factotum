<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/zoneu_mascot.jpg" width="120" alt="zoneu吉祥物">
</p>

<h1 align="center">zoneu</h1>

<p align="center">
  <em>运行在 Android 上的通用 mobile agent：LLM 通过工具调用操作手机界面完成任务，不做单一 App 的专用适配。</em>
</p>

<p align="center">
  <a href="https://github.com/asdukw/zoneu/actions/workflows/ci.yml"><img src="https://github.com/asdukw/zoneu/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/asdukw/zoneu/releases"><img src="https://img.shields.io/github/v/release/asdukw/zoneu" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · 简体中文
</p>

zoneu 把手机交给模型：读屏、点按、长按、输入、滑动、启动应用 —— **10 个通用 UI 原语**，
模型自己组合它们完成「打开计算器算 37×48」这类任务，而不是给每个 App 写一套脚本。

agent 运行时是内嵌在 App 里的 Node 24 + dsh phone profile；执行层走无障碍服务
（控件树优先、截图兜底），两者经本机回环上的 HTTP 桥连接。

zoneu 还有第二条获取信息的通道：连接器（首版 Gmail），不经过无障碍，直接读用户自己云端账号里的
数据。此外它带酒馆模式、用于文件操作的内嵌 Debian，以及由模型在对话中创建的定时任务。

> [!IMPORTANT]
> 装完要做三件事才能用：**填自己的 LLM API key**（设置 → 提供商，预选 OpenAI / Anthropic /
> DeepSeek / OpenCode Go / OpenRouter，也可自定义端点）、**开启无障碍服务**（只能由你手动开）、
> **（可选）连接 Gmail**。哪些路径真机验证过、哪些只是「配置对得上」，看下面的状态表。

> **全新应用身份**：`com.zoneu.app` 可与 Factotum 并存，不继承旧应用的数据、凭据或授权；
> 请单独配置和授权 zoneu。历史发布保留原 APK 名称；源码改名不代表已发布新版本。

## 特性

**设备控制**

- **10 个 UI 原语**：`read_ui_tree`、`tap_node`、`long_press`、`capture_screenshot`、
  `tap_target`、`long_press_target`、`type_text`、`press`、`launch_app`、`swipe`。
  模型看到的是工具，执行的是 App。
- **9 个系统工具**：`set_alarm`、`add_calendar_event`、`list_calendar_events`、
  `update_calendar_event`、`delete_calendar_event`，以及 `schedule_task` /
  `list_scheduled_tasks` / `update_scheduled_task` / `delete_scheduled_task`。
  日程走 `ContentResolver` 与闹钟 intent，不去点日历 App。
- **视觉兜底**：对无障碍隐藏控件树的 App（实测微信）改走截图。模型给出图像像素里的框，
  `ScreenFrame.toScreen` 是唯一的换算点。截图有状态，越界框直接拒绝，不做夹取。
- **不切输入法也能打字**：Android 13+ 走无障碍自带的文本注入通道，控件树不可读时也能输入；
  控件树可读时仍然优先走语义路径。
- **手机上的 Linux**：`run_command` 在内嵌 Debian（proot + `bookworm-slim`）里执行，
  `apt`、Python 和原生模块都不需要 Termux 或 root。它属于写操作：默认每条命令都要审批。

**模型与语音**

- **自带模型**：5 个预选 + 自定义端点。密钥存在 Keystore 封装的 `SecretVault` 里，
  Node 启动时以环境变量传入 —— 不落盘，也不编进 APK。
- **语音**：朗读走 Fish Audio 的云端免费模型（`s2.1-pro-free`），key 存在 Keystore 封装的
  vault 里。语音输入完全在本机：sherpa-onnx + Paraformer，silero VAD 切段，标点模型跟着
  一起装；音频不出设备、不落盘。

**连接器、搜索与酒馆**

- **Gmail 连接器**：9 个工具 —— 5 个只读（`mail_list`、`mail_search`、`mail_read`、
  `mail_thread`、`mail_labels`）和 4 个写工具（`mail_modify`、`mail_draft`、`mail_send`、
  `mail_save_attachment`）。每个写操作执行前都过审批面板。连接器桥与无障碍分离，
  无障碍关掉时邮件工具照常可用。
- **联网搜索**：`web_search` / `web_fetch` 走模型服务商自带的服务器端搜索 ——
  不新增厂商、不新增凭据；网页正文标记为**外部不可信数据**。
- **酒馆模式**：角色卡 PNG / JSON / CHARX 导入导出、独立世界书、预设、宏与会话变量、
  候选回答、续写 / 替我写、支线、2–4 人群聊、每张卡独立记忆与前情提要。
  日常会话与酒馆会话分区显示。
- **agent 配套能力**：`ask_user_question`、长期记忆、文件读写 / 编辑与 `glob` / `grep`、
  待办，以及支持续传与签名校验的应用内更新。
- **落盘加密**：聊天记录在 Room + SQLCipher 里，数据库口令由 Keystore 封装。

## 状态

zoneu 对「验到什么程度」说得比较直白：**单测通过 ≠ 真机可用**。

| 能力 | 状态 |
|---|---|
| 聊天界面、持久化、无障碍执行层 | 真机验证（一加 15T / ColorOS） |
| 内嵌 Node 24 + dsh 运行时、连续多轮任务 | 真机验证 |
| 内嵌 Debian（`run_command`） | 真机验证（dash / dpkg / curl 能从 rootfs 起得来） |
| LLM 接入 | 只有 OpenCode Go 用真 key 跑通过；其余预选与运行时契约对得上，但还没真正发过请求 |
| 视觉路径 | 坐标换算有单测钉住；模型在图上估坐标准不准尚未真机验证 |
| 语音输入 | 真机验证（VAD 切段与 Paraformer 识别） |
| 朗读 | 设置页与「未配置 key」路径已真机验证；云端合成还没用真 key 跑过 |
| Gmail 连接器 | 真机验证（完整 OAuth 流程 + 真实读到邮件，仅一个账号）。写工具受审批门控；2026-10-28 有真机验收反馈，但未逐工具留证 |
| 联网搜索 | 真机验证（仅 OpenCode Go） |
| 酒馆模式、世界书 | 已实现并有单测；2026-10-28 有真机验收反馈，但未逐路径留证 |
| 记忆、文件工具、`ask_user_question` | Android 16 模拟器端到端验过；尚未真机验证 |
| 定时任务 | 单测通过；2026-10-28 有本轮改动的真机验收反馈。到点触发、开机重排与权限降级未逐项留证 |
| 闹钟与日程 | 模拟器验证预填；尚未真机验证 |
| 应用内更新 | 模拟器验证下载、取消与签名校验；续传有单测与部分模拟器验证 |

## 快速开始

### 安装现成包

[Releases](https://github.com/asdukw/zoneu/releases) 里有签名 APK、`.sha256` 与发布说明。
目前只出 `arm64-v8a`。

```bash
APK=zoneu-vX.Y.Z-arm64-v8a.apk
adb install -r "$APK"
sha256sum -c "$APK.sha256"   # 可选：确认包没被换过
apksigner verify --print-certs "$APK"
gh attestation verify "$APK" --repo asdukw/zoneu
```

`adb install -r` 会摘掉已启用的无障碍服务，装完重新开一次即可。

APK 由 CI 在 `v*` tag 上签名，并附带 GitHub build-provenance；
`gh attestation verify` 可以确认它确实由本仓库的 release workflow 构建。签名材料不参与构建，
包内不含、也不可能含编译进去的 API key。

> [!NOTE]
> ColorOS 首次启动可能弹「保持开启」对话框，建议同意，否则系统可能冻结进程。
> 无障碍服务只能由用户手动开启。

### 从源码构建

需要 **JDK 21**、PC 上的 **Node 24 + npm**，以及 Android SDK（**compileSdk 37**）、
**NDK 29.0.14206865**、**CMake 3.31.6**（后三个用 `sdkmanager` 装）。

```powershell
git submodule update --init --recursive   # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so
.\tools\fetch-sherpa.ps1                  # 语音输入的 sherpa 库 + silero VAD 模型
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip
.\tools\fetch-proot.ps1                   # run_command 用的 proot + Debian rootfs

$env:JAVA_HOME = '<JDK 21 的安装路径>'
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- `.ps1` 脚本请用 **`pwsh` 7**，不要用 Windows PowerShell 5.1：5.1 写出的 zip 条目名带反斜杠，
  设备上解不出来。
- 装配脚本按 pinned 版本现拉运行时，产物不入库；网络走 `HTTP_PROXY` / `HTTPS_PROXY`。
- 跑模拟器要先取 x86_64 的 libnode，并用 `"-Pzoneu.abis=x86_64"` 构建，
  **用完切回 arm64**。
- **真机安装不要走 Gradle 的自动安装通道**：ColorOS 会拦全新安装，挂几分钟后失败。
  用 `.\tools\install-debug.ps1` 或手动 `adb install -r`，再重新启用无障碍。

## 工作原理

```
Compose UI ── Room/SQLCipher 落库
    │  session/prompt
    ▼
内嵌 Node 24（宿主机进程内单实例）
    └─ dsh phone profile
         ├─ dsh-plugin-android-tools（10 个 UI 原语 + 9 个系统工具）
         │    │  HTTP POST /action（127.0.0.1 + 每进程随机 token）
         │    ▼
         │  DeviceBridgeServer（App 内）
         │    ▼
         │  AccessibilityService：控件树优先、截图兜底
         │  SystemIntents / AlarmManager / ProotRuntime
         │
         ├─ dsh-plugin-character-cards（6 个角色卡工具，宿主鉴权）
         │    ▼
         │  角色卡 + 世界书存储（应用私有文件，会话在 SQLCipher）
         │
         └─ dsh-plugin-gmail（5 个只读 + 4 个审批写工具）
              │  HTTP POST /action（127.0.0.1 + 每进程随机 token）
              ▼
            ConnectorBridgeServer（App 内）—— 只发一个短命的 access token
              ▼
            Gmail API（用户自己的邮箱）
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
- **写操作过闸，下载有界**：日程、定时任务、邮件、`run_command` 与角色卡写操作都走
  `tools/pre-execute → ask`。外部角色卡下载逐跳复检目标地址、拒绝私网地址，并限制大小与时长。

## 隐私与密钥

> [!NOTE]
> 这些不是「以后会做」的承诺，是已经落地的结构性事实 —— 相关的采集管线
> （QQ / 微信聊天记录、知识图谱、通知探针）已从代码与仓库历史里整体移除。

- **密钥只有一个来源**：设置页 → `SecretVault`（Android Keystore 封装）。密文落盘、
  硬件封装不可导出、不进云备份与设备间直传、不回填界面、不进日志，**也不会被编进 APK**。
- **屏幕与聊天正文不落日志**：日志只记计数 / 类名 / 坐标 / 错误码；读屏内容只出现在
  给模型的工具结果里。发往云端的是**你自己配置的那个端点**。
- **视觉路径的感知面更大**：树不可读时改走截图，交给模型的是**整屏图像**（含屏幕上的一切正文）。
  它和读屏 payload 同级：只进给模型的工具结果，不落日志、不落盘。
- **邮箱连接器本机不保存邮箱副本**：不落盘、不落日志、不做后台轮询。
  refresh token 进 Keystore 封装的 `SecretVault`，插件只拿短命的 access token。
- **授权面比读取面宽（刻意的）**：申请的是 `gmail.modify`（读 + 写 + 发），模型拿到的是
  5 个只读工具与 4 个写工具；写工具只有用户逐次批准后才会执行。Google 对已安装应用不支持
  增量授权，扩权要所有已连接用户重走同意页，所以一次要齐。完整取舍见
  [docs/email-connector.md](docs/email-connector.md) §1.1。
- **酒馆数据**：角色卡、角色记忆和世界书是应用私有文件；会话与前情提要在 SQLCipher 数据库里。
  发送酒馆请求时，卡内人设、命中的世界书条目、记忆、提要与聊天会交给用户配置的模型提供商。
  外部卡是不可信数据：卡内 system prompt 与示例对话默认不生效，只有用户显式打开后才进入运行提示。
- **会话库** `zoneu.db` 用 SQLCipher 加密，口令由 Keystore 封装：
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
  判断交给 `zoneu-auditor` 子 agent。产物（含屏幕正文）按敏感数据对待，不入库。
- 改了 `tools/dsh-phone/` 下的 launcher / profile / 插件，必须重跑 `.\tools\bundle-dsh.ps1`
  并重装 APK 才生效（运行时打包在 assets 里）；设备工具插件本身在
  [独立公开仓库](https://github.com/asdukw/dsh-plugin-android-tools)，
  先在那边提交再更新 submodule 指针。

CI 有六个 workflow：`ci.yml`（静态检查 + 单测 + 离线自检）、`assemble.yml`（装配 libnode/dsh
并出包，**只在装配相关路径变动时**跑，且验产物结构）、`codeql.yml`、`release.yml`
（推 `v*` tag：先过门禁 → 出签名包 → 验签 + 扫包内密钥 → 发 release），另有
`workflow-lint.yml` 与 `rg.yml` 分别做 workflow lint 和预编译 ripgrep。

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
- **文件工具不是沙箱**：模型能读写 App 能碰到的全部路径。`run_command` 受审批门控，
  但一旦放行就拥有 App 的文件系统权限。
- 切换提供商 / 模型会换 dsh 会话命名空间：界面历史保留，但模型上下文从下一条消息重新开始
  （dsh SDK 只有 create、没有 resume）。
- **Gmail 的预置 OAuth 客户端停在 Google 测试模式**：只有白名单里的测试账号能授权，
  且 refresh token 寿命受限（实测 7 天，到期需重连）。别人装这个包想用 Gmail，
  得在设置里换成自己的 OAuth 客户端 ID 与密码。授权期间还要求 App 进程活着
  （最长 10 分钟），进程被杀就得重来。
- 定时任务支持每天 / 每周 / 每月 / 每年 / 指定日期单次。精确闹钟在 Android 12+ 需要单独授权，
  Android 14 对新装应用默认拒绝。单次任务在被领取时即停用，无论随后成功还是失败。
- 酒馆模式的直连通道目前只支持 OpenAI 兼容端点；不可用时回落到 dsh 通道，
  深度插入、续写、替我写和自动提要等直连能力受限。角色卡文件不用 SQLCipher 加密，
  PNG 头像与嵌入附件实体不保存，`use_regex` / `vectorized` 世界书条目保留但不执行。
- 跨 App 长任务依赖前台服务保活，ColorOS 的后台冻结仍有边界情况。
- 项目经历两次改名：Memex → Factotum → zoneu，每次 `applicationId` 都换新。
  旧包（`com.memex.app`、`com.factotum.app`）读不到新 App 的数据，卸载即可。

## 文档

| 文件 | 内容 |
|---|---|
| [docs/Q&A.md](docs/Q&A.md) | Q&A：常见问题与处理办法 |
| [AGENTS.md](AGENTS.md) | 给 coding agent 的项目记忆：硬规则、命令、改动入口、完整文档索引 |
| [docs/dsh-android.md](docs/dsh-android.md) | agent runtime 选型与各阶段实测 |
| [docs/android-accessibility.md](docs/android-accessibility.md) | 执行层、视觉路径、文本注入通道 |
| [docs/tools.md](docs/tools.md) | 模型可见工具、日历与闹钟、`run_command` |
| [docs/approvals.md](docs/approvals.md) | 审批缝、确认面板、写操作闸门 |
| [docs/providers.md](docs/providers.md) | 提供商 / 模型 / 模态 / 运行时路由 |
| [docs/voice.md](docs/voice.md) | 朗读与语音输入：引擎取舍、模型授权、VAD |
| [docs/email-connector.md](docs/email-connector.md) | 连接器的边界与隐私闸门 |
| [docs/web-search.md](docs/web-search.md) | 联网搜索的边界、协议与实测 |
| [docs/linux-on-phone.md](docs/linux-on-phone.md) | 手机上的 Linux：内嵌 proot + Debian 与 `run_command` |
| [docs/roleplay.md](docs/roleplay.md) | 酒馆模式、角色卡、预设、宏、记忆、提要 |
| [docs/worldbooks.md](docs/worldbooks.md) | 世界书：选择、绑定、请求投影 |
| [docs/scheduled-tasks.md](docs/scheduled-tasks.md) | 定时任务：规则、日期边界、权限 |
| [docs/user-questions.md](docs/user-questions.md) | `ask_user_question` |
| [docs/memory.md](docs/memory.md) | 记忆与上下文注入 |
| [docs/app-updater.md](docs/app-updater.md) | 应用内更新：断点续传与校验 |
| [docs/on-device-inference.md](docs/on-device-inference.md) | 端侧推理 |
| [docs/releasing.md](docs/releasing.md) | CI 与发版流程、签名材料 |
| [docs/pitfalls.md](docs/pitfalls.md) | 完整排障表 |
| [docs/roadmap.md](docs/roadmap.md) | 技术路线畅想（未验证的假设） |
| [CHANGELOG.md](CHANGELOG.md) | 对外可见的变化 |

# Tappet

> 运行在 Android 上的通用 mobile agent：LLM 通过工具调用操作手机界面完成任务，
> 不做单一 App 的专用适配。

[![CI](https://github.com/asdukw/tappet/actions/workflows/ci.yml/badge.svg)](https://github.com/asdukw/tappet/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/asdukw/tappet)](https://github.com/asdukw/tappet/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

它把手机交给模型：读屏、点按、输入、滑动、启动应用 —— 一共 9 个通用原语，
模型自己组合它们完成「打开计算器算 37×48」这类任务，而不是给每个 App 写一套脚本。
agent 运行时是内嵌在 App 里的 Node 24 + dsh（DeepSeek Harness）phone profile，
执行层走无障碍服务（控件树优先、截图兜底），两者经本机回环上的 HTTP 桥连接。

> [!IMPORTANT]
> 装完要做两件事才能用：**填自己的 LLM API key**（设置页，支持 OpenAI / Anthropic /
> DeepSeek / OpenCode Go / 自定义），**开启无障碍服务**（ColorOS 首次会弹「保持开启」，
> 不点可能被关掉）。装之前请读 [v0.1.0 的发布说明](docs/releases/v0.1.0.md) ——
> 里面写清了**哪些路径真机验证过、哪些只是「配置对得上」**。

## 快速开始

### 装现成的（arm64-v8a）

[Releases](https://github.com/asdukw/tappet/releases) 里有签名 APK 与 `.sha256`：

```bash
adb install -r tappet-v0.2.0-arm64-v8a.apk

# 可选：确认包没被换过（证书指纹应与这里一致）
apksigner verify --print-certs tappet-v0.2.0-arm64-v8a.apk
# SHA-256: 5522b33e1010feab0ab0778c4e304c9571289879dcf0429ffc6a4dee1c9375f6
```

APK 由 CI 在 `v*` tag 上用 release keystore 签名；密钥不参与构建，
包内不含、也不可能含编译进去的 API key。

### 从源码构建

需要 **JDK 21**、PC 上的 **Node 24 + npm**，以及 Android SDK（compileSdk 36）、
**NDK 29.0.14206865**、**CMake 3.31.6**（后三个用 `sdkmanager` 装）。

```powershell
git submodule update --init --recursive   # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1                 # libnode.so + libc++_shared.so
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip

$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

两个装配脚本按 pinned 版本现拉运行时（产物不入库），代理跟随 `HTTPS_PROXY` / `HTTP_PROXY`，
没有就走直连。**真机安装不要走 Gradle 的自动安装通道** —— ColorOS 会拦全新安装，
挂几分钟后失败；正确流程是产包后手动 `adb install -r`，再单独跑测试任务。
细节与重装注意事项见 [AGENTS.md](AGENTS.md)。

## 现在能做什么

| 能力 | 状态 |
|---|---|
| 会话 / 消息 / 工具调用持久化（Room + SQLCipher） | 真机验证 |
| 无障碍执行层原子动作（读屏 / 截图 / 点按 / 长按 / 输入 / 按键 / 启动应用 / 滑动） | 真机验证（一加 15T，ColorOS） |
| agent runtime：内嵌 Node 24 + dsh phone profile，stdio JSON-RPC 驱动，可连续跑任务 | 真机验证 |
| 聊天界面（Compose：Markdown / 工具卡 / 思考折叠 / 流式事件 / 停止保留半截 / 崩溃恢复） | 真机验证 |
| LLM 接入：设置页自填端点与 key，预选四家 + 自定义 | 部分验证 —— 只有 OpenCode Go 用真 key 跑通过 |
| 设备工具插件（`dsh-plugin-android-tools`，9 个通用原语） | 独立公开仓库，见 [AGENTS.md](AGENTS.md) |

## 工作原理

```
Compose UI ── Room/SQLCipher 落库
    │  session/prompt
    ▼
内嵌 Node 24（宿主机进程内单实例）
    └─ dsh phone profile
         └─ dsh-plugin-android-tools（模型能看见的 9 个工具）
              │  HTTP POST /action（127.0.0.1 + 每进程随机 token）
              ▼
        DeviceBridgeServer（App 内）
              ▼
        AccessibilityService：控件树优先、截图兜底
```

几个刻意的选择：

- **不在设备上装插件**：工具经本机回环桥落地，插件源码在独立仓库，
  换 ROM 只加一个 `OemProfile`，不写 App 专用逻辑 —— QQ / 微信只是试验任务的目标 App。
- **stdout 是协议独占**：`session.event` / `session.status` 走 stdout，诊断一律 stderr，
  否则 JSON-RPC 会被日志污染。
- **不做热重载**：设置页改完**下次启动生效**（内嵌 Node 的 env 与 profile 只在启动时读一次，
  重拉进程会把整个 App 带崩）。

## 隐私与密钥

> [!NOTE]
> 这些不是「以后会做」的承诺，是已经落地的结构性事实 —— 相关的采集管线（QQ / 微信聊天记录、
> 知识图谱、通知探针）已从代码与仓库历史里整体移除。

- **密钥只有一个来源**：设置页 → `SecretVault`（Android Keystore 封装）。密文落盘、
  硬件封装不可导出、不进云备份与设备间直传、不回填界面、不进日志，**也不会被编进 APK** ——
  构建期注入那条路已删除（`AGENT_API_KEY` 这个 BuildConfig 字段不存在了）。
- **屏幕与聊天正文不落日志**：日志只记计数 / 类名 / 坐标 / 错误码；读屏内容只出现在
  给模型的工具结果里。发往云端的是**你自己配置的那个端点**。
- **会话库** `tappet.db` 用 SQLCipher 加密，口令由 Keystore 封装：
  **卸载即永久丢失，外部不可解、不可拷**。
- 发布前自检：`tools\check-sensitive.ps1`（扫工作树 + 全部 git 历史，可选连 APK 一起扫；
  `-SelfTest` 先证明扫描器确实会命中）。

## 开发

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug   # 格式 / 坏味道 / Android 专有检查
.\gradlew.bat :app:testDebugUnitTest                # JVM 单测
```

- 取舍与理由写在配置里而不是 wiki：格式见 `android/.editorconfig`，坏味道见
  `android/config/detekt/detekt.yml`（含 **baseline 是历史欠账快照、不许往里加**的规矩），
  Android 专有检查见 `android/app/lint.xml`。
- **真机测试**（`androidTest/`，覆盖 Room/SQLCipher 与 Compose UI）：先 `adb install -r`，
  重新启用无障碍服务，再 `.\gradlew.bat :app:connectedDebugAndroidTest`。
- **任务审计回路**：`tools/audit/run-suite.mjs` 跑固定任务集并采集轨迹，
  判断交给 `tappet-auditor` 子 agent。产物（含屏幕正文）按敏感数据对待，不入库。

CI 分四个 workflow：`ci.yml`（静态检查 + 单测 + 离线自检）、`assemble.yml`（装配 libnode/dsh
并出包，**只在装配相关路径变动时**跑，且验产物结构 —— native 库齐不齐、运行时资产是不是真 zip）、
`codeql.yml`（语义级安全扫描）、`release.yml`（推 `v*` tag：先过门禁 → 出签名包 → 发 release）。

> [!WARNING]
> **CI 绿 ≠ 能用**。无障碍执行层、内嵌 Node 的启动、真实任务只能在真机上验证。
> CI 不装 NDK（那 ~1.5GB 只有装配与发版两条 workflow 需要）。

## 已知限制

- 只带 **arm64-v8a**（内嵌 Node 运行时就占 65MB，多一个 ABI 体积翻倍）；截图能力要求 Android 11+。
- 四个预选里**只有 OpenCode Go 用真 key 跑通过**；OpenAI / Anthropic / DeepSeek 是「端点、
  调用风格、模型三件套对得上运行时目录」，尚未用真 key 发过请求。
- 部分 App（实测微信 8.0.78）对无障碍隐藏控件树。这时会走**视觉路径**：截一张图，
  模型在图上找到目标、给出外框，由执行层换算成屏幕坐标点击。**但树不可读时输入不了文字** ——
  「发一条消息」这类任务目前做不到（缺一条不依赖控件树的输入通道）。
- 视觉路径的坐标换算有单测钉住，**模型在图上估坐标准不准还没在真机上验证过**。
- 跨 App 长任务依赖前台服务保活，ColorOS 的后台冻结仍有边界情况。
- 项目原名 Memex，2026-10-12 更名（含 `applicationId`）。旧包 `com.memex.app` 若还在设备上，
  其数据新包读不到，卸载即可。

## 文档

| 文件 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 给 coding agent 的项目记忆：硬规则、命令、常见坑、发版流程 |
| [docs/dsh-android.md](docs/dsh-android.md) | agent runtime 选型（内嵌 dsh + libnode）与各阶段实测 |
| [docs/android-accessibility.md](docs/android-accessibility.md) | 执行层系统探测与实测记录 |
| [docs/roadmap.md](docs/roadmap.md) | 技术路线畅想（未验证的假设） |
| [CHANGELOG.md](CHANGELOG.md) | 对外可见的变化；每个版本的说明在 `docs/releases/` |

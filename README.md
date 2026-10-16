# Tappet

**运行在 Android 上的通用 mobile agent**：通过工具调用操作手机上的任意 App 完成任务，
不做单一 App 的专用适配。

[![CI](https://github.com/asdukw/tappet/actions/workflows/ci.yml/badge.svg)](https://github.com/asdukw/tappet/actions/workflows/ci.yml)

> **当前版本 v0.1.0**：[Releases](https://github.com/asdukw/tappet/releases) 里有**签名 APK**
> （arm64-v8a）与源码。APK 由 CI 在 `v*` tag 上用 release keystore 签名，证书 SHA-256 指纹
> `5522b33e1010feab0ab0778c4e304c9571289879dcf0429ffc6a4dee1c9375f6`。
> 密钥不再编进包（构建期注入已整个去掉），所以发包不再等于公开密钥。
> 装之前请读 [v0.1.0 的说明](docs/releases/v0.1.0.md) —— 里面写清了要自备 LLM key、要开无障碍，
> 以及**哪些路径真机验证过、哪些只是「配置对得上」**。

> **方向（2026-10-03 定稿）**：放弃 QQ / 微信聊天记录与知识图谱，不做 PC 客户端，
> 不采集通知。仓库唯一运行时是 Android App；Python 侧已整体删除。
>
> **2026-10-17 收缩**：删掉 dsh Web UI 预览面板与无障碍「四问」探针页 —— 两个都是开发期的
> 诊断入口，不该出现在使用者面前。执行层本身的原子动作（读屏 / 截图 / 点按 / 输入 / 滑动）
> 不受影响。
>
> 被删除的旧管线（QQ / 微信解密、增量同步、归一化、聊天抽取、MCP Server、
> 通知探针、Python 图谱骨架）**不在本仓库历史里**：公开发布前做过一次历史重建，
> 对外历史从重建出的**根提交**开始。旧历史（含上述代码）备份在仓库外的
> `memex-history-backup-2026-10-12.bundle`，本地回顾用它，不随发布。

## 现在有什么

| 模块 | 状态 |
|---|---|
| Android 会话 / 消息 / 工具调用持久化（Room + SQLCipher） | ✅ 真机验证 |
| 手写的流式 LLM 客户端（`LlmClient` / `AgentLoop`，含 reasoning_content 与流式 tool_calls 解析） | ⏸ **已暂停，不随发布使用**（见 AGENTS.md「方向红线」）；真正在跑的是内嵌 dsh |
| 原生聊天界面（Compose：Markdown / 代码块 / 工具卡 / 会话列表、流式事件、停止保留半截、崩溃恢复） | ✅ 真机通过（一加 15T） |
| 无障碍执行层原子动作（读屏 / 截图 / 点按 / 长按 / 输入 / 按键 / 启动 App / 滑动） | ✅ 真机通过（一加 15T） |
| agent runtime（内嵌 dsh + 本地桥 + android-tools 9 个通用原语，见 [docs/dsh-android.md](docs/dsh-android.md)） | ✅ 真机通过（P6 工具卡；P7 长按/按键；P8 思考折叠块；P10 截图 + 图像输入） |

## Android

```powershell
# 0）首次准备：仓库里没有内嵌 Node 运行时（libnode / dsh-runtime.zip 都按 pinned 版本现拉，不入库）。
#    除 JDK 21 外还需要：PC 上的 Node 24 + npm，以及 Android SDK（compileSdk 36）、
#    NDK 29.0.14206865、CMake 3.31.6（后三个用 sdkmanager 装）。
git submodule update --init --recursive    # 设备工具插件在独立仓库
.\tools\fetch-libnode.ps1                  # libnode.so + libc++_shared.so
.\tools\bundle-dsh.ps1                     # vendored dsh → assets/dsh-runtime.zip
#    注意：这两个脚本的代理跟随 HTTPS_PROXY / HTTP_PROXY（本机是 127.0.0.1:7897）；
#    没有这两个变量就走直连（CI 上就是直连）。

# 1）构建（必须 JDK 21）
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
cd android

.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

### 质量门禁

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain   # CI 跑的就是这三条
```

ktlint（格式）/ detekt（坏味道）/ Android Lint（API 级别、清单、资源），配置与取舍理由见
[AGENTS.md](AGENTS.md) 的「代码质量门禁」。

CI 分四个 workflow：`ci.yml`（静态检查 + 单测 + 离线自检）、`assemble.yml`（装配 libnode/dsh 并出包，
**只在装配相关路径变动时**跑，且会验产物结构 —— native 库在不在、运行时资产是不是真 zip）、
`codeql.yml`（语义级安全扫描）、`release.yml`（推 `v*` tag 时先过门禁再出签名包并发 release）。

**CI 绿 ≠ 能用**：无障碍执行层与内嵌 Node 的启动只能在真机上验证。

真机安装**不要**用 Gradle 的自动安装通道：ColorOS 会拦全新安装（挂 ~5 分钟后
失败）。正确流程是产包后手动 `adb install -r`，再单独跑需要的测试任务。
完整规则与重装注意事项见 [AGENTS.md](AGENTS.md)。

- **LLM 端点与 API key 都由使用者在设置页填**（抽屉 → 设置），预选里有 OpenAI /
  Anthropic / DeepSeek / OpenCode Go，也可以自定义。预选不只是填两个字符串：端点、
  调用风格（`openai-responses` / `anthropic-messages` / `openai-completions`）、模型
  三件套是一套的，选错会在第一次请求时报 404/400。
- **密钥只有一个来源：设置页**，存进 Android Keystore 封装的 `SecretVault` —— 密文落盘、
  硬件封装不可导出、不进云备份与设备间直传、不回填界面、不进日志，**也不会被编进 APK**
  （构建期注入那条路已经删掉）。设置页保存后**下次启动生效**（内嵌 Node 的 env 只在启动时
  读一次）。
- `android/local.properties` 只剩非密钥的开发机默认值（端点 / 模型 / 代理），优先级低于设置页。
- 会话库 `databases/tappet.db` 用 SQLCipher 加密，口令由 Android Keystore 封装：
  **卸载即永久丢失，外部不可解、不可拷。**
- 项目原名 Memex，2026-10-12 更名为 **Tappet**（含 `applicationId`，见 [AGENTS.md](AGENTS.md)
  的「命名」）。旧包 `com.memex.app` 若仍在设备上，其数据新包读不到，卸载即可。

## 仓库结构

```
android/          Android App（唯一运行时）
docs/             android-accessibility.md：无障碍执行层系统探测与实测记录
                  dsh-android.md：agent runtime 选型（内嵌 dsh + libnode）与落地计划
                  roadmap.md：技术路线畅想（下一步能推到什么程度）
```

## 隐私红线

1. **密钥永不入库**：`android/local.properties` 已在 `.gitignore` 内，不要改。
2. **密钥不进发布物，且这件事是结构性的**：密钥只存在设备上的 Keystore 封装里，
   构建期注入那条路已删除（`AGENT_API_KEY` 这个 BuildConfig 字段不存在了），
   所以「包里有密钥」不可能发生 —— 而不是靠人记得别发包。
   真要发包仍建议跑 `tools/check-sensitive.ps1 -ApkPath <包>` 兜底：
   它现在的作用是确认这条结构性保证没被谁改回去。
3. **默认全本地**：手机端数据是否发往云端 LLM 由用户逐次决定。
4. **不采集他人数据**：随聊天记录管线一并移除；如需重新引入任何采集能力，
   先在设计文档里写清边界与隐私闸门，再动代码。

## 许可

MIT，见 [LICENSE](LICENSE)。

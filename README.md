# Tappet

**运行在 Android 上的通用 mobile agent**：通过工具调用操作手机上的任意 App 完成任务，
不做单一 App 的专用适配。

> **方向（2026-10-03 定稿）**：放弃 QQ / 微信聊天记录与知识图谱，不做 PC 客户端，
> 不采集通知。仓库唯一运行时是 Android App；Python 侧已整体删除。
>
> 被删除的旧管线（QQ / 微信解密、增量同步、归一化、聊天抽取、MCP Server、
> 通知探针、Python 图谱骨架）**不在本仓库历史里**：公开发布前做过一次历史重建，
> 对外历史从重建出的**根提交**开始。旧历史（含上述代码）备份在仓库外的
> `memex-history-backup-2026-10-12.bundle`，本地回顾用它，不随发布。

## 现在有什么

| 模块 | 状态 |
|---|---|
| Android 会话 / 消息 / 工具调用持久化（Room + SQLCipher） | ✅ 真机验证 |
| 流式 LLM 客户端（含 reasoning_content 与流式 tool_calls 解析） | ✅ 真机验证 |
| 原生聊天界面（Compose：Markdown / 代码块 / 工具卡 / 会话列表、流式事件、停止保留半截、崩溃恢复） | ✅ 真机通过（一加 15T） |
| Web 预览面板（dsh Web UI 嵌入，按需打开：HTML / Office / diff / 富文本） | ✅ 真机通过（P5） |
| 无障碍四问探针（读屏 / 截图 / 输入 / 手势） | ✅ 真机通过（ColorOS + QQ） |
| 无障碍执行层原子动作（读屏 / 截图 / 点按 / 长按 / 输入 / 按键 / 启动 App / 滑动） | ✅ 真机通过（一加 15T） |
| agent runtime（内嵌 dsh + 本地桥 + android-tools 9 个通用原语，见 [docs/dsh-android.md](docs/dsh-android.md)） | ✅ 真机通过（P6 工具卡；P7 长按/按键；P8 思考折叠块；P10 截图 + 图像输入） |

## Android

```powershell
# 必须 JDK 21
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
cd android

.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

真机安装**不要**用 Gradle 的自动安装通道：ColorOS 会拦全新安装（挂 ~5 分钟后
失败）。正确流程是产包后手动 `adb install -r`，再单独跑需要的测试任务。
完整规则与重装注意事项见 [AGENTS.md](AGENTS.md)。

- API key **只在 debug 变体**由构建期从 `android/local.properties`（gitignored）注入
  BuildConfig：`tappet.agent.apiKey=...`。绝不入库、不进日志。
  **release 变体留空**（`AGENT_API_KEY=""`）：BuildConfig 是编译期常量，会以明文进 dex，
  带密钥的包一旦发出去密钥就等于公开。所以发布物里没有密钥，由使用者运行时自备
  （设置页仍待做，在此之前 release 包不可用）。
- 会话库 `databases/tappet.db` 用 SQLCipher 加密，口令由 Android Keystore 封装：
  **卸载即永久丢失，外部不可解、不可拷。**
- 项目原名 Memex，2026-10-12 更名为 **Tappet**（含 `applicationId`，见 [AGENTS.md](AGENTS.md)
  的「命名」）。旧包 `com.memex.app` 若仍在设备上，其数据新包读不到，卸载即可。

## 仓库结构

```
android/          Android App（唯一运行时）
docs/             android-accessibility.md：无障碍执行层系统探测与四问指导
                  dsh-android.md：agent runtime 选型（内嵌 dsh + libnode）与落地计划
                  roadmap.md：技术路线畅想（下一步能推到什么程度）
```

## 隐私红线

1. **密钥永不入库**：`android/local.properties` 已在 `.gitignore` 内，不要改。
2. **密钥不进发布物**：只有 debug 变体注入 API key；发布物（源码、release 包）里
   不得出现密钥，打包前用 `strings` 扫一遍产物确认。
3. **默认全本地**：手机端数据是否发往云端 LLM 由用户逐次决定。
4. **不采集他人数据**：随聊天记录管线一并移除；如需重新引入任何采集能力，
   先在设计文档里写清边界与隐私闸门，再动代码。

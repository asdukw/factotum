# Changelog

只记**对外可见的变化**。格式按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的骨架，
版本号按语义化版本；提交信息用 conventional commits，`feat` / `fix` / `BREAKING CHANGE` 决定升位。
release 由 `.github/workflows/release.yml` 在 `v*` tag 上生成，每个版本的说明存档在
`docs/releases/<tag>.md`。

## [0.1.0] - 2026-10-17

首个发布（源码 + 签名 APK，arm64-v8a）。

### 包含

- **Agent 运行时**：App 内嵌 Node 24（nodejs-mobile）+ dsh phone profile，经 stdio JSON-RPC 驱动；
  会话可连续跑，不需要重启进程。
- **设备执行层**：`AccessibilityService` 实现，控件树优先、截图兜底；9 个通用原语
  （读屏 / 截图 / 点按 / 按坐标点 / 长按 / 输入 / 按键 / 启动应用 / 滑动）经本地 HTTP 桥
  （127.0.0.1 + 每进程随机 token）暴露给模型。
- **LLM 接入**：设置页自填端点与 key，预选 OpenAI / Anthropic / DeepSeek / OpenCode Go / 自定义；
  密钥存 Keystore 封装，**构建期不带密钥**（包里不可能有）。
- **持久化与界面**：Room + SQLCipher 存会话 / 消息 / 工具调用；Compose 聊天界面（流式回复、
  工具卡、停止保留半截、崩溃恢复）。
- **工程质量**：ktlint + detekt + Android Lint 门禁、单测、CodeQL、Dependabot，跑在 GitHub Actions 上；
  设备侧另有一套审计回路（固定任务集 + 轨迹采集）。

### 未包含 / 已知限制

- **OpenAI / Anthropic / DeepSeek 三个预选未经真 key 验证**（只有 OpenCode Go 跑通过）。
- 部分 App（微信 8.0.78）只在触摸探索开启后启动的进程里暴露控件树，任务期间会自动开触摸探索，
  但那要求目标 App 在模式开启后重启进程 —— 已在任务的提示里说明。
- 跨 App 长任务依赖前台服务保活；ColorOS 的后台冻结仍有边界情况。
- 只带 arm64-v8a；截图能力需要 Android 11+（API 30）。

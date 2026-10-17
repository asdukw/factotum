# Changelog

只记**对外可见的变化**。格式按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的骨架，
版本号按语义化版本；提交信息用 conventional commits，`feat` / `fix` / `BREAKING CHANGE` 决定升位。
release 由 `.github/workflows/release.yml` 在 `v*` tag 上生成，每个版本的说明存档在
`docs/releases/<tag>.md`。

## [未发布]

### 新增

- **视觉路径（帧契约）**：控件树读不到时不再请用户「强行停止」目标 App，改走
  `capture_frame`（图 + 帧信息）→ `tap_target`（按该帧的图像像素外框点击）。执行层负责
  图像像素 → 屏幕像素的换算，动作后比对 8×8 指纹回 `changed`。工具面：新增 `capture_frame` /
  `tap_target` / `long_press_target`，移除 `tap_point`（屏幕像素陷阱）。

### 变更

- **任务期不再自动开触摸探索**：它一开，注入手势就被系统吞掉，树不可读时等于「只能看不能点」，
  而树不可读正是要走视觉的时候；它也救不了已经在跑的 App。机制与 debug 广播保留，
  用户自己开着 TalkBack 时的语义兜底分支一个字没动。
- 判「树不可读」从「节点数 < 2」改成「可见且有内容/可交互的节点一个都没有」——
  后者能抓住「节点不少、可读为零」的假阴性。
- `swipe` 的描述改成实情「约半屏」；触摸探索态下没有可滚动节点时不再谎报滑动成功。
- `image-offload` 启用：超请求图像预算时丢最老的图重试，而不是整轮失败（代价：永久丢帧）。

### 已知缺口

- **树不可读时无法输入文字**（`type_text` / `press(enter)` 都要控件树找输入框）——
  「给某人发一条消息」这类任务仍做不到，需要 roadmap §2.5 的自建 IME。
- 帧契约的换算有单测钉住，但**模型在图上估坐标准不准尚未真机验证**。

## [0.2.0] - 2026-10-17

### 新增

- **流式输出**：运行时补丁把 token 级增量转发成 `session.stream`，正文逐字上屏（50ms 合并刷新），
  步进成品只替换本步的流式文本。
- **聊天界面「过程块」**：工具与思考收进一个大可折叠块，内部各自可折叠；工具显示中文名，
  点开是底部详情（参数 / 结果 / 复制）；思考显示时长。正文在块外。
- **无障碍引导**：真的有一次设备动作因无障碍未开启而失败时，聊天页提示 + 一键跳到系统设置；
  纯聊天不打扰。

### 变更

- Kotlin 2.3.20 → 2.4.21；SQLCipher 4.17.0 → 4.19.1（已验证旧库可打开）；Gradle wrapper → 9.8.1。
- 去掉气泡里的「已提交，等待模型…」状态文案。

完整说明见 [docs/releases/v0.2.0.md](docs/releases/v0.2.0.md)。

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

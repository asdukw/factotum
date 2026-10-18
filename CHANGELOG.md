# Changelog

只记**对外可见的变化**。格式按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的骨架，
版本号按语义化版本；提交信息用 conventional commits，`feat` / `fix` / `BREAKING CHANGE` 决定升位。
release 由 `.github/workflows/release.yml` 在 `v*` tag 上生成，每个版本的说明存档在
`docs/releases/<tag>.md`。

## [未发布]

### 新增

- **联网搜索（`web_search`）**：接的是 **dsh 自带的 web 能力 seam**（`dsh-web` + `dsh-tool-web`
  + `dsh-web-search-deepseek`），不是另写一个插件 —— 模型拿到 `web_search` 与 `web_fetch`
  两个工具，附提示词指引、来源上限、超时，网页正文被标成**外部不可信数据**。
- **不接搜索 API**：Bing Web Search API 已退役（替代品要 Azure 订阅）、Google Custom Search
  JSON API 已对新客户关闭。走模型服务商自己的服务器端搜索：**零新增厂商、零新增凭据**
  （用的还是用户已经配好的那把 key）。
- **换协议比换模型划算**：zen 的 Anthropic 兼容端点 `/zen/go/v1/messages` 支持原生
  `web_search_20250305`，同一个 `claude-haiku-5-5` 在 zen 的 `/responses` 上是被协议门挡掉的。
  实测一次搜索 input 约 13k token，比 `/responses` 那条（17k–44k）便宜。
- 没有可用搜索端点的提供商（OpenRouter / 自定义）**工具照样挂着**，每次调用回结构化的
  `WEB_PROVIDER_UNAVAILABLE` —— 与连接器「没配就回 `NOT_CONNECTED`」同一条路数。

### 已知限制

- **「接受工具」不等于「会真的搜索」**：网关不校验模型是否真支持宿主 `web_search`。
  实测 zen 的 `deepseek-v4.1-flash` 收下工具、回 200，然后一次都没搜（`input_tokens` 只有 50）
  —— 所以搜索模型是**单独指定**的（OpenCode Go 用 `claude-haiku-5-5`），不是当前聊天模型。
- **Anthropic 官方端点与 DeepSeek 官方端点未验**（没有对应的 key），形状按官方文档写。
- 一次搜索约 13k input token（zen + `claude-haiku-5-5`）。

边界、两道门的实测与真机验证步骤见 [docs/web-search.md](docs/web-search.md)。

## [0.5.0] - 2026-10-18

### 新增

- **连接器（首版：Gmail，只读）**：设置 → 连接器 → Gmail，点「连接」→ 浏览器里点「同意」→
  自动跳回 App。连上后模型多出三个工具：`mail_list` / `mail_search` / `mail_read`。
  **工具面没有任何写操作** —— 发送、删除、归档、标记已读都不在首版里。
  边界与隐私闸门见 [docs/email-connector.md](docs/email-connector.md)。
- **连接器是一类能力，不是一个功能**：设置 → 连接器 是一个列表页，Gmail 已可用，
  Outlook 显示「即将推出」。加一个连接器 = 加一条数据 + 一个插件。
- 邮件工具**不经过无障碍**：无障碍被关掉时设备工具全失效，邮件这条路照常可用。
- **授权时申请的是 `gmail.modify`（读 + 写 + 发），比工具面宽**：Google 对已安装应用不支持
  增量授权，扩权要所有已连接用户重走同意页，所以一次要齐。代价写在文档 §1.1 ——
  「插件被攻破也发不出邮件」这条以前由 Google 兜底，现在只剩插件里唯一的只读出网函数在守。
- 凭据分层：refresh token 进 Keystore 封装的 `SecretVault`（明文不落盘、不进日志、不进云备份），
  access token 只在内存；插件每次调用向本机的连接器桥要一个短命令牌，从不接触 refresh token。
- 授权用 OAuth 2.0 + **PKCE**；回环重定向（`127.0.0.1`）不改 Manifest、不注册签名指纹，
  客户端 ID 与密码因此可以预置在包里，新装用户直接点「连接」即可。
  客户端密码不是机密（Google 原话：桌面应用是公共客户端、保不住秘密），真正的保护是 PKCE；
  但它**必须发** —— 文档标「可选」而令牌端点实际会拒（`client_secret is missing`）。
- 邮件正文包在「正文开始 / 结束」的数据边界里，并在工具描述中声明**是数据不是指令**；
  超过 4000 字符截断。正文不进日志、不落盘，本机不保存邮箱副本。

### 已知限制

- **预置的 Google OAuth 客户端停在测试模式**：只有白名单里的测试账号能授权，且 refresh token
  寿命受限（实测 7 天，到期需重连）。别人装这个包想用 Gmail，得在设置里换成自己的 OAuth
  客户端 ID 与密码（「使用我自己的 OAuth 客户端 ID」）。
- **授权过程要求 App 进程活着**（最长 10 分钟持有回环 socket）。进程被杀就得重来。
- Gmail 连接器只读：没有发送 / 删除 / 归档 / 标记已读。写操作要等危险动作确认闸门。

## [0.4.0] - 2026-10-18

### 新增

- **设置页与提供商管理**：分组卡片设置页；提供商列表（搜索、按显示名字母序、启停、模型数、
  缺 Key 标记）；详情三页签（配置 / 模型 / 高级设置）。密钥每个提供商一把、各自用 Keystore
  封装；旧版单端点配置自动沿用（老用户不需要重填 key）。
- **模型列表不再写死**：从端点 `GET /models` 拉取（可搜索、多选加入「可用模型」），
  端点不支持时手填 id；可用模型可增删、点选为当前模型。
- **聊天页切换提供商 / 模型**：顶栏副标题显示「提供商 · 模型」，输入栏左下是品牌图标；
  切换 sheet 对齐 RikkaHub（胶囊搜索、供应商分组可折叠、当前项高亮）。选中后按需重载
  dsh 运行时 —— 同一 Node 进程内 remount（约 0.2–1 秒），不重启 App。
- 构建期从 pi-ai 目录生成模态索引（42 家 / 1600 个模型），运行时按它声明
  `TAPPET_LLM_MODELS`；目录外的 id 按纯文本声明。

### 变更

- 抽屉里的设置入口改为齿轮图标 +「设置」。
- 设置页去掉「密钥与存储」一行（说明仍在提供商详情的高级设置里）。

### 修复

- 手填 / 自定义模型报 `UNKNOWN_MODEL`：模型清单改为按当前选中模型动态注入。
- 自定义提供商 + Response API 的 `thinkingFormat` 与协议不匹配。
- 启用中的自定义端点被清空会让 dsh mount 失败、把 App 带崩：回落可挂载默认并清空密钥。
- 「恢复默认设置」对升级用户无效：一并清掉指向该提供商的旧版键。
- 密钥输入框改用 Password 键盘类型；`AgentConfig.toString()` 不再包含密钥；错误提示改用
  Snackbar；图标按钮补语义标签。

### 已知限制

- 切换模型会换 dsh 会话命名空间：界面历史保留，但模型上下文从下一条消息重新开始。
- 改 API Key 仍需重启 App（key 只在 Node 启动时读入 env）；模型 / 提供商切换不需要。
- 真机上的实际切换 + 发任务尚未完成（模拟器已跑通）；除 OpenCode Go 外的提供商没有用
  真 key 实跑过。

完整说明见 [docs/releases/v0.4.0.md](docs/releases/v0.4.0.md)。

## [0.3.0] - 2026-10-17

### 新增

- **视觉路径（帧契约）**：控件树读不到时不再请用户「强行停止」目标 App，改走
  `capture_frame`（图 + 帧信息）→ `tap_target`（按该帧的图像像素外框点击）。执行层负责
  图像像素 → 屏幕像素的换算，动作后比对 8×8 指纹回 `changed`。工具面：新增 `capture_frame` /
  `tap_target` / `long_press_target`，移除 `tap_point`（屏幕像素陷阱）与 `screenshot`（由 `capture_frame` 取代）。

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

完整说明见 [docs/releases/v0.3.0.md](docs/releases/v0.3.0.md)。

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

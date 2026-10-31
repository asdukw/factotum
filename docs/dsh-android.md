# dsh on Android：运行时决策与落地计划

**状态（2026-10-03）**：决策已定 —— **agent runtime 用 DeepSeek Harness（dsh），
不自造 harness；宿主走「App 内嵌 libnode（Node 24）」**。手写的
`LlmClient` 工具协议 / `AgentLoop` / 对话集成**暂停**，设备执行层
（无障碍服务 + `DeviceBridge` + 四问）全部保留。

> 背景：手写 runtime 的结论被推翻。dsh 的「一切皆插件」+ Node 24 可嵌入 Android
> 两个事实，使「写插件而不是写 harness」成为更划算的路线。

---

## 独立世界书适配的实施边界

实现合同见 [worldbooks.md](worldbooks.md)。新增适配不修改 dsh 冻结请求或持久消息，也不关闭 loop 的重建校验；纯装配补丁仅在 pi-ai 请求转换后暴露可选应用投影服务。服务缺省完全透传，锚点、版本或部分补丁状态不符即停止打包。

内部 SDK 扩展负责在真实用户消息创建后、followup 前绑定宿主请求身份；定向取消和持久化结算经专用内部 RPC 完成，不发送 shutdown、不退出 Node。普通日常和辅助标题／压缩调用不注入。最终提供商协议无法表达的角色／位置明确报不兼容，不能静默改写成 user。

这些模块先由本地 fake provider 验证最终 wire 与生命周期，再接 Room 和 UI；尚未完成的接线与真机验证不能写成已支持。角色／世界书仓库的 fs 包装保护不等于同 UID shell 沙箱，长期记忆工作区仍保持可写。

## 1. 查证事实（2026-10-03，来源均为官方仓库/文档）

| 事实 | 值 | 影响 |
|---|---|---|
| dsh 版本 / 许可 | `0.2.1-alpha.1`（master）、MIT、developer preview | API 会破坏性变更 → vendored + 锁版本 |
| dsh Node 要求 | `^22.19.0 \|\| >=24.0.0` | 与 libnode 的 Node 24 对齐 |
| 插件架构 | Cordis：loop / tools / session / UI / 模型适配器全是插件；工具注册在 `ctx.tools` | 设备控制写一个 TS 插件即可，不改 core |
| 自定义模型 | `llm-pi-ai` 支持 `api: openai-completions` + `baseURL` + `headers`；pi-ai 的 URL 识别名单**含 `opencode.ai`**；`compat.thinkingFormat: deepseek` | 继续用现有 opencode zen key（`x-opencode-session` 需 P1 实测） |
| MCP 客户端 | 支持 `streamable-http` + headers（备选桥方案） | 桥不一定要 MCP；原生插件更省 |
| Android Node | 社区 fork `gmaclennan/nodejs-mobile`：Node `v24.18.0`、arm64/armv7/x86_64、**16KB page**、`libnode.so` 预编译 | 不必自己编 Node；上游 Janea 已停更，fork 活跃 |
| 嵌入模板 | 官方 sample `native-gradle-node-folder`：CMake 导入 `libnode.so`、JNI 调 `node::Start`、stdout/stderr 转发 logcat | P0 直接照搬骨架 |
| 数据目录约定 | Android 必须设 `TMPDIR`=cacheDir、`HOME`=filesDir、`NODE_COMPILE_CACHE`+`PORTABLE=1`；`NODE_OPTIONS` 建议 `--max-old-space-size-percentage` | 由 Kotlin 侧在启动前 `setenv` |
| 移动端限制 | 无 `child_process`/`fork`；`process.exit()` 在 iOS 受限；原生模块需交叉编译 | phone profile 避开 shell/subprocess/terminal |
| dsh 原生依赖 | `node-pty`（subprocess-local）、`sharp`（attachment/spill）、`node-addon-system`（sandbox/landlock/flock） | 均无 Android 产物 → phone profile 不挂这些插件；Windows 无 prebuild 也能跑，说明可选 |

## 2. 架构

```
zoneu APK（单进程）
├─ Kotlin / Compose 前端（现有聊天 UI 或新界面）
├─ AccessibilityService + DeviceBridge（现有执行层，四问/8 个桥动作）
├─ BridgeServer（127.0.0.1 + token，把 DeviceAction 暴露成 JSON HTTP）
└─ NodeRuntime（JNI → libnode.so，后台线程）
     └─ dsh（vendored，phone profile）
          ├─ llm-pi-ai → opencode zen
          ├─ core：session / tools / system-prompt / agent / agent-loop
          ├─ sessions：jsonl（compression none）
          └─ dsh-plugin-android-tools（8 个通用原语，工具 → BridgeServer）
```

- **为什么插件而不是 MCP**：dsh 的 MCP client 走 streamable-http 要我们实现完整
  MCP server（会话/SSE/协议协商）；而 dsh 插件只需 `ctx.tools` 注册 + `fetch`
  本地 HTTP，代码量小一个量级，且能直接用 dsh 的审批/展示管线。MCP 留作
  将来对外复用桥的选项。
- **为什么内嵌而不是外部进程**：产品形态是一个 APK，不要求用户另装一个终端 App。
- **profile**：以 `packages/bundle/sdk-minimal/cordis.patch.yml` 为模板——
  去掉 shell/subprocess/sandbox/terminal/attachment，保留
  timer/llm/session/system-prompt/tools/mcp-resources/agent/llm-retry/jobs/agent-loop/
  sessions，  再挂 `llm-pi-ai`、android/chat 插件与 `ask-user`（审批）。

## 3. 产物与仓库策略

- `libnode.so`（每 ABI ~24MB）**不入 git**：`tools/fetch-libnode.ps1` 按 pinned
  版本从 fork release 下载到 `android/app/src/main/jniLibs/<abi>/`（gitignore）。
- dsh 运行时（node_modules）**不入 git**：`tools/bundle-dsh.ps1` 在 PC 上安装
  pinned 版本的 `@deepseek-ai/dsh`、去掉无关大包、连同 phone profile 拷进
  `android/app/src/main/assets/nodejs/`（gitignore，随 APK 打包）。仓库只保留
  脚本、profile patch 与插件 submodule 指针（插件源码在独立公开仓库）。
- 许可：dsh MIT；Node.js 与 fork patches 为 MIT，打包时附 third-party notices。

## 4. 分阶段

| 阶段 | 内容 | 验证 |
|---|---|---|
| **P0** ✅ | JNI 模块 + libnode + `NodeRuntime`（env/资产拷贝/日志转发），跑 hello-world JS | 2026-10-03 一加 15T 通过：logcat `NODEJS-MOBILE` 输出 `v24.18.0 / mobile 24.18.0-0 / android / arm64`，TMPDIR/HOME 正确，5 秒后 exit 0；4KB 页设备跑 16KB 对齐的 .so 正常 |
| **P1a** ✅ | vendored dsh + phone profile + 自定义 launcher（绕开 boot/loader） | PC 验证：`launcher.mjs` 用 zen 端点完成一次问答（exit 0） |
| **P1b** ✅ | 运行时打包进 assets 并由 `NodeRuntime` 解压启动 | 真机：dsh 在内嵌 Node 24 上启动，zen 完成问答 `2+2 equals 4.`；会话落盘 `session.v4.jsonl.zstd` |
| **P1c** ✅ | Kotlin BridgeServer + android 插件（7 个设备工具） | 真机：dsh 调 `read_ui_tree` 后回答 `pkg=com.android.launcher，text-node=45`；任务结束进程存活 |
| **P2** 🟡 | QQ 端到端：群聊中结合上下文发言（@/回复、联系人聊天待测） | 真机在目标群成功发送并自证（内容与群名不入库） |
| **P3** 🟡 | 产品化：SDK JSON-RPC（stdio 经 nodejs-mobile channel）或 WebView；前台服务保活；审批策略；会话落 SQLCipher（可选，实现 `SessionPersistence` seam） | 日常可用（RPC ✅ 2026-10-03） |

## 5. P0/P1 待回答的问题（fail fast）

1. fork 的 `node::Start` 与 sample 一致？启动后 `process.version` 是否为 24.18？
2. `NODE_OPTIONS` / `TMPDIR` / `HOME` 在 fork 的凭证补丁下是否生效？
3. dsh 在移动构建上的 ESM 动态 import 是否全部可用（无 child_process 路径）？
4. `session-persistence-jsonl` 的 flock lease 在无 `node-addon-system` 时是否优雅回退？
5. 资产提取与首次启动耗时（node_modules 文件数 → 用 `NODE_COMPILE_CACHE` 缓解）。
6. APK 体积（libnode 24MB + dsh 运行时）与冷启动内存是否可接受。

## 6. 风险

- dsh 仍是 developer preview：锁定版本、把 profile patch 与插件当作适配层维护。
- npm 供应链 + 无障碍权限：运行时全量 vendored、设备桥只监听 127.0.0.1 + token、
  不开放在设备上安装插件。
- ColorOS 冻结：Node 线程必须挂在前台服务里（旧探针期结论）。
- 体积/内存：libnode + dsh 会让 APK 明显变大；P1 决定是否需要裁剪 dsh 包集合。

## 7. P0 实测记录（2026-10-03）

- 工具链：`ndk;29.0.14206865` + `cmake;3.31.6`（sdkmanager 安装，走系统代理）。
- 构建：`ndkVersion`、`abiFilters = arm64-v8a`、`externalNativeBuild.cmake`。
- **两个必须记住的坑**：
  1. Node 24 头文件（cppgc）要求 **C++20**（`set(CMAKE_CXX_STANDARD 20)`），
     否则 `unknown type name 'concept'` 编译失败；
  2. `libnode.so` 依赖 **`libc++_shared.so`**，AGP 不会自动打包 —— 由
     `tools/fetch-libnode.ps1` 从 NDK sysroot 复制到 `jniLibs/`，否则运行时
     `dlopen failed: library "libc++_shared.so" not found`。
- 产物：`libnode.so` 24MB/ABI + `libc++_shared.so`；debug APK 约 96MB。
- 触发自检（安装后应用处于 stopped，广播要带 `--include-stopped-packages`）：

```powershell
adb shell am broadcast --include-stopped-packages `
  -n com.zoneu.app/.node.NodeDebugReceiver `
  -a com.zoneu.app.debug.NODE --es op START
```

**P0 结论**：Android 上嵌入 Node 24 可用，环境变量与数据目录按 EMBEDDING.md
设置后一切正常；可以进入 P1（vendored dsh + phone profile）。

### P1a 实测记录（2026-10-03，PC 侧验证）

- **dsh 官方 `boot()`/CLI loader 在 Android 上不可用**：host preparation 硬依赖
  `node-addon-require-builtin`（只有 darwin/linux/win32 预编译）。解法是**绕开 boot()**：
  用 `loadProfileDirectory` + `readProfilePatches` + `composeEntries` 这些纯函数取出
  profile 的有效 rows，再由 Cordis 直接并发 `ctx.plugin` mount；配置交给 Cordis 用
  各插件的 Schemastery `Config` 解析（不要手动再解析一次）。
- 两个 mount 细节：传给 `ctx.plugin` 的必须是含 `inject`/`name` 的**完整插件定义**
  （default 导出或模块命名空间），只传 `apply` 裸函数会丢依赖声明；`typert*` 行要求
  `ctx.baseUrl` 锚点（Web/客户端能力），phone profile 里禁用。
- **会话写租约**：非 win32 平台走 `@deepseek-ai/node-addon-system/flock`（同样没有
  Android 预编译）。内嵌 Node 是单进程、无跨进程争用，由
  `tools/dsh-phone/patch-runtime.mjs` 在 Android 上放行（幂等补丁）。
- **opencode zen 强制要求 `x-opencode-session` 头**，已在 pi-ai 路由的 `headers` 中补上。
- 运行时 staging：`npm install @deepseek-ai/dsh@0.2.0-rc.2 --os=android --cpu=arm64`
  = 222MB / ~25.8k 文件（对比不限平台 452MB），由 `tools/bundle-dsh.ps1` 打包成
  assets 里的 zip。`android/.dsh-cache/runtime` 是**未打补丁**的 npm 原始树：每次装配
  复制到 `.dsh-cache/staging` 后才打补丁、放插件并压 zip。插件测试直接读原始树，
  所以先打包再跑测试也不会被补丁污染。
- 验证：仓库版 `tools/dsh-phone/launcher.mjs` 在 PC 上跑通完整内核 + zen 端点，
  `2+2 等于几？` → `2+2 等于 4。`（exit 0）。

### P1b 实测记录（2026-10-03，真机）

- `NodeRuntime` 在 APK 更新时把 `assets/dsh-runtime.zip`（64.8MB）解压到
  `files/dsh-runtime`：**29,118 个文件 / 3.5–4.5s**，可接受。
- **`process.exit()` 会终止宿主 App 进程**（内嵌 Node 无独立进程）。P1 验收没问题，
  产品形态要改成常驻 runtime + RPC（P3），任务结束不能 exit。
- jsonl 持久化用硬链接发布文件，Android 沙箱对 app data 的 `link()` 返回
  `EACCES` → `patch-runtime.mjs` 增加第二个补丁：发布改为 `rename`（单进程下语义足够）。
- 真机结果：`adb broadcast` 触发任务 → NODEJS-MOBILE 输出 `2+2 equals 4.`；
  `dsh-home/sessions/.../session.v4.jsonl.zstd`（5.4KB）落盘。
- 操作坑：`adb shell am broadcast --es task "含空格的文本"` 会被设备 shell 拆词，
  必须整条命令再包一层引号（`adb shell "am broadcast ... --es task '...'"`）。

### P1c 实测记录（2026-10-03，真机）

- **通道**：`DeviceBridgeServer`（Kotlin，只监听 127.0.0.1 随机端口 + 每次进程启动
  随机 token）把 8 个 `DeviceAction` 暴露成 `POST /action`；dsh 插件
  `dsh-plugin-android-tools`（`ctx.tools`）把动作注册成工具，经 env
  `ANDROID_BRIDGE_URL/TOKEN` 找到桥。未连接无障碍服务时返回明确失败。
- 工具：`read_ui_tree` / `tap_node(ref)` / `tap_point(x,y)` / `type_text` / `press_back` /
  `launch_app(query)` / `swipe(direction)`；描述里写明「先读屏、操作后再确认」。
- **`process.exit()` 会 abort 宿主进程**（FORTIFY: pthread_mutex_lock on destroyed
  mutex → SIGABRT）：launcher 改为任务结束后不 exit，只记录 `task finished code=0`。
  代价是 P1 期间一个 App 进程只跑一次任务；常驻 runtime + RPC 是 P3 的正式形态。
- 验证：任务「Call read_ui_tree once, then reply with ONLY the package name and the
  text-node count」→ 真机输出 `pkg=com.android.launcher，text-node（TextView）数量=45。`，
  App 进程存活。

### P2 实测记录（2026-10-03，真机；不含群名与聊天内容）

- 任务：用户授权后在指定 QQ 群里结合上下文发言。dsh 的动作序列（从输出可复盘）：
  `launch_app("QQ")` → `read_ui_tree` 找到置顶群（核实标题群名一致、群人数）→
  `read_ui_tree` 读取近期聊天（话题：升学、地域打趣、回老家）→ `type_text` 一条
  结合「回老家」话题的自然发言 → `tap_node` 点「发送」→ 用「查找聊天记录」搜索
  关键词自证消息已入库 → 报告完成。
- **约束生效**：任务文本里写明「用户已明确授权」并给出「核实群名不一致就退出重找」
  的检查步骤；系统提示里的确认规则没有被误触发。
- **插件拆分（2026-10-03）**：聊天页辅助曾拆出为 `dsh-plugin-chat-tools`；后按通用 mobile
  agent 方向下线，phone profile 不再挂载、bundle 也不再打包它。`dsh-plugin-android-tools`
  收敛为 9 个通用原语（8 个桥动作 + 本地 wait）；chat-tools submodule 仅保留为可选源码。
- **一个进程一次任务（P2 时的限制）**：第二次任务在同一 App 进程内会起第二个 Node 实例而挂起
  （内嵌 Node 单实例约束）→ 每次任务前先 `am force-stop` 再广播。
  **P3 已解决**：改为常驻 runtime + SDK stdio JSON-RPC，同一进程可连续任务（见下）。

### P3 实测记录（2026-10-03，真机；SDK stdio JSON-RPC）

- **形态**：launcher 挂 `@deepseek-ai/dsh-sdk-jsonrpc-server`（profile 里 insert；不再用
  `dsh-headless` 的 headless-runner）。Kotlin `NodeRuntime` 当 SDK client：
  `initialize` 握手后，`session/prompt` 提交任务，进度走 `session.event` /
  `session.status` 通知。**stdout 是协议独占**，phone profile 不挂 stdout logger；
  `NODEJS-MOBILE` 只承载 stderr 诊断。
- **native**：`prepareStdio()` 预建 stdin/stdout/stderr 管道（stdin 由 Kotlin 经 JNI
  写入；stdout 逐行回调 Kotlin，不落 logcat；stderr 进 logcat）。
- **验证**：initialize 往返 ~0.45s；同一进程连续 prompt #2/#3；`read_ui_tree` 任务
  ~3.2s 返回 `com.oplus.safecenter 21`（包名 + 节点数）；无需 force-stop。
- **踩坑（都已修）**：
  1. `prepareStdio` 必须在 `node::Start` 前取到 JavaVM，否则 stdout 线程 attach 失败、
     帧只能丢；
  2. `initialize` 必须等状态到 Running（管道建好）再写 stdin，否则报 "stdin 不可写"；
  3. pi-ai 路由会校验 `reasoningEffort` 白名单，`low` 被拒 → 暂不传，用模型默认；
  4. debug 广播别用 `goAsync` 等任务完成：Broadcast Timeout 60s 会触发 ANR 杀进程。
- **仍缺**：前台服务保活（跨 App 任务时 ColorOS 会冻结）、Chat UI 接事件流 → 同日晚已接入，见 P4。

### P4 实测记录（2026-10-03，Android 16 / API 36 x86_64 模拟器）

- **前台服务 + Chat UI 事件流已接入**：`NodeTaskService`（`dataSync` 前台服务，任务期间常驻）+
  `DshStreamer`（dsh 事件 → 聊天流：状态文案 → 助手正文）。聊天页默认走 DshStreamer，
  手写 `LlmClient` 仅保留参考，不再实现 `ChatStreamer`。
- **模拟器路径**：API 36 `google_apis;x86_64` 镜像 + WHPX；`fetch-libnode.ps1 -Abi x86_64` +
  `:app:assembleDebug -Pzoneu.abis=x86_64`（默认 ABI 仍是真机 arm64）。端到端任务
  （「打开设置读主要选项」「读当前屏幕」）均通过；单测通过。
- **本轮修的两个 bug**：
  1. **会话 id 冲突**：dsh 会话持久化在 `DSH_HOME`，App 进程重启后再用同一 id `session/prompt`
     会报 `-32603 session "..." already exists`（SDK 只有 create 语义，没有 resume/load）。
     修法：`NodeRuntime` 维护「调用方 sessionId → 本次进程 dsh sessionId（加 runtimeTag 后缀）」，
     `dshEvents` 再把事件里的 dsh id 映射回调用方 id（收集方过滤逻辑不变）。
     代价：进程重启后 dsh 上下文不延续（UI 历史仍在 Room；跨重启 resume 需等 SDK 支持）。
  2. **状态文案残留**：`ChatViewModel` 收到 `Status` 只清了本地缓冲，`appendText` 仍往气泡里
     追加，最终回答前会留着「正在调用 read_ui_tree…」。修法：第一条 `TextDelta` 用
     `replace = true` 顶掉状态（新增回归单测）。
- **仍缺**：真机回归（ColorOS 前台服务冻结策略、通知权限）、任务「停止」目前只停 UI 收集，
  dsh 侧无取消方法。

### P5 实测记录（2026-10-03，一加 15T / ColorOS；dsh Web UI 嵌入）

- **流程**：arm64 `assembleDebug` → `adb install -r` → 重新启用无障碍 → 启动 App。
- **结果**：内嵌 Node 启动 ~1s、web server 绑定随机端口，WebView 完整渲染 dsh GUI；
  WebView 里发「Read the current screen…」，agent 5 秒完成，经 android-tools 调 `read_ui_tree`
  正确回答当前是 zoneu（`com.zoneu.app`）——WebView → gateway → session → phone preset →
  无障碍桥整条链路在真机打通。
- **坑（已修）**：
  1. **WebView 白屏**：ColorOS 上 `html/body` 的 `height:100%` 与 `100vh` 都解析成 0px
     （`innerHeight` 正常），整页高度 0；DOM 正常、Chromium 自己的截图也是白的。
     修法：`onPageFinished` 注入 `window.innerHeight` 像素高度 + resize 重算
     （`WebAgentScreen.kt`）；软件层渲染不是根因。
  2. **冷启动窗口**：首次解压 + ~190 行 mount 需 ~75s，URL 轮询窗口从 60s 提到 5min。
  3. **welcome 弹窗**：`settings.write` 依赖 file-backed `include` 树的 config-editor，
     当前 rows 直接挂 `loader.root`，ack 必然失败 → 暂以运行时补丁隐藏 Preview Notice；
     正式修法 = launcher 改用 `@deepseek-ai/cordis-plugin-include`。
- **仍缺**：terminal/HMR/account/workspace-files 等少量 UI 行在手机组合下 pending（不阻塞）；
  file-backed include 以恢复设置页可写。

### P6 混合原生：聊天回到 Compose，Web 只当预览面板（2026-10-03）

- **决策**：P5 让 WebView 变成默认页，观感不像原生、也绕开了已有的 Room 会话与
  DshStreamer 事件流。改走「混合原生」：**聊天本身是 Compose 原生**，WebView 只在
  需要 HTML / Office / diff / 复杂富文本时按需打开（顶栏与侧栏的 Web 入口）。
- **导航**：`Main` = `ChatScreen`（默认），`WebPreview` = `WebAgentScreen(onBack)`，
  `Probe` = 四问探针；Web 页顶部有返回栏，不再霸占首页。
  （2026-10-17：这两个入口连同页面一起删除，见 README 的「收缩」一条。）
- **会话列表（原生）**：左侧抽屉列出全部会话（Recent first，来自 Room
  `observeSessions()`），支持新建 / 切换 / 删除（删除有确认弹窗，级联删消息与工具调用）。
  任务进行中禁止切会话 —— 事件流只认当前 sessionId，切走会把半截回复写到别的会话上。
- **Markdown 渲染（原生，零新依赖）**：`ui/chat/Markdown.kt` 是纯 Kotlin 解析器
  （标题 / 段落 / 列表 / 引用 / 分隔线 / 代码围栏，围栏没闭合时按到文本尾处理，正好适配流式），
  `ui/chat/MarkdownText.kt` 翻成 Compose（代码块等宽 + 横向滚动、行内代码 / 粗斜体 / 可点链接）。
  表格、公式、图片、HTML 仍走 Web 面板。
- **工具卡（原生）**：DshProtocol 现在把 `tool/call` 的 `callId/name/arguments` 与
  `tool/result` 的 `message.toolCallId/isError/content-text` 解出来，DshStreamer 转成
  `AgentStreamEvent.ToolCallStarted / ToolCallFinished`；ChatViewModel 一边上屏一边写进
  `tool_call` 表（挂在发起它的那条助手消息上），切会话 / 重启后按 messageId 归位。
  卡片默认一行（名字 + 参数摘要 + 执行中/完成/失败），点开看完整参数与结果（结果截断 1200 字）。
  流被停掉或出错时，还在「执行中」的卡片统一落成失败，避免下次进来一直转圈。
- **多 step 分隔**：一个 turn 里 dsh 每个 step 各发一条完整 assistant/message，之前会粘成一句；
  现在第二次起补一个空行。
- **冷启动体验**：DshStreamer 先发「正在启动 dsh 运行时…」再 initialize（首次解压可能几十秒），
  前台服务也在 initialize 之前启动，避免 mount 到一半被 ColorOS 冻结。
- **验证（JVM）**：`.gradlew.bat :app:testDebugUnitTest`（45 个用例，含新增
  MarkdownTest、DshProtocol 工具帧、ChatViewModel 工具卡 / 会话切换 / 删除）、
  `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 全绿。
- **验证（真机，一加 15T / ColorOS，2026-10-03）**：`adb install -r` → 重新启用无障碍 →
  冷启动原生聊天页正常（旧会话从 SQLCipher 里恢复，Markdown 斜体/行内代码渲染正确）；
  在输入框发「Call read_ui_tree once and reply with ONLY the package name.」：
  - 气泡先显示「已提交，等待模型…」+ 光标，发送键变「停止」；
  - **工具卡**按预期出现，收尾成「完成」，点开有「参数 / 结果」两栏；
  - 助手回答 `com.zoneu.app`（实际经无障碍读屏拿到），整条 dsh → 桥 → 无障碍链路在真机跑通；
  - 杀进程重进（force-stop + 重启）后，工具卡仍随消息一起从库里恢复 —— 落库 / 归位正确；
  - 会话抽屉列出会话（标题 + 时间）、新建 / 删按钮与底部 Web / 探针入口都在。
    （2026-10-17：底部那两个入口已删除，只剩「设置」。）
  - 坑：装完第一次用到无障碍时会弹 ColorOS「检测到 zoneu 获取无障碍权限」倒计时弹窗，
    要点「保持开启」；否则服务会被关掉（AGENTS.md 已记，这里再确认一次）。
  - 未覆盖：抽屉左滑手势、长按选中复制、Web 预览页的新返回栏（这几项靠 Compose 预览 / 后续真机补）。
- **仍缺**：Web 面板里的会话不进 Room（两套会话各自独立，暂不合并）；工具卡的 `meta`
  （dsh-tool-fs 的 diff 预览）没接；dsh 仍只有 step 级 assistant/message，token 级增量见下条更新；
  的；**2026-10-17 起 token 级增量已接上**（见下方「已知差异」那条的更新说明）。
  ColorOS 上「停止」只停 UI 收集，dsh 那一轮仍会跑完（SDK 无取消方法）。

### P7 通用原语收敛：chat-tools 下线（2026-10-04）

- **方向**：`android-tools` 只保留 App 无关的“手和眼”，不做聊天页专用语义；
  `collect_chat` / `read_chat` 不再挂载，通用 `wait` 搬进 `android-tools`。
- **工具（9 个）**：`read_ui_tree` / `tap_node` / `tap_point` / `long_press` /
  `type_text` / `press(back|home|recents|enter)` / `launch_app` / `swipe` / `wait`。
  Android 没有全局 Esc，取消/返回用 `press(back)`；`enter` 走 `ACTION_IME_ENTER`（Android 11+）。
- **读屏与长按**：`ScreenNode` 增加 `long_clickable` 标记；长按优先按 `ref` 中心点手势，
  坐标兜底，时长 300–2000ms，默认 700ms。
- **运行时**：phone profile 移除 chat-tools 的 preset/global insert；`bundle-dsh.ps1`
  从持久缓存删除旧包并只打 `android-tools`，避免 `tar *` 残留。
- **验证（JVM）**：插件 `npm test`（9 个工具 + bridge contract）；Android
  `:app:testDebugUnitTest`、`:app:assembleDebug` 全绿；运行时 zip 确认无
  `dsh-plugin-chat-tools`，profile 无 chat-tools。
- **验证（真机，一加 15T / ColorOS，2026-10-04）**：`read_ui_tree` / `long_press` /
  `press back` / `press home` / `press enter` 均 ok；同一个常驻 runtime 跑
  `read_ui_tree` 任务结果 `ok=true tools=1 errors=0`。

### P8 下线 wait + 原生「思考」折叠块（2026-10-05）

- **wait 下线**：等待交给模型自身的思考阶段与重复 `read_ui_tree` 轮询；插件面收敛为
  8 个通用原语（纯桥动作）。插件仓库提交 `feat: 下线 wait 工具`（smoke 断言 8 个工具、
  契约测试去掉 wait 分支），zoneu 更新 submodule 指针，phone profile 两处 persona 去掉
  「等待」；`bundle-dsh.ps1` 重打运行时，zip 内确认无 wait 工具、无「等待」文案。
- **原生思考折叠块（参考 dsh Web 端 `ReasoningRow`）**：`assistant/message` 的
  `message.content` 里 pi-ai adapter 会产出 `{type:'reasoning'}` block；`DshProtocol`
  此前只取 text 块，现在把 reasoning 解出来（多个 block 以换行拼接），`DshStreamer` 发
  `ReasoningDelta`（第二个 step 起补空行分隔），`ChatViewModel` 立即落库并在气泡渲染
  「思考」折叠行：默认折叠、收起显示首行摘要（去掉 `**`，对齐 Web 的 `firstLine`）、
  展开全文（次要字号 Markdown）、推理进行中时摘要呼吸提示；会话恢复后折叠块从
  `message.reasoning` 列回来。
- **已知差异**：Web 端逐 token 实时思考来自 web adapter 的 `assistant/live-chunk` 瞬态帧，
  SDK stdio 服务器不转发 `agent/assistant-stream`，所以原生块是 **step 级**（模型一步结束、
  工具调用/正文之前整段到达）；要逐 token 需另开通道，暂不做。

> **2026-10-17 更新**：已经另开通道了。`tools/dsh-phone/patch-runtime.mjs` 的补丁 4 让 SDK stdio
> 服务器把进程内的 `agent/assistant-stream` 转成 `session.stream` 通知；Kotlin 侧
> （`DshProtocol` / `DshStreamer` / `ChatViewModel`）逐字上屏、UI 50ms 合并刷新，步进成品
> （`assistant/message`）到达时**只替换本步**追加的文本（多步回答的前几步不会被吃掉）。
> 实测：一条 795 字的回复 = 128 个增量帧（`[zoneu] assistant stream end: frames=128 textChars=795`）。
- **验证（JVM）**：插件 `npm test`（8 个工具 + bridge contract）；Android
  `:app:testDebugUnitTest`（DshProtocol reasoning block、ChatViewModel reasoning 落库/恢复、
  ReasoningSummary）、`:app:assembleDebug` 全绿。
- **验证（真机，一加 15T / ColorOS，2026-10-06）**：装包后发
  「Call read_ui_tree once and reply with ONLY the package name.」：界面顺序为
  用户消息 → 思考折叠行（默认折叠，标题「思考」+ 首行摘要 + 展开按钮）→
  `read_ui_tree` 工具卡（完成）→ 回答 `com.zoneu.app`；点行内任意处展开出全文、
  按钮变「收起」，再点收回。`force-stop` 重启后折叠行、工具卡、回答均从 SQLCipher
  恢复（折叠行回到默认折叠）。旧会话里历史遗留的 wait 卡片不影响新运行时，
  新任务不会再产生 wait。

### P9 移除 chat-tools submodule（2026-10-06）

- **背景**：chat-tools 自 P7 起已不在 phone profile 挂载、`bundle-dsh.ps1` 也不再打包，
  只作为可选源码留着；这个 submodule 只会让新克隆多拉一个用不到的仓库。
- **改动**：`git rm tools/dsh-phone/plugins/chat-tools`（gitlink + `.gitmodules` section
  一并移除，现在只有 `android-tools`）；清理本机 `.git/modules` 与 `.git/config` 里的
  残留。`bundle-dsh.ps1` 里「从持久缓存删旧包」的逻辑保留——缓存目录是持久的，
  防 `tar *` 把历史残留重新打进运行时 zip。`dsh-plugin-chat-tools` 仓库本身仍在
  GitHub，只是 zoneu 不再引用。
- **验证**：`git submodule status` 仅剩 android-tools；全仓 grep 无活引用（只剩文档
  历史与 bundle 缓存清理）；`:app:testDebugUnitTest` 通过；新克隆的
  `git submodule update --init --recursive` 不再拉 chat-tools。

### P10 图像输入：工具结果带图给模型（2026-10-11）

**背景**：微信对无障碍隐藏控件树（见 `docs/android-accessibility.md` §9），截图兜底本是
自然的第二条路，但当时判定「dsh 的 pi-ai 适配器 text-only，工具结果交不了图像」——
那个结论**是错的**，真实情况是缺依赖，不是协议不支持。

**代码层面的结论（读 vendored 运行时得到）**：

| 环节 | 事实 |
|---|---|
| `ContentBlockMap` | `dsh-llm` 有 `image` block（`{type:'image', attachment: ImageAttachmentRef}`） |
| 工具结果 | `pi-ai` 的 `userContent()` **对 tool 角色同样生效**（`toolResultOf(..., userContent(...))`），`assertSupportedHistory` 只拒绝 assistant/system 带图 |
| 线上格式 | `@earendil-works/pi-ai` 的 openai-completions 适配器把 tool 结果里的图抽出来，跟在 tool 消息后发一条 user 消息，内容为 `image_url: data:<mime>;base64,...` |
| 模型 | 目录里 `deepseek-v4.1-flash` 本来就声明 `input` 含 `image` |
| 端点 | 实测 zen 接受 `image_url` 并正确识别内容（8×8 纯红 PNG → 回答「红色」） |
| 附件服务 | `llm-pi-ai` 用软查询 `resolveAttachments: () => ctx.get('attachments')`，挂上就通 |

**真正的堵点**：`@deepseek-ai/dsh-attachment-local` 依赖 `sharp` 做归一化与质量阶梯
编码，而 sharp 没有 Android 产物（bundle 用 `--os=android --cpu=arm64` 装，原生二进制
根本不在）→ 一存图就 `Could not load the "sharp" module using the android-arm64 runtime`。

**改动**：

- 新增 `tools/dsh-phone/android-attachments/`：纯 JS 的 `AttachmentStore` 实现
  （`ctx.attachments`），内容寻址（sha256 即文件名）、PNG/JPEG 头解析取尺寸、
  读回校验摘要。不重编码 —— 图由 Kotlin 侧按预算缩好再送进来。
- profile 禁用 `attachment-local` 并挂上它；模型条目显式声明
  `input: [text, image]`（不写的话默认只有 text，pi-ai 会以
  `does not support image input` 直接拒）。
- 设备层新增 `screenshot` 原子动作：`takeScreenshot` → 缩到最长边 2048（对齐 dsh 的
  2048px / 2048² 像素预算）→ PNG，超 900KB 转 JPEG q80 → payload 为
  `{mediaType,width,height,base64}` 的 JSON。
- 设备层新增 `screenshot` 原子动作（2026-10-17 起同时产出**帧**，工具名 `capture_screenshot`）：
  `takeScreenshot` → 缩到最长边 2048（对齐 dsh 的 2048px / 2048² 像素预算）→ PNG，超 900KB 转
  JPEG q80 → payload 为 `{mediaType,width,height,screenshotId,screenWidth,screenHeight,offsetX,offsetY,base64}`
  的 JSON。后六个字段是**帧契约**：模型只说图像像素，屏幕像素由执行层 `ScreenFrame.toScreen` 换算
  （见 `docs/android-accessibility.md` §9）。
- 插件新增 `capture_screenshot` 工具：取桥的 base64 → `ctx.attachments.saveImage` → `render` 出
  `[text, image]`，其中文本是**帧图例**（图像尺寸 / 屏幕尺寸 / screenshot_id）—— 模型只看得见文本，
  尺寸不写进文本它就拿不到。没有附件服务或桥没回契约形状时退回纯文字，不抛错。

**预算与代价**：pi-ai 默认请求图像上限 20MiB（base64 计），每轮请求都会重发历史图；
单图原始字节软目标 1MiB。`image-offload`（`@deepseek-ai/dsh-compaction-image-offload`）
**已在 phone profile 里启用**（2026-10-17，它是纯 JS、无原生依赖）：超预算时把最老的图换成占位符
再重试，而不是整轮硬失败（`IMAGE_OFFLOAD_REQUIRED`）。代价要如实记着：**offload = 永久丢帧**，
模型之后拿不到那张图（占位文本里的路径读不了 —— `tool-fs` 在 phone profile 里是禁用的）。
长视觉任务仍应尽量少截图。

**已知限制**：不重编码 ⇒ 路由的 `maxBytes` 只是软目标，图不会按质量阶梯缩小；
只认 PNG/JPEG（WebP 会被 `UNSUPPORTED_IMAGE_TYPE` 拒）；`screenshot` 的 payload
是 base64 大块，`DeviceActionReceiver` 对超过 4000 字符的 payload 不落日志。

**验证（2026-10-11，一加 15T / ColorOS）**：

- PC：`node tools/dsh-phone/android-attachments/test.mjs`（契约与边界：尺寸解析、
  类型不符、垃圾字节、内容寻址、摘要校验、非法引用、体积/尺寸上限、请求版本）。
- 插件：`npm test`（smoke / bridge contract / **真实 defineTool 校验**）。
- 真机：任务「只调 screenshot 描述屏幕」→ 1 次工具调用，模型逐条读出状态栏
  （时间、蓝牙、Wi-Fi、5G、电量）、小组件文字、各 App 图标名与 Dock 四个图标；
  同会话第二轮再截一张，正确指出「唯一变化是时间 22:00 → 22:01」——
  证明图确实进了请求（而不是模型猜的），且历史里的图能重放。

**踩坑（值得记）**：dsh 的 value schema DSL 要求对象根**显式**写
`additionalProperties: true|false`，漏了 `defineTool` 会抛错，而它一抛错
**整个插件注册的工具全没了** —— 现象是模型侧 `tools: []`，模型只能对着 persona
猜工具名（还真猜出了 read_ui_tree/tap/swipe 这些）。`test/smoke.mjs` 用 stub 顶掉了
`defineTool`，拦不住；新增的 `test/real-schema.mjs` 自建沙箱（拷源码 + junction 接
运行时依赖）跑真校验，才把这类错误挡在 PC 侧。

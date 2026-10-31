# dsh on Android：内嵌运行时

唯一的运行时是 App 内嵌的 Node 24（nodejs-mobile 的 `libnode.so`）加 vendored dsh。
宿主是一个进程：Kotlin/Compose 与 Node 同生共死，stdout 是协议通道，stderr 才是日志。

改 `NodeRuntime`、launcher、profile、运行时补丁或装配脚本之前先读本页。

## 1. 架构

```
zoneu APK（单进程）
├─ Kotlin / Compose：聊天、设置、审批面板
├─ AccessibilityService + DeviceBridgeServer（127.0.0.1 + token）
├─ ConnectorBridgeServer / UserQuestionBridgeServer / ApprovalBridgeServer
└─ NodeRuntime（JNI → libnode.so，后台线程）
     └─ dsh（vendored，phone profile）
          ├─ llm-pi-ai → 当前启用的提供商
          ├─ session / tools / system-prompt / agent-loop
          ├─ sessions：jsonl（SQLCipher 之外的会话文件）
          └─ 插件：android-tools、gmail、approval、user-questions、web seam
```

- **内嵌而不是外部进程**：产品形态是一个 APK，用户不需要另装终端。
- **插件而不是 MCP**：设备工具只需在 `ctx.tools` 注册并 `fetch` 本地桥；MCP 需要实现完整
  server 与会话协商，代码量大一个量级。桥只监听 `127.0.0.1` 并带每进程随机 token。
- **密钥**：只在 Node 启动时以 `ZONEU_LLM_KEY_<ID>` 等环境变量传入，不写
  `dsh-home/runtime-config.json`。

## 2. 启动与加载

dsh 官方的 `boot()` / CLI loader 在 Android 上不可用：host preparation 硬依赖
`node-addon-require-builtin`，它没有 Android 预编译。用 `tools/dsh-phone/launcher.mjs`：

1. 用 `loadProfileDirectory` + `readProfilePatches` + `composeEntries` 取出 profile 的有效行；
2. 交给 Cordis 并发 `ctx.plugin` mount；
3. 配置由各插件的 `Config` schema 解析，不手动解析第二次。

传 `ctx.plugin` 的必须是含 `inject` / `name` 的完整插件定义；只传 `apply` 裸函数会丢依赖声明。

`tools/dsh-phone/patch-runtime.mjs` 的两个补丁是必需的：

- 放行会话写租约（`flock` 无 Android 产物；内嵌 Node 单进程，无跨进程争用）；
- jsonl 发布从硬链接改为 `rename`（Android 沙箱对 app data 的 `link()` 返回 `EACCES`）。

补丁幂等，锚点不匹配直接抛错，不静默跳过。

## 3. 硬规则

- **不调用 `process.exit()`**，包括 SDK 的 `shutdown`。Node 与宿主同进程，退出就是 App 崩。
  只记录、不退出。
- **stdout 只能走 JSON-RPC**。phone profile 不挂 stdout logger；所有诊断走 stderr。
- **`prepareStdio()` 必须在 `node::Start` 之前取到 JavaVM**，否则 stdout 线程 attach 失败。
- **`initialize` 必须等状态到 Running 再写 stdin**，否则报「stdin 不可写」。
- **会话 id 稳定，重启/重载后 resume**。JSON-RPC server 原本只暴露 create；zoneu 的运行时
  补丁加了 `session/resume`：加载磁盘会话、补写中断 turn 的结束事件、丢掉上一个进程留下的
  排队输入。`NodeRuntime` 在每次运行时实例里第一次用到某会话时 resume 一次，磁盘上没有才
  由 `session/prompt` 新建。不要让 id 再随运行时变化 —— 那等于每次重启都丢上下文。
- **卡死恢复用 `session/cancel`**：30 秒内没等到 `running` 时宿主中断该 turn 并清空排队
  输入，下一轮沿用同一段上下文；不要靠换会话 id 绕过。
- **profile 是启动路径**：一份坏 YAML 或一个 mount 失败会让整棵 cordis 根起不来，而 Node
  与宿主同进程，表现是整个 App 起不来。`bundle-dsh.ps1` 用真实 parser 校验。
- **工具 schema 编译失败会丢掉整个插件的全部工具**，模型侧只看到 `tools: []`，不会报错。
  插件测试必须用真实 `defineTool`（`real-schema.mjs`）校验。

## 4. 重载与配置

宿主写非密的 `dsh-home/runtime-config.json`，再发 `runtime.reload`；launcher 重挂 cordis 根，
重新 `initialize`。重载不重启 App，会话 id 不变，下一次 prompt 前会 `session/resume`
把磁盘会话接回来，模型上下文继续。

提供商、模型、密钥变化都走这条路径。细节见 [providers.md](providers.md)。

## 5. 产物与装配

以下都是生成物，不入库：

| 产物 | 生成 |
|---|---|
| `libnode/`、`jniLibs/*/libnode.so`、`libc++_shared.so` | `tools/fetch-libnode.ps1` |
| `assets/dsh-runtime.zip` | `tools/bundle-dsh.ps1`（pinned dsh + profile + 插件 + 补丁） |
| sherpa / proot / rg 原生库 | `tools/fetch-sherpa.ps1` / `fetch-proot.ps1` / `fetch-rg.ps1` |

插件源码在 `tools/dsh-phone/plugins/`，随本仓库提交；改完 `bundle-dsh.ps1` 并重装 APK。插件名在 `package.json`、profile insert、
`bundle-dsh.ps1` 的 `Package` 三处必须逐字相同；`NodeRuntimeProfileTest` 钉住。

装配顺序与门禁见 [build.md](build.md)。

## 6. 移动端限制

- 无 `child_process` / `fork`；`node-pty`、`sharp`、`node-addon-system` 都没有 Android 产物，
  phone profile 不挂依赖它们的插件。
- `@deepseek-ai/dsh-attachment-local` 依赖 `sharp`，用自带的 `android-attachments` 替代。
- 图像输入：`pi-ai` 的 openai-completions 适配器会把工具结果里的图抽成后续 user 消息。
  模型条目必须声明 `input: [text, image]`，否则请求会被拒。
- 单进程单实例：一个 App 进程只跑一个 Node runtime；任务与重载串行在同一个 runtime 上。
- 会话持久化在 `dsh-home/sessions/`，与 Room 的聊天历史是两套数据。

## 7. 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\tools\bundle-dsh.ps1
# 重装 APK 后跑一个真实任务；启动失败看 logcat -s NODEJS-MOBILE ZoneuNode
```

CI 绿不等于运行时可用：内嵌 Node 启动、profile mount 和真实任务必须在设备上过一遍。

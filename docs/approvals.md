# 审批（approval）：危险动作的确认机制

> 状态：**已接线，PC 侧测试通过，真机待验**（2026-10-22）。
> 能力面还没有写工具，所以这套机制现在是**基础设施**：第一个写工具落地时直接用。
> 本文是 `AGENTS.md` §6 那句「写操作必须先过审批」的实现说明。

## 0. 为什么用 dsh 官方的 seam，而不是自己造

dsh 已经有一整套权限链，写工具应当接它：

```
tools/pre-execute 瀑布（返回 { kind: 'ask', reason, displayReason }）
        │
        ▼
@deepseek-ai/dsh-user-approval（ctx.approval）
  · 会话策略 ask / never
  · 把请求交给 approval/request 瀑布上的 answerer
  · 审计：approval/asked + approval/decided 写进会话日志
        │
        ▼
answerer（本仓库的 dsh-plugin-approval → 本地审批桥 → Kotlin 确认面板）
```

**结果词汇是封闭的**：`allowed-once` / `rejected` / `cancelled` / `unavailable`。
只有 `allowed-once` 是授权；**没有 answerer 时服务自己就以 `unavailable` 关闭（fail closed）**。
这是官方语义，不是我们的约定 —— 自己写一个确认弹窗会漏掉审计、会话策略与 fail-closed。

## 1. 四处接线

| 位置 | 管什么 |
|---|---|
| `tools/dsh-phone/profile/cordis.patch.yml` | 把 `approval.policy` 覆盖回 `ask`；插入 answerer 插件 |
| `tools/dsh-phone/plugins/approval/` | `approval/request` answerer：把请求转给本地桥，回一个封闭结果 |
| `bridge/ApprovalBridgeServer.kt` | 第四个本地桥（127.0.0.1 + 每进程随机 token），阻塞等用户决定 |
| `agent/ApprovalHub.kt` + `ui/chat/ApprovalSheet.kt` | 进程级待审批状态 + Compose 确认面板 |

**插件名三处逐字相同**：`plugins/approval/package.json` 的 `name`、profile 的 insert `name`、
`bundle-dsh.ps1` 的 `Package`。`NodeRuntimeProfileTest` 钉住了这三处与 `policy: ask`。

### 1.1 为什么 profile 要覆盖 policy

`dsh-base` 已经挂了 `@deepseek-ai/dsh-user-approval`，但它的默认配置是：

```yaml
policy: !!js "(process.env.DSH_PERMISSION_MODE ?? 'workspace-write') === 'danger-full-access' ? 'never' : 'ask'"
```

宿主设了 `DSH_PERMISSION_MODE=danger-full-access`（沙箱语义），于是策略被读成 **`never`**：
所有 `ask` 都会被确定性拒绝，面板永远不会出现。手机没有沙箱，所以 profile 里单独覆盖：

```yaml
- id: approval
  config:
    policy: ask
```

**不要把 `DSH_PERMISSION_MODE` 改掉来达到这个目的**：它同时是沙箱模式，改它会让别的消费者
看到不同的运行时语义。审批策略只在 profile 里改。

### 1.2 为什么 answerer 用 `prepend: true`

`dsh-api-remotes` 也挂在 `approval/request` 上，把请求推进 remote 队列等**浏览器**回答。
手机上没有人消费那条通道，排在它后面就会永远等下去。与 `dsh-plugin-user-questions` 同一条理由。

### 1.3 桥不可用时返回 `unavailable`，不 `next()`

这一点与提问插件不同。提问插件接不了就 `next()` 让路；审批插件**自己给出 `unavailable`**：

- `unavailable` 是 dsh 定义的 fail-closed 结果，模型立刻拿到「这次没被允许」，任务可以继续；
- `next()` 会落到等浏览器的 `dsh-api-remotes` 上，把整轮任务挂住 —— 手机上没有人会来回答。

宁可拒绝，不要挂起。

## 2. 语义（都是 dsh 的，不要自创）

- **一次性授权**：`allowed-once` 只对这一次调用有效。dsh 的词汇里没有「记住这条规则」。
- **全部允许是 App 侧的开关，不是 dsh 的 policy**：设置 → 执行层 → 写操作审批（默认「每次
  询问」）。打开后 `ApprovalBridgeServer` 收到请求直接回 `allowed-once`，面板不出现；请求仍
  走 `@deepseek-ai/dsh-user-approval` 与审计，变的只是谁来回答。策略存在
  `ApprovalSettings`（prefs `zoneu_approval_settings`），每次请求现读，改完立即生效，
  不需要重启 Node。
- **关闭 = 取消**：用户下滑关掉面板 → `cancelled`，不是 `rejected`；两者都不授权。
- **同时只允许一个待审批**：`ApprovalHub` 对第二个请求直接回 `CANCEL`（fail closed）。
  并行工具调用同时要权限时，不会出现「一次点击批准了两件事」。
- **超时按取消处理**：插件等 10 分钟仍无答案 → `cancelled`（不是 `unavailable`）。
- **只在不结束的轮次内有效**：`ctx.approval.request` 在空闲时调用会在审计前抛异常（dsh 的限制）。

## 3. 隐私：`reason` 会进日志，`displayReason` 不会

dsh 的审批请求有两个文案字段，**持久化行为不同**（`dsh-user-approval` 的类型注释）：

| 字段 | 去向 | 用法 |
|---|---|---|
| `reason` | 写进会话日志的 `approval/asked` | 只写**不含用户内容**的概括，例如「发送 1 封邮件」 |
| `displayReason` | 只给 UI，**不进审计** | 可以放收件人、主题等需要用户看清的细节 |

所以写工具的 `ask` 应当这样构造：`reason` 说动作类别，`displayReason.zh` 说具体内容。
**正文永远不进任何一个字段** —— 审批只需要让用户判断「发不发、发给谁」，不需要给他看全文。

Kotlin 侧同样不记 `reason` / `displayReason`：`ApprovalBridgeServer` 的日志只有
`approve tool=<name> decision=<outcome>`。

## 4. 怎么给新写工具挂审批

官方路径是在一个 `tools/pre-execute` 监听器里返回 `ask`（不要在工具体里手写确认逻辑）：

```js
ctx.on('tools/pre-execute', (exec, next) => {
  if (!WRITE_TOOLS.has(exec.name)) return next()
  return {
    kind: 'ask',
    reason: '发送 1 封邮件',                       // 会进审计日志：不要放用户内容
    displayReason: { en: 'Send 1 email', zh: `发送给 ${to}：${subject}` }, // 只给 UI
  }
})
```

要点：

- **闸门放在桥 / 管道层，不放在模型侧**（模型侧的约束一律不可信，roadmap §3.2）。
- `ask` 只对声明了的写工具生效；读工具保持 `allow`。
- 加写工具时，`docs/email-connector.md` §1.1 那条「出网请求全是 GET」的断言要换成
  「每个写工具都挂了审批、非 GET 只出现在 `allowed-once` 之后」。
- 拒绝后的工具结果要可行动（「用户未允许这次发送」），不要把 `rejected` 伪装成网络错误。

**已落地**：Gmail 的 `mail_modify` / `mail_draft` / `mail_send` / `mail_save_attachment`
就是按这个形状接的（`tools/dsh-phone/plugins/gmail/index.mjs` 的 `WRITE_TOOLS` 与 `approvalFor`）。
`reason` 只写动作类别（「发送 1 封邮件」），收件人与主题放在 `displayReason`；
插件测试同时断言四个写工具都被判成 `ask`、读工具放行。

**设备侧写操作**（`dsh-plugin-android-tools`）同一套：`set_alarm` / `add_calendar_event` /
`update_calendar_event` / `delete_calendar_event` / 三条定时任务工具 / **`run_command`**
都在 `tools/pre-execute` 判 `ask`，读工具（`list_calendar_events` 等）放行。前几条之所以
**必须**进闸门：它们是**静默写**（`ContentResolver` / `EXTRA_SKIP_UI`），系统界面不再替用户确认；
`run_command` 则是**真 shell**，能写文件、装包、删东西。

`run_command` 的 `reason` 固定写「执行 shell 命令」，**命令原文只进 `displayReason`** ——
审计日志里不落命令内容（那里面可能有路径、口令或文件内容）。确认面板上看得见要跑什么，
才有「允许」的意义。
成功之后由 `device/DeviceNotices.kt` 发一条回执通知（ColorOS 上支持时走流体云），
`reason` 里只放动作与 id / 标题，不放屏幕内容。

## 5. 怎么验

PC 侧（已跑过）：

```powershell
node tools/dsh-phone/plugins/approval/test/smoke.mjs     # 注册在 approval/request，prepend
node tools/dsh-phone/plugins/approval/test/answerer.mjs  # 映射 / fail closed / 取消
.\gradlew.bat :app:testDebugUnitTest --tests "com.zoneu.app.agent.ApprovalHubTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.zoneu.app.node.NodeRuntimeProfileTest"
```

装配：

```powershell
.\tools\bundle-dsh.ps1
# zip 内应有 node_modules/dsh-plugin-approval/index.mjs
```

真机（**还没做**）：

1. 装 APK，确认运行时 mount 成功（新增一行 profile 写坏会让整棵 cordis 根 mount 失败，
   而 Node 与宿主同进程 —— 表现是整个 App 起不来）。
2. 第一个写工具落地后跑一次真实任务，确认面板出现、允许 → 动作执行、拒绝 → 模型收到未允许。
3. `adb logcat -s ZoneuApproval` 只应出现工具名与结果，不应出现 `reason` / `displayReason`。

## 6. 已知限制

- 请求**不携带工具参数**：answerer 只看到 `toolName` / `reason` / `displayReason` / `callId`。
  要展示什么由发起方在 `displayReason` 里写好。
- **没有「记住这条规则」**：dsh 只有一次性授权，「全部允许」是 App 侧的全局开关（见 §2），
  粒度只有开 / 关，不能按工具或按收件人放行。
- **没有设备验证**：面板只能在真机上确认；PC 侧只覆盖了插件与 Hub 的逻辑。
- 审批策略 `never` 仍然可用（无人值守 / CI 场景）：把 profile 的 `policy: ask` 去掉即可。

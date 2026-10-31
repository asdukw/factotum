# 审批（approval）：危险动作的确认机制

所有写工具都走 dsh 官方的审批 seam，不自己造弹窗。新增写工具或改审批 UI 之前先读本页。

```
tools/pre-execute 返回 { kind: 'ask', reason, displayReason }
        │
        ▼
@deepseek-ai/dsh-user-approval（ctx.approval）
  · 会话策略 ask / never
  · 把请求交给 approval/request 瀑布上的 answerer
  · 审计：approval/asked + approval/decided
        │
        ▼
dsh-plugin-approval → ApprovalBridgeServer → Compose 确认面板
```

结果词汇是封闭的：`allowed-once` / `rejected` / `cancelled` / `unavailable`。
只有 `allowed-once` 是授权；没有 answerer 时服务以 `unavailable` 关闭（fail closed）。

## 1. 四处接线

| 位置 | 职责 |
|---|---|
| `tools/dsh-phone/profile/cordis.patch.yml` | 把 `approval.policy` 覆盖为 `ask`；插入 answerer |
| `tools/dsh-phone/plugins/approval/` | `approval/request` answerer |
| `bridge/ApprovalBridgeServer.kt` | 本地桥（127.0.0.1 + 每进程随机 token），阻塞等用户决定 |
| `agent/ApprovalHub.kt` + `ui/chat/ApprovalSheet.kt` | 进程级待审批状态 + 确认面板 |

插件名在 `plugins/approval/package.json`、profile insert、`bundle-dsh.ps1` 的 `Package`
三处逐字相同；`NodeRuntimeProfileTest` 钉住这三处与 `policy: ask`。

### 为什么 profile 覆盖 policy

`dsh-base` 的默认策略是 `DSH_PERMISSION_MODE === 'danger-full-access' ? 'never' : 'ask'`。
宿主设了 `danger-full-access`（沙箱语义），策略会被读成 `never`，所有 `ask` 都被确定性拒绝。
手机没有沙箱，所以在 profile 里单独覆盖 `policy: ask`，**不要**改 `DSH_PERMISSION_MODE`。

### 为什么 answerer 用 `prepend: true`

`dsh-api-remotes` 也挂在 `approval/request` 上，把请求推进等浏览器回答的 remote 队列。
手机上没人消费那条通道，排在它后面会永远等下去。

### 桥不可用时返回 `unavailable`

审批插件自己给出 `unavailable`，不 `next()`：模型立刻拿到「这次没被允许」，任务可以继续。
`next()` 会落到等浏览器的通道上，把整轮挂住。宁可拒绝，不要挂起。

## 2. 语义

- **一次性授权**：`allowed-once` 只对本次调用有效；dsh 没有「记住这条规则」。
- **全部允许是 App 侧开关**：设置 → 执行层 → 写操作审批（默认「每次询问」）。打开后
  `ApprovalBridgeServer` 直接回 `allowed-once`，面板不出现，但请求仍走审批 seam 与审计。
  策略存 `zoneu_approval_settings`，每次请求现读。
- **关闭 = 取消**：下滑关面板返回 `cancelled`，不是 `rejected`；两者都不授权。
- **同时只允许一个待审批**：第二个请求直接回 `CANCEL`（fail closed）。
- **超时按取消处理**：等 10 分钟无答案返回 `cancelled`。
- 只在未结束的轮次内有效；空闲时调用会在审计前抛异常（dsh 限制）。

## 3. 隐私：`reason` 会进日志，`displayReason` 不会

| 字段 | 去向 | 用法 |
|---|---|---|
| `reason` | 会话日志的 `approval/asked` | 只写不含用户内容的概括，如「发送 1 封邮件」 |
| `displayReason` | 只给 UI，不进审计 | 放收件人、主题、命令原文等需要用户看清的细节 |

正文永远不进任何一个字段。Kotlin 侧只记 `approve tool=<name> decision=<outcome>`。

## 4. 给新写工具挂审批

在 `tools/pre-execute` 监听器里返回 `ask`，不要在工具体里手写确认：

```js
ctx.on('tools/pre-execute', (exec, next) => {
  if (!WRITE_TOOLS.has(exec.name)) return next()
  return {
    kind: 'ask',
    reason: '发送 1 封邮件',
    displayReason: { en: 'Send 1 email', zh: `发送给 ${to}：${subject}` },
  }
})
```

- 闸门放在桥 / 管道层，不放模型侧；模型侧约束一律不可信。
- 读工具保持 `allow`。
- 拒绝后的工具结果要可行动（「用户未允许这次发送」），不要把 `rejected` 伪装成网络错误。

**已接入**：Gmail 四个写工具；设备侧 `set_alarm` / `add_calendar_event` /
`update_calendar_event` / `delete_calendar_event` / 三个定时任务工具 / `run_command`。
日历与闹钟是静默写（`ContentResolver` / `EXTRA_SKIP_UI`），系统界面不再替用户确认；
`run_command` 是真 shell。`run_command` 的 `reason` 固定「执行 shell 命令」，命令原文只进
`displayReason`。成功后 `device/DeviceNotices.kt` 发回执通知。

### 角色卡与世界书

`list_character_cards` / `read_character_card` 是有界只读工具，不进写审批；其白名单结果会进
当前模型上下文。创建、下载导入与 `update_character_card` 走 `tools/pre-execute → ask`。

更新预检按真实当前版本计算变化字段、长度与条目增改删数量；`reason` 固定「修改角色卡」，
`displayReason` 只显示安全目标标识、字段名和数量，不放正文 / 关键词。审批与执行绑定相同参数
与 `expected_revision`，审批后再检查版本，冲突要求重读。无差异直接返回 unchanged，不申请写权限。
预检失败关闭执行。

世界书与角色仓库的写工具走同一条审批链；专用工具审批不等于文件系统沙箱，见
[worldbooks.md](worldbooks.md)。

## 5. 验证

```powershell
node tools/dsh-phone/plugins/approval/test/smoke.mjs
node tools/dsh-phone/plugins/approval/test/answerer.mjs
.\gradlew.bat :app:testDebugUnitTest --tests "com.zoneu.app.agent.ApprovalHubTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.zoneu.app.node.NodeRuntimeProfileTest"
.\tools\bundle-dsh.ps1
```

真机：装包确认运行时 mount 成功；跑一个写工具任务，确认面板出现、允许后执行、拒绝后模型
收到未允许；`adb logcat -s ZoneuApproval` 只出现工具名与结果。

## 6. 限制

- 请求不携带工具参数：answerer 只看得到 `toolName` / `reason` / `displayReason` / `callId`；
  要展示什么由发起方写进 `displayReason`。
- 没有「记住这条规则」；「全部允许」只有开 / 关，不能按工具或按收件人放行。
- 审批策略 `never` 仍可用（无人值守 / CI）；把 profile 的 `policy: ask` 去掉即可。

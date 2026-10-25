# 定时任务：Android 上没有 cron，我们用什么

> 状态：**代码完成、门禁全绿，真机未验**（2026-10-25：改为对话语义化创建，设置页入口已删）。
> 验证要点在 §6。
> 本文是 `AGENTS.md` §「安全与隐私红线」第 6 条要求的前置文档：定时任务是**第一条让 agent
> 在用户不在场时自己动起来**的能力，边界与失败行为必须先写清楚。

## 1. 没有 cron，也别假装有

Android 没有 crontab、没有常驻调度器 —— 后台执行是被刻意限制的（Doze、App Standby，
ColorOS 还会再叠一层）。可用的调度只有这几种：

| 机制 | 精度 | 代价 |
|---|---|---|
| **`AlarmManager.setExactAndAllowWhileIdle`** | 准点，Doze 下也能唤醒 | Android 12+ 要 `SCHEDULE_EXACT_ALARM`（特殊权限）；Doze 下**每应用约 9 分钟**才允许一次 |
| `AlarmManager.setWindow` | 15 分钟窗口内 | 无权限 |
| `WorkManager` Periodic | **最小 15 分钟**，不精确 | 无权限 |
| 前台服务 + 计时器 | 精确 | 常驻通知，进程被杀就没了 |

**所以任务规则只有一种：每天 HH:mm。** 不给 cron 表达式，因为它承诺不了 ——
更细的粒度需要常驻进程，而那正是 Android 一直在杀的东西。

**创建只有一条路：对话。** 用户说「每天早上八点提醒我看天气」，模型把它拆成
`title / prompt / hour / minute` 调 `schedule_task`；设置页没有定时任务入口。
查看、修改、停用、删除同样走工具（`list_scheduled_tasks` / `update_scheduled_task` /
`delete_scheduled_task`），见 §2.1。

## 2. 一次触发的完整路径

```
AlarmManager（精确或窗口）
  → ScheduledTaskReceiver（BroadcastReceiver）
      → NodeTaskService.start()          ← 先起前台服务：任务可能跑几分钟
      → ScheduledTaskRunner.run(taskId)
          → NodeRuntime.initialize + runTask（无 UI 的那条路）
          → 结果写回 scheduled_task 表（只记成功/失败码 + 时间）
          → 排下一次
      → NodeTaskService.stop()
```

**先起前台服务再干活**是必须的：`onReceive` 返回后没有 FGS，进程会被直接回收，
表现是「任务跑了一半没了」，而且没有任何报错。

### 2.1 模型可见的四个工具

写入这样一条链路：插件 → `DeviceBridgeServer` → `ScheduledTaskStore` → Room +
`ScheduledTaskScheduler`。**不经过无障碍**（与日历 / 闹钟同一类），所以没开无障碍也能建任务。

| 工具 | 做什么 | 审批 |
|---|---|---|
| `schedule_task(title, prompt, hour, minute)` | 建一条每天 HH:mm 的任务 | `ask` |
| `list_scheduled_tasks` | 列出 id / 标题 / 指令 / 时间 / 启用态 / 下次触发 / 上次结果 | 读，放行 |
| `update_scheduled_task(id, ...)` | 改时间 / 指令 / 标题，或 `enabled=false` 停用 | `ask` |
| `delete_scheduled_task(id)` | 删除并取消闹钟 | `ask` |

时间解析（「每天早上」「每晚十点」）由模型完成，桥只接受 0-23 / 0-59 的钟点；
越界回 `SCHEDULE_INVALID_TIME`，id 不存在回 `SCHEDULE_NOT_FOUND`（让模型先 list，不要按标题猜）。
没有精确闹钟权限时任务照样建，只是降级成 15 分钟窗口 —— 回执里会写明。

## 3. 精确与不精确：有降级，不是有闸门

和无障碍不同，这里**没有权限也能用**：

- 有 `SCHEDULE_EXACT_ALARM` → `setExactAndAllowWhileIdle`
- 没有 → `setWindow`（15 分钟窗口），照样跑，只是可能晚几分钟

**权限只在真的有需要时提醒，而且是主动的**：聊天页每次回到前台会查一次 ——
「至少有一条启用的定时任务」且「没有精确闹钟权限」时，顶部摆一条可点的提示（去系统开关）。
这条提醒**不能等失败**：缺权限时任务照跑、只是可能晚 15 分钟以上，永远不会报错。
用户授予权限后，`ScheduledTaskBootReceiver` 收到 `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`
会整体重排，把已有的窗口闹钟升级成精确闹钟（否则之前建的任务会一直晚到下次编辑）。

应用不在前台时，`ExactAlarmMonitor` 每 6 小时轮询一次：一条**非精确**的
`AlarmManager.set`（自续期的一次性闹钟，开机 / 改时间后由 boot receiver 重排）。
满足条件时发一条高优先级通知，最多**每 24 小时一次**；权限开好或任务删光后同一次检查会把
通知撤掉。用非精确闹钟是刻意的 —— 要提醒的正是「精确闹钟权限没开」，不能反过来依赖它。
通知正文只报任务条数，不报标题。

- Android 14 起新装默认拒绝这个权限；用户随时可以撤销。
- 撤销的瞬间 `setExactAndAllowWhileIdle` 会抛 `SecurityException` —— 代码里就地降级成
  `setWindow`，不让那条任务静默消失。
- **不要用 `USE_EXACT_ALARM`**：那个变体只允许「核心功能是闹钟/日历」的应用使用。

## 4. 错过与重启：不补跑

- alarm **不持久化**：重启后全没了。`ScheduledTaskBootReceiver` 在
  `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` 时整体重排。
- **错过的任务不补跑**：重排时按「今天这个点还没到就今天、否则明天」算，只跑下一次。
  开机瞬间连跑十条积压任务比漏掉更糟。
- 没开机、深度休眠期间错过的那一次就算了 —— 这一点在设置页的说明里写明。

## 5. 隐私与边界

1. **跑的是同一套 agent**：定时任务用 `NodeRuntime.runTask`，与聊天页那条路径同一个运行时、
   同一套工具权限。它能做的事 = 用户自己在聊天里说一句话能做的事，没有额外扩权。
2. **产出进会话库，不进这张表**：`scheduled_task` 只存标题 / 指令 / 时间 / 上次结果码。
   产出（屏幕内容、邮件正文等）走会话库，与服务端一样的加密与可见性。
3. **日志只记 task id 与结果码**：prompt 里可能有用户的私人内容，绝不打印。
4. **不做后台轮询**：没有「每隔 N 分钟看看有没有事」这种设计，只有用户明确设定的时刻。
5. **用户随时能停**：在对话里说「停掉每天八点的提醒」，模型用 `update_scheduled_task`
   （`enabled=false`）或 `delete_scheduled_task` 处理；停用 = 取消 alarm。

## 6. 验证到什么程度

| 项 | 状态 |
|---|---|
| `nextTrigger` 的边界（今天 / 明天 / 整点） | ✅ 单测（固定时区） |
| `parseTime` / `validClock` 的合法与非法输入 | ✅ 单测 |
| 桥的四个 action 名与解析器一致 | ✅ `DeviceBridgeActionContractTest` |
| 缺权限时的提醒判据（有启用任务 + 无权限） | ✅ 单测 |
| 真机：轮询闹钟已排上（`dumpsys alarm` 可见 `CHECK_EXACT_ALARM`，约 6 小时后） | ✅ 2026-10-25 |
| 真机：缺权限 → 聊天页顶部提示 → 授予后重排 | ❌ **未验**（当前设备权限是开的） |
| 真机：缺权限 → 通知提醒（24 小时内至多一条） | ❌ **未验** |
| 插件把三个写工具判成 `ask` | ✅ 插件 smoke |
| DB v1 → v2 迁移（只加表，不清库） | 需设备验证 |
| 真机：`schedule_task` → 到点触发 → 结果 | ❌ **未验** |
| 真机：到点触发 → 前台服务 → 任务跑完 → 记录 | ❌ **未验** |
| 真机：开机后重排 | ❌ 未验 |
| 无权限时的窗口降级 | ❌ 未验 |

**怎么验**：让模型建一条「每天 HH:mm」的任务（工具结果里能看到 id 与 next_trigger），
到点后 `adb logcat -s TappetSchedule` 应看到 `task <id> done error=-`；`adb logcat -s TappetSchedule` 应看到
`task <id> done error=-`；列表页的「上次已运行」应更新。

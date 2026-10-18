# 定时任务：Android 上没有 cron，我们用什么

> 状态：**代码完成、门禁全绿，真机未验**（2026-10-19）。验证要点在 §6。
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

## 3. 精确与不精确：有降级，不是有闸门

和无障碍不同，这里**没有权限也能用**：

- 有 `SCHEDULE_EXACT_ALARM` → `setExactAndAllowWhileIdle`
- 没有 → `setWindow`（15 分钟窗口），照样跑，只是可能晚几分钟

**权限只在用户真的建了一条准点任务时引导一次**（与无障碍同一套：不在启动时打扰）。
引导是一个对话框，说清「为什么需要」与「不开会怎样」，两个出口：去系统开关 / 先用不精确时间。

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
5. **用户随时能停**：列表页每条都能关（关 = 取消 alarm），也能删。

## 6. 验证到什么程度

| 项 | 状态 |
|---|---|
| `nextTrigger` 的边界（今天 / 明天 / 整点） | ✅ 单测（固定时区） |
| `parseTime` 的合法与非法输入 | ✅ 单测 |
| DB v1 → v2 迁移（只加表，不清库） | 需设备验证 |
| 真机：到点触发 → 前台服务 → 任务跑完 → 记录 | ❌ **未验** |
| 真机：开机后重排 | ❌ 未验 |
| 无权限时的窗口降级 | ❌ 未验 |

**怎么验**：建一条 2 分钟后的任务，锁屏等它响；`adb logcat -s TappetSchedule` 应看到
`task <id> done error=-`；列表页的「上次已运行」应更新。

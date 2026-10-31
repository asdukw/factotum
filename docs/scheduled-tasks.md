# 定时任务

定时任务通过对话创建、查询、修改、停用和删除，没有设置表单；设置页另有只读列表。支持每天、每周、每月、每年，以及指定公历日期单次执行；沿用现有 AlarmManager 和权限，不使用 cron 或高频后台轮询。

## 1. 自然语言与工具合同

| 用户表达示例 | schedule_type | 规则参数 |
|---|---|---|
| 每天早上八点查看天气 | `daily`（省略时默认） | `hour=8, minute=0` |
| 每周三上午九点汇总待办 | `weekly` | `weekday=3, hour=9, minute=0` |
| 每月十五号十八点做月度整理 | `monthly` | `day_of_month=15, hour=18, minute=0` |
| 每年六月一日上午九点提醒我 | `yearly` | `month=6, day_of_month=1, hour=9, minute=0` |
| 2027年1月5日上午十点执行一次 | `once` | `date="2027-01-05", hour=10, minute=0` |

- `schedule_task(title,prompt,hour,minute,schedule_type?,weekday?,day_of_month?,month?,date?)` 创建并启用任务。旧的四参数调用继续表示每日。
- `list_scheduled_tasks` 只读，返回真实 id、规则、设备时区、下次具体日期时间、启用态及上次执行结果；完成 / 停用任务的 `next_trigger` 为 null。
- `update_scheduled_task(id,...)` 仅修改提供的字段，可只改小时或分钟；`enabled=false` 停用。改 / 删前先 list，不能按标题猜 id。
- `delete_scheduled_task(id)` 删除并取消 alarm。
- 增 / 改 / 删沿用 `tools/pre-execute → ask` 审批，不因定时而扩权。audit reason 只含规则与时间，不含标题或 prompt；用户内容只进审批 UI、只读任务列表的标题与工具结果；列表不显示 prompt，也不写入日志。
- 设置 → 定时任务 提供只读列表：标题、规则、下次触发时间与上次结果；创建、修改、停用、删除仍然只走对话，
  UI 不提供表单。

模型负责解析自然语言，桥严格校验参数：周一=1…周日=7；月=1…12；月日=1…31 且年度月日组合必须存在；单次日期使用完整 `YYYY-MM-DD`。规则只允许相关字段，错误类型、显式 null、未知规则或矛盾组合不会静默回退为每天。切换规则时清除旧规则字段，新规则所需参数必须完整；缺少或有歧义的时间由模型询问。

## 2. 日期边界

- 按**设备本地时区**的公历日历执行，回执带时区与实际下次触发时间。换时区会重新计算，不固定原时区的绝对时刻。
- 每月 31 号遇到无 31 号的月份跳过，**不改成月末**；年度 2 月 29 日遇非闰年跳过，不改成 2 月 28 日。4 月 31 日等不可能的年度组合直接拒绝。
- DST 缺失时刻按 `java.time.atZone` 顺延，重复时刻取较早偏移，不在第二个偏移重复安排同一当地时刻。
- 单次任务创建、改期或恢复时要求未来时刻，已过时间返回 `SCHEDULE_PAST_DATE`，不变成循环任务或下一年。已排 alarm 延迟送达可以执行，不因几毫秒迟到而丢弃。
- 单次任务**被领取时即停用**，无论之后成功、失败还是进程被杀，都不自动重复；不声称副作用“恰好一次”。上次结果与记录保留，可由用户改到未来日期再启用。
- 重启重排时错过的单次任务停用并标 `SCHEDULE_MISSED`，不补跑；周期任务只排下一次，不在开机时连跑积压任务。

## 3. 精度、权限与后台限制

| 机制 | 条件与边界 |
|---|---|
| `setExactAndAllowWhileIdle` | Android 12+ 需要用户授予已有 `SCHEDULE_EXACT_ALARM` 特殊权限；Doze 等系统限制仍适用 |
| `setWindow` | 无精确闹钟权限的降级，可能延迟 15 分钟或更久，不保证窗口上界 |
| 任务前台服务 | 领取任务后启动现有 NodeTaskService；失败记录 `FOREGROUND_SERVICE_FAILED`，不伪装已执行 |

不新增依赖或权限，不申请只适用于核心闹钟 / 日历应用的 `USE_EXACT_ALARM`。现有 ExactAlarmMonitor 与聊天提示仍负责引导精确闹钟设置；开机、系统时间 / 时区变化、授权变化及应用更新后重排。

**准点与可执行是两回事**：锁屏时不能保证操作其他 App，不能绕过锁屏；需要无障碍的动作仍需原权限。默认写审批需要人在场，无人确认不能自动放行。ColorOS 可能在前台服务运行时仍冻结应用，见 [Q&A](Q&A.md#background-restrictions)。周期规则更丰富不代表这些系统限制被消除。

## 4. 存储、领取和停止

- Room v6→v7 仅增加规则、编辑版本和计划发生时刻，旧任务默认 daily，原有标题、指令、时间和结果不丢；数据库与 Keystore 身份不变。
- PendingIntent 的 data 带完整 task id，避免只凭 hashCode 碰撞；排期 / 取消时清理旧版无 data 的 intent。
- alarm 携带任务 id、编辑 revision、planned_at。数据库条件领取同时验证 enabled、版本与计划时刻，领取后清除 next_run_at，重复或旧改期广播不能再次执行。
- 编辑、领取和重排在短锁内串行，agent 网络执行不持锁。运行结束重新读取最新行；旧结果不能覆盖新编辑，不能用旧快照复活停用或已删除任务。
- 任务结束排下次，失败也排周期任务的下一次；单次任务保持停用。任务执行期间强杀进程不提供自动重试保证。
- BroadcastReceiver 的 pending result 仅保护到前台服务已启动的交接；重复广播无需启动任务服务。

## 5. 隐私与错误

任务使用同一个 agent 与原有工具权限；没有后台邮件订阅、通知采集或高频轮询。标题与 prompt 在 SQLCipher 加密表中，日志只记任务 id、计数、异常类型与固定结果码；不记 prompt、屏幕、邮件或模型错误正文。

主要错误：`SCHEDULE_INVALID_TIME`、`SCHEDULE_INVALID_RULE`、`SCHEDULE_EMPTY_CONTENT`、`SCHEDULE_PAST_DATE`、`SCHEDULE_NOT_FOUND`；运行状态 `RUNNING`、`TURN_FAILED`、`TASK_FAILED`、`TASK_CANCELLED`、`FOREGROUND_SERVICE_FAILED`，错过单次为 `SCHEDULE_MISSED`。无精确权限是降级而非创建失败，回执必须写清延迟风险。

## 6. 验证

- JVM：每日兼容、星期边界、短月、闰年与跨世纪、年度日期、单次过期、DST / 时区、规则切换、严格桥解析、领取版本与重复广播判据。
- 插件：五类规则的 schema、参数透传、自然语言描述及审批隐私，使用真实 defineTool 校验。
- 隔离设备测试：v6→v7 保留每日任务、单次原子领取 / 停用、旧运行与编辑 / 删除交错。测试仅用独立数据库和合成任务，不清正式库；编译不等于真机执行。
- 真机由用户验证：自然语言创建五类任务并 list 核对下次日期；单次设到几分钟后，确认只领取一次并停用；修改 / 停用 / 删除后核对不再触发；重启与精确权限降级仍需实测。

没有执行过的真机路径不能按 CI 绿宣称可用；不要为了测试修改用户设备系统时间或自动触发有副作用任务。

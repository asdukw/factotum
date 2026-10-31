# 模型可见工具：清单与合同

模型能调用哪些工具、每个工具的行为边界。执行层怎么实现见
[android-accessibility.md](android-accessibility.md)，审批语义见 [approvals.md](approvals.md)。

## 1. 新增或修改一个设备动作

一个原子动作要同时改四处，少一处就会出现「工具在、桥不认」或「桥认、模型看不见」：

| 位置 | 职责 |
|---|---|
| `device/DeviceAction.kt` | 动作定义 |
| `accessibility/ZoneuAccessibilityService` | 执行 |
| `bridge/DeviceBridgeServer` | HTTP 映射 |
| `tools/dsh-phone/plugins/android-tools/index.mjs` | `ctx.tools` 注册 + 给模型看的描述 |

然后 `bundle-dsh.ps1` + 重装 APK。

## 2. 清单

设备工具（19）：

`read_ui_tree`、`tap_node(ref)`、`long_press(ref,duration_ms)`、`capture_screenshot()`、
`tap_target(screenshot_id,x1,y1,x2,y2)`、`long_press_target(...)`、`type_text`、
`press(back|home|recents|enter)`、`launch_app(query)`、`swipe(direction)`、
`set_alarm(hour,minute,label?)`、`add_calendar_event(...)`、`list_calendar_events(begin,end)`、
`update_calendar_event(event_id,...)`、`delete_calendar_event(event_id)`、
`schedule_task(title,prompt,hour,minute,schedule_type?,...)`、`list_scheduled_tasks`、
`update_scheduled_task(id,...)`、`delete_scheduled_task(id)`。

- `run_command`：在**内嵌 Debian** 里执行。宿主设置 `ZONEU_PROOT=1` 时才注册；launcher 把它
  映射到外部插件的 `FACTOTUM_PROOT` 契约。见 [linux-on-phone.md](linux-on-phone.md)。
- 接了邮箱连接器时再加九个：五个只读 `mail_list` / `mail_search` / `mail_read` /
  `mail_thread` / `mail_labels`，四个审批写操作 `mail_modify` / `mail_draft` / `mail_send` /
  `mail_save_attachment`。见 [email-connector.md](email-connector.md)。

## 3. 日历与闹钟

- **不经过任何 App 的界面**：用 `ContentResolver` 与 `AlarmClock` intent
  （`device/SystemIntents.kt`），静默完成，没有编辑器、没有系统确认框。
  让模型去点日历 App 改一条日程，实测花了 27 个 UI 动作——所以先 `list_calendar_events`，
  再按 `event_id` update / delete。
- 四个写操作都走 `tools/pre-execute` → `ask`，成功后由 `device/DeviceNotices.kt` 发一条回执
  通知（ROM 支持时升级为流体云）。
- 需要 `READ_CALENDAR` / `WRITE_CALENDAR`；只在**真机上确实失败之后**从聊天提示里引导授权。

## 4. `run_command`

与日历写操作同级：`tools/pre-execute` 判 `ask`。审计 `reason` 只写「执行 shell 命令」，
命令正文放 `displayReason`（只给 UI，不落盘）。设置 → 执行层 → 写操作审批 =「全允许」时，
答案器直接回 `allowed-once`，面板不出现；seam 与审计仍在。

## 5. 两类失败不能混

| 情况 | 属于 | 给模型的话 |
|---|---|---|
| 桥不认识这个动作名 | 契约错误（插件与 App 版本不匹配） | **重试没有用** |
| 缺少必填参数 | 模型自己可修 | **补齐参数后重试一次** |

合成一句「unknown or incomplete action」曾让模型对同一个死错误连撞六次。
`DeviceBridgeActionContractTest` 把这条拆分钉住。

## 6. 其余契约的归属

| 契约 | 看 |
|---|---|
| 坐标只用最近一次 `capture_screenshot` 的**图像像素**；`ScreenFrame.toScreen` 是唯一换算点 | [android-accessibility.md](android-accessibility.md) §3 |
| 文字输入：控件树优先，失败走文本注入通道（`text_no_channel` / `text_flag_missing`） | [android-accessibility.md](android-accessibility.md) §4 |
| 增量读屏、ref 稳定、页面记忆只存结构 | [android-accessibility.md](android-accessibility.md) §2 |
| 定时任务的规则、日期边界与领取语义 | [scheduled-tasks.md](scheduled-tasks.md) |
| 写操作审批、`reason` / `displayReason` | [approvals.md](approvals.md) |

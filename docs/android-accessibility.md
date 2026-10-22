# Android 无障碍执行层：系统探测与「四问」运行手册

**状态（2026-10-03）**：系统侧探测完成；**四问探针已实现并在真机跑通**
（Tappet 控制组 + QQ 试验场，结论见 §7）。执行层走**控件树优先、截图兜底**，
可以开始写工具；QQ 是后续两个试验任务的目标 App（选联系人聊天 / 群聊发言 @ 回复）。

> **2026-10-17 变更**：探针页（`ui/probe/`）、`ProbeCommandReceiver` 广播、
> `DeviceCapability` / `CapabilityResult` / `DeviceBridgeState` 这套探针契约
> **已随功能一起删除** —— 它是开发期的诊断入口，不该出现在使用者面前。
> 本文下面的实测结论与数值**保持原样**（它们是这台设备 / 这个 ROM 的记录，仍然有效），
> 但文中的「怎么跑」部分现在只有历史意义：执行层的原子动作仍可用
> `DeviceActionReceiver` 广播驱动（见 AGENTS.md 的 debug 广播一节），
> 四问那套能力自检要重新做的话，得先把探针加回来。

旧设计文档 `docs/mobile-agent.md` 已随旧管线一起删除，且**不在本仓库历史里**（2026-10-12 的历史重建把它连同其中的真实标识一起去掉了）。
本文接管「执行模型」这一节，并补上系统侧与四问实测。

---

## 1. 基线（实测 2026-10-03）

| 项 | 值 | 命令 |
|---|---|---|
| 设备 | 一加 15T（PLZ110） | `getprop ro.product.model` |
| 系统 | Android 16 / API 36 | `getprop ro.build.version.sdk` |
| ColorOS | PLZ110_16.0.10.500(CN01)，安全补丁 2026-08-01 | `getprop ro.build.display.id` |
| 屏幕 | 物理 1080x2344，density 560 / 覆盖 480 | `wm size` / `wm density` |
| 微信 | 8.0.78 | `dumpsys package com.tencent.mm` |
| 本 App | targetSdk 36 / minSdk 28，debug 构建 | `dumpsys package com.tappet.app` |
| shell | uid 2000，无 root | `adb shell id` |

## 2. 系统里有哪些无障碍服务（28 个）

```powershell
adb shell cmd package query-services --brief -a android.accessibilityservice.AccessibilityService
```

当前没有任何服务在跑：

- `settings get secure accessibility_enabled` = `0`
- `settings get secure enabled_accessibility_services` = 空

已安装的 28 个服务（按来源分组）：

**AOSP / Google（4）**

| 服务 | 用途 |
|---|---|
| `com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService` | 系统无障碍菜单 |
| `com.google.android.marvin.talkback/.TalkBackService` | TalkBack |
| `com.google.android.marvin.talkback/...SelectToSpeakService` | 随选朗读 |
| `com.google.android.accessibility.switchaccess/...SwitchAccessService` | Switch Access |

**ColorOS / OPlus 系统（17）**

| 服务 | 用途（据服务名） |
|---|---|
| `com.coloros.accessibilityassistant/.ColorAccessibilityService` | 无障碍助手 |
| `com.coloros.codebook/...AutofillAccessibilityServiceText` | 密码本自动填充（文本） |
| `com.coloros.codebook/...AutofillAccessibilityServiceGesture` | 密码本自动填充（手势） |
| `com.coloros.colordirectservice/...FlashNotesAccessibilityService` | 闪记 |
| `com.coloros.colordirectservice/.ColorTextAccessibilityService` | 文本服务 |
| `com.heytap.quicksearchbox/...SimulateClickService` | 模拟点击 |
| `com.heytap.speechassist/.kanalogclick.AnalogSkillService` | 小布语音·模拟点击 |
| `com.oplus.aiwriter/...KitExtractorAccessibilityService` | AI 写作·文本提取 |
| `com.oplus.dmp/...SimulateClickService` | 模拟点击 |
| `com.oplus.gleanerservice/...FlashNotesAccessibilityService` | 屏幕信息采集 |
| `com.oplus.matrix/...DefaultScAccessibilityService` | Matrix 采集 |
| `com.oplus.ocar/.basemodule.acc.OCarAccessibilityService` | 车机互联 |
| `com.oplus.ocar/...XGuiAccessibilityService` | 车机互联 UI |
| `com.oplus.travelengine/...EngineAccessibilityService` | 出行引擎 |
| `com.oplus.viewtalk/.imagedetect.ImageDetectService` | 图像描述（看见） |
| `andes.oplus.documentsreader/...SimulateClickService` | 文档阅读器·模拟点击 |
| `com.coloros.filemanager/...SimulateClickService` | 文件管理·模拟点击 |

**第三方（7）**

| 服务 | 用途 |
|---|---|
| `com.baidu.input_oppo/...AcsService` | 百度输入法（预装） |
| `com.microsoft.appmanager/...ScreenMirroringAccessibilityService` | 微软手机连接·投屏 |
| `com.kugou.android/...AccessibilitySuperService` | 酷狗 |
| `com.netease.uuremote/...UURemoteAccessibilityService` | 网易 UU 远程 |
| `com.ss.android.ugc.aweme/...AudioAccessibilityService` | 抖音 |
| `com.xingin.xhs/...GameRecordAccessibilityService` | 小红书 |
| `tv.danmaku.bili/...GameAccessibilityService` | B 站 |

**结论**：ColorOS 自身大量依赖 AccessibilityService（模拟点击、文本提取、屏幕采集、
自动填充、图像描述、投屏），第三方服务也能装上并声明 —— 系统没有阉割这条通道。
系统对 App 一视同仁：能力上限由 API + 权限 + 目标 App 的实现决定，
**不存在需要逐 App 适配的白名单**。能走多远取决于四问。

## 3. 管控状态与启用路径

- **侧载受限设置**：`cmd appops get com.tappet.app ACCESS_RESTRICTED_SETTINGS` 返回
  `default; rejectTime=+5d… ago` —— App 处于 Android 13+ 的「受限设置」。
  从设置 UI 手动开启服务前，需走 应用信息 → 右上角菜单 → **允许受限设置**。
- **adb 启用路径实测可用**（不再只是预计）：`settings put secure enabled_accessibility_services
  <组件>` + `settings put secure accessibility_enabled 1` 后服务立即 bound，
  `dumpsys accessibility` 显示 `capabilities=161`（读树 1 + 手势 32 + 截图 128）。
- **ColorOS 安全弹窗**：带无障碍的 App 到前台时，`com.oplus.safecenter` 会弹
  「检测到“Tappet”获取无障碍权限」，按钮「保持开启 (3)」倒计时 3 秒后才可点，
  不点则可能被关闭。首次启用 / 重装后必弹。
- **重装会摘掉服务**：`adb install -r` 后 `enabled_accessibility_services` 变空，
  必须重新启用（实测）。
- **`uiautomator dump` 会顶掉无障碍服务**：UiAutomation 抢占通道，服务 unbind ~1 秒后
  自动重绑。用 adb 做探针自动化时要避开 dump 或等重绑；UI 上会短暂显示「未连接」。
- `pm grant` / `appops set` 被 ColorOS 加固拦截（uid 2000 无权限），别反复试。
- ColorOS 会冻结后台进程：设备操作需要**前台服务**（旧探针期结论）。

弱证据（对 Q1 有利）：`dumpsys accessibility` 的 Client list 里有 `com.tencent.mm`
—— 微信注册了无障碍客户端；但“能不能读到节点”仍以 Q1 为准。

## 4. 能力矩阵（用 `android-36/android.jar` javap 实测符号）

| 能力 | 声明 | 调用 | 备注 |
|---|---|---|---|
| 读控件树 | `android:canRetrieveWindowContent="true"` | `getRootInActiveWindow()` / `getWindows()`（需 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`） | 目标 App 是否暴露节点要 Q1 |
| 手势 | `android:canPerformGestures="true"`（`CAPABILITY_CAN_PERFORM_GESTURES`） | `dispatchGesture(...)` | API 24+ |
| 文本输入 | — | `AccessibilityNodeInfo.ACTION_SET_TEXT`(=2097152) / `ACTION_FOCUS`(=1) / `ACTION_PASTE`(=32768) | 要 Q3 |
| 截图 | `android:canTakeScreenshot="true"`（`CAPABILITY_CAN_TAKE_SCREENSHOT`） | `takeScreenshot(displayId, executor, callback)`、`takeScreenshotOfWindow(windowId, …)` | 失败有明确错误码，含 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` |
| 节点交互 | — | `performAction(ACTION_CLICK)`、`isEditable/isFocusable/isVisibleToUser`、`refresh()` | |

FLAG_SECURE 的系统侧旁证（样本之一）：当前微信 `LauncherUI` 窗口的 `fl=` 列表里
**没有 SECURE**（`dumpsys window windows`），且 `mDisableSecureWindows=false`。
FLAG_SECURE 是逐窗口属性（支付、密码类页面常设），所以 Q2 必须跑样本矩阵，
不能用单个 App 的结论代表全部。

## 5. 四问 Runbook（已实现；2026-10-17 起代码已删除，本节作为方法与记录保留）

### 代码分层（ROM 无关契约 / Android 实现 / OEM 档）

- `app/src/main/java/com/tappet/app/device/` —— **通用契约**：
  `DeviceAction` / `DeviceActionResult`（原子动作）、`DeviceBridge`（操作入口）、
  `OemProfiles`（OemProfile：启用路径、adb 命令、已知限制）。
  （`DeviceCapability` / `CapabilityResult` / `DeviceBridgeState` 随探针一起删除。）
- `app/src/main/java/com/tappet/app/accessibility/` —— **AOSP 无障碍实现**：
  `TappetAccessibilityService`（`DeviceBridge` 实现，不绑 ROM）。
- `app/src/debug/` —— debug-only 的 adb 广播入口（`DeviceActionReceiver` / `NodeDebugReceiver`，release 不含）。

换 ROM 只加一个 `OemProfile`；正式执行层只扩 `DeviceBridge`，UI 与工具不动。

探针服务声明：

```xml
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:notificationTimeout="100" />
```

启用 / 复位（adb 路径）：

```powershell
adb shell settings put secure enabled_accessibility_services com.tappet.app/.accessibility.TappetAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 复位（PowerShell 里空串要整体包一层引号）
adb shell "settings put secure enabled_accessibility_services ''"
adb shell settings put secure accessibility_enabled 0
```

四问可用两种方式触发（**下面两条入口在 2026-10-17 已随探针一起删除**，
留在这里是为了说明当时的数据是怎么采的）：

1. **UI**：App 首页右上角「探针」→ 按钮带 8 秒倒计时，切到目标 App 停稳后自动执行。
2. **adb（仅 debug 构建）**：目标 App 在前台时直接广播，无需倒计时 ——

```powershell
adb shell am broadcast -n com.tappet.app/.accessibility.ProbeCommandReceiver `
  -a com.tappet.app.debug.PROBE --es capability READ_TREE   # SCREENSHOT / SET_TEXT / TAP
```

结果同时进 `Logcat`（当时 tag 是 `TappetProbe`；2026-10-17 起执行层的 tag 改为 `TappetDevice`，
只记计数 / 类名 / 坐标 / 错误码，不落正文）。

四问跑在**样本矩阵**上，不绑定单一 App：目标是通用 mobile agent，QQ 是后续两个
试验任务的目标 App，微信/浏览器只是高难度样本，不是适配对象。样本至少覆盖：
系统设置（AOSP 原生）、QQ 聊天页（自绘）、浏览器网页（WebView）、
一个 Compose/Flutter 自绘 App、一个信息流类 App。

每问都要记录：结果、证据、失败形态、可复现的样本包名与页面。

- **Q1 读屏**：对每个样本页调 `getRootInActiveWindow()` / `getWindows()`。
  判据：根节点非空、文本节点数 > 0、顶层节点归属该样本包名。
  只记录计数、节点类名与控件类型分布；**不把页面文字写进日志 / 文件 / 报告**（隐私红线）。
- **Q2 截图**：对每个样本页各调一次 `takeScreenshot`。
  判据：`onSuccess` 且非纯黑（采样非黑像素比例）；`onFailure` 记错误码
  （`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` = 该页禁截）→ 记入「只能读树、不能截图」名单。
- **Q3 输入**：在探针页自测 Compose 输入框；再对系统设置搜索框、微信输入框、
  WebView 输入框各测一次 `ACTION_SET_TEXT` 写探针字符串（**不发送 / 不搜索**），
  读回 `getText()` 验证；失败改试 `ACTION_FOCUS` + `ACTION_PASTE`（先写剪贴板）。
- **Q4 手势**：① 先点探针 App 自己的输入框（对照）；② 在目标 App 点输入框；
  用 Q1 的节点 bounds 计算坐标，`dispatchGesture` 点击后判据：`onCompleted` +
  焦点变成可编辑节点（输入框聚焦后可能位移，坐标包含判定不能单独用）。

## 6. 复现命令（系统侧）

```powershell
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell settings get secure accessibility_enabled
adb shell cmd package query-services --brief -a android.accessibilityservice.AccessibilityService
adb shell dumpsys accessibility
adb shell dumpsys window windows   # 目标窗口 fl= 里有没有 SECURE
```

## 7. 结果记录（2026-10-03 实测）

| 问 | 结论 | 证据 | 失败形态 |
|---|---|---|---|
| Q1 读屏 | ✅ | Tappet：34 节点 / 12 文本 / 1 输入框；QQ 聊天页：45 节点 / 21 文本 / 1 输入框 / 25 可点；均含 `getWindows()` | root 为 null 时框架内已给明确失败文案，未遇到 |
| Q2 截图 | ✅ | Tappet 100% 非黑；QQ 图库 65.5%；QQ 主页 99.7%；尺寸 1080x2344 | 未遇到 FLAG_SECURE 页；遇到时错误码为 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` |
| Q3 输入 | ✅ | `ACTION_SET_TEXT` 在 Tappet Compose 输入框、QQ 输入框均写入且读回一致；验证后自动清空 | 首版因 Compose/QQ 文本异步刷新读到旧值而误判失败 → 加 400ms 延迟校验后通过；未用到 PASTE 回退 |
| Q4 手势 | ✅ | `dispatchGesture` 点输入框：控制组焦点坐标包含；QQ 输入框聚焦后位移，按「焦点变为可编辑」判定通过 | 输入框聚焦后 bounds 变化，坐标包含判定会假阴性 |

**结论：四问全通** → 执行层走**控件树优先**（读屏 + 按节点 bounds 手势 + ACTION_SET_TEXT），
截图作为无法读树时的兜底与多模态补充。不需要视觉模型做基础操作。

## 8. 下一步

1. 基于四问结论实现正式执行层：扩 `DeviceBridge` 出 `observeScreen` / `tap` / `setText` /
   `screenshot` / `startApp` / `back` 等 App 无关原子操作（含重试、限流、目标校验）。
2. agent 工具循环 + 工具定义（手写，按已实测的流式 tool_calls 语义）。
3. QQ 试验场两条验收任务：
   ① 选一个联系人，结合上下文与对方聊天；
   ② 选一个群聊，结合上下文发言，支持 @ 他人与回复他人。
4. 前台服务保活（ColorOS 冻结），把设备操作放到前台服务里。
5. 样本矩阵补测：系统设置、浏览器/WebView、信息流类 App；遇到禁截页登记进
   「只能读树、不能截图」名单。

## 9. App 隐藏控件树：微信的「触摸探索」门控（2026-10-11，一加 15T / ColorOS 16）

### 现象

微信 8.0.78 对无障碍**完全不暴露控件树**：`read_ui_tree` 只返回 1 个节点，
`uiautomator dump` 的 `<hierarchy>` 里 root 只有状态栏/导航栏，`dumpsys accessibility`
显示微信窗口 `hasChildren=false`。同一时刻 QQ 读得到 72 节点、系统设置 46 节点 ——
所以不是 ROM 拦截、也不是执行层的问题，是微信 App 侧的行为。

### 根因：微信按 `isTouchExplorationEnabled()` 门控

决定性实验（`adb shell settings put secure enabled_accessibility_services ...` 切换服务）：

| 场景 | 触摸探索 | 微信进程 | 读到的节点 |
|---|---|---|---|
| 只挂 Tappet（未声明触摸探索能力） | false | 任意 | 1 |
| 只挂 Tappet + `canRequestTouchExplorationMode` 但运行时去掉 flag | false | 冷启动 | 1 |
| 运行时加上 flag（`setServiceInfo`） | true | 之前已启动 | 1 |
| 同上 | true | 冷启动 / 强停后重启 | **441** |
| 先 false 再 true（进程存活） | true | 存活 | 1 |
| 旋转屏幕、`--activity-clear-task`、HOME→回前台 | true | 存活 | 1 |

结论两条：
1. 微信只在 `isTouchExplorationEnabled()` 为真时暴露节点；
2. 这个状态是**进程启动时采样一次**并缓存的 —— 进程存活期间怎么切都不恢复
   （反过来：切成 false 会**立刻**隐藏），所以只能让目标 App 重新起进程。

另外验证过：`am kill`（= `ActivityManager.killBackgroundProcesses`，App 能拿到的同名
普通权限 API）**杀不掉微信** —— 后台 90 秒后仍失败，微信进程有前台服务保活；
**最近任务卡片上滑也没用**（在卡片本体上做滑动，卡片不消失、pid 不变）。
目前唯一确认有效的结束方式是系统设置的「强行停止」。
**App 内没有可靠手段强制重启别的应用。**

### 连带后果：触摸探索开启时注入手势全部失效

`AccessibilityInputFilter` 在触摸探索模式下会把触摸转成探索事件，
`dispatchGesture` 注入的点击/长按/滑动**同样被吞掉**：桌面翻页滑动前后截图字节完全一致，
设置页点击节点无任何变化（对照：shell 的 `input tap` 不受影响）。

所以触摸探索开启期间 **点击/长按/上下滚动必须走 `performAction`
语义动作**（`ACTION_CLICK` / `ACTION_LONG_CLICK` / `ACTION_SCROLL_FORWARD|BACKWARD`），
`tap_point` 退化为「点中坐标节点的语义点击」。实测微信会话行、EditText、发送按钮、
ListView 滚动都能用语义动作完成；但**部分节点（如聊天列表右上角搜索图标）报告
`isClickable=true`、`performAction` 返回 true 却无任何反应** —— 语义路径不是万能的。

反过来，触摸探索**常开**会让用户的手动单击变成「探索 + 双击」、单指拖动变成探索，
手机没法正常用。

### 落地方案：任务期**不**开触摸探索，改走视觉路径（2026-10-17 改）

原先的落地是「任务期按任务开关触摸探索」：`DshStreamer` / `NodeDebugReceiver` 在任务开始时
`setTouchExplorationForTask(true)`，`finally` 关掉。**这条路已经不走**，两个理由：

1. **它和视觉路径互斥，而且死在同一个场景上**。触摸探索一开，`dispatchGesture` 注入的手势就被
   系统当探索事件吞掉，点击/滑动只能退回 `performAction` 语义动作 —— 而那需要节点。树不可读时
   没有节点，于是「只能看不能点」；偏偏树不可读正是要走视觉的时候。
2. **它救不了已经在跑的 App**。那个状态是目标 App 进程启动时采样一次并缓存的，进程存活期间
   怎么切都不恢复，而 App 内没有强杀别的应用的手段（见上）。

现在的形态：

- 任务生命周期**不再**调 `setTouchExplorationForTask` —— 手势始终可用，任务期间用户手动点击也不受影响。
- 机制本身**保留**（`setTouchExplorationForTask` + debug 广播 `AGENT_MODE` + `accessibility_service_config.xml`
  里的 `canRequestTouchExplorationMode`），供实验用；`onServiceConnected` 仍复位成 false。
- **`touchExplorationActive` 的语义兜底分支一个字都不能删**：用户自己开着 TalkBack 时全局触摸探索
  恒为真（系统取所有已启用服务的**或**），`dispatchGesture` 一样被吞，那些 `performAction` 分支是
  读屏用户唯一可用的路径。
- 读屏**没有任何可读节点**时（判据是 `ScreenSnapshot.hasNoReadableNode()` —— 可见且有内容/可交互的
  节点一个都没有，不是按节点总数），payload 追加 `EMPTY_TREE_HINT` 引导模型走视觉路径。
- debug 入口不变：`--es action AGENT_MODE --ez on true|false`。

### 视觉路径：帧契约

树不可读时模型改走 `capture_screenshot` → `tap_target`。这里有一个**必须**先修的硬伤：

- 交给模型的截图被缩到最长边 2048，1080×2344 的屏得到 **943×2047**；而 `tap_point` 收的是屏幕像素。
  两者之间以前没有任何声明的换算，模型连屏幕尺寸都看不到（插件只把 `summary` 文本交给模型，
  payload 里的尺寸它读不到）—— 按图给坐标会系统性偏约 **12.6%**，而且不报错。
- 现在坐标一律是**最近一次 `capture_screenshot` 那张图的图像像素**，换算在设备侧的纯函数
  `ScreenFrame.toScreen`（`round(offset + x*screenW/imageW)`，夹到 `screenW-1`）。
  屏幕尺寸取**缩放前的捕获位图**而不是 `displayMetrics`；两者不一致时打 `ev=frame_mismatch`。
- `capture_screenshot` 的 payload 带 `screenshotId / width / height / screenWidth /
  screenHeight / offsetX / offsetY`；插件把它渲染成**模型可见的图例文本** ——
  模型只看得见文本，尺寸不写进文本它就拿不到。
- 截图是**有状态的**：界面变了旧截图作废（`STALE_SCREENSHOT`）；bbox 越界直接拒（`OUT_OF_SCREENSHOT`），不 clamp。
- 动作后验证：再截一张算 8×8 指纹比对，只回 `changed` 布尔不回图。`changed=false` 时 `ok` 仍为 true
  （`NO_VISIBLE_CHANGE`）—— 它是提示不是失败，插件把 `ok=false` 当异常抛。

### 已知限制

- **视觉路径不解决输入**（**2026-10-20 补上了通道，见 §10**）：`type_text` / `press(enter)` 原先
  只有控件树一条路，树不可读时没有通道，只能如实告诉用户做不到。现在多了一条**不依赖控件树**的
  注入通道，但它要求输入框**已经拿到焦点** —— 先 `tap_target` 点中输入框才谈得上输入，
  点不中就仍然做不到。
- **视觉路径不新增定位能力**。它消掉的是**确定性**的换算错误（12.6% 的系统性偏移），消不掉
  「模型能不能在图上找到目标」这个能力问题。首点命中率要靠真机实测，不能靠契约保证。
- 图会占请求预算（20MiB、每轮重发历史图）：profile 里已启用 `image-offload`，把「超预算整轮硬失败」
  降级成「丢最老的图重试」；代价是**永久丢帧**，模型之后拿不到那张图。
- `changed` 是 8×8 指纹的启发式：整屏级变化（翻页、弹窗）看得见，小面积变化（勾选框、行高亮）
  看不见 —— 所以它只能报「似乎没变」，不能报「点击失败」。

### 真机验证

**2026-10-11（触摸探索时代，已不是当前路径，留作对照）**：

- 任务「打开微信，在聊天列表里找到指定联系人，读出最近消息说明上下文」：
  **10 次工具调用、0 错误**。产品路径（任务自动开关 触摸探索）下微信读屏 319 节点，
  正确找到联系人、向上滚动一次列表后点开会话、按左右头像区分说话人并复述上下文；
  任务结束 `touchExploration` 回到 false。
- 任务「给该联系人发一条符合上下文的消息」：**11 次工具调用、0 错误**，
  语义路径完成 `tap_node(EditText)` → `type_text` → `tap_node(发送)`，消息确认送达。

**2026-10-17（帧契约）—— 尚未真机验证**，只有 PC 侧证据：JVM 单测（`ScreenFrameTest` 钉住
943×2047 → 1080×2344 的换算与那条 12.6% 偏差）、插件 `npm test`（10 个工具 + 真实 `defineTool` 校验）、
`check-profile.mjs`。**待验**：模型在图上估的绝对像素 bbox 准不准（仓库从没测过坐标回归）、
`takeScreenshot` 的限流窗口与验证截图是否打架、`image-offload` 与自定义附件实现是否配合、
ColorOS 下捕获位图尺寸是否等于 `displayMetrics`。验证步骤见本节末尾。

#### 截图契约怎么验（2026-10-17 起，尚无真机记录 —— 验完把结论写回本节）

1. **换算**（确定性，不依赖模型）：`CAPTURE_FRAME` 的 summary 应报「图像 943×2047｜屏幕
   1080×2344」；对某个已知元素 `TAP_TARGET`，logcat 里换算出的屏幕坐标要落在该元素的
   `getBoundsInScreen` 内（用 `READ_SCREEN` 复核）。建议先开「开发者选项 → 指针位置」，
   点击坐标肉眼可见。再故意用过期 `screenshot_id` → 应报 `STALE_SCREENSHOT`；`x2` 超出
   图像宽 → 应报 `OUT_OF_SCREENSHOT`。
2. **动作生效**：对会翻页 / 开新页的按钮 `TAP_TARGET`，要求 `changed=true` 且页面确实变了；
   `NO_VISIBLE_CHANGE` 只说明「看着没变」，不判失败。
3. **空树端到端**：微信已在后台运行时提交「打开微信，点开第一个会话」，工具序列应是
   `capture_screenshot → tap_target`（不再是「读屏 1 个节点后停下请用户强停」）。
4. **回归**：系统设置这类可读树的 App 仍应走 `read_ui_tree + tap_node`，不该掉进视觉路径。

## 10. 控件树不可读时的文字输入：无障碍文本注入通道（2026-10-20）

### 问题

`type_text` / `press(enter)` 原先只有一条路：在控件树里找 `isEditable` 的节点，对它
`ACTION_SET_TEXT` / `ACTION_IME_ENTER`。控件树不可读的 App（微信一类，见 §9）上这条路直接断了 ——
**没有节点可找**；自绘输入框还会出现「有节点但被拒绝」。于是视觉路径**能点不能打字**：
「打开微信，给某人发一条消息」这类任务永远差最后一步。

### 先排除的两条路

- **自建 IME**（roadmap §2.5 原先写的做法）：要用户把 Tappet 切成当前输入法才有效果。而
  **只有当前输入法能切回自己**（`InputMethodManager.setInputMethod` 要 IME 自己的 token 或
  `WRITE_SECURE_SETTINGS`，普通应用两样都没有），所以「任务前切过去、任务后切回来」做不到；
  一个无键盘的输入法当默认输入法 = 把用户的键盘拿走。**每做一个需要输入的任务切一次**，不可接受。
- **剪贴板 + 长按粘贴**：`ACTION_PASTE` 同样要节点；退化成「长按输入框 → 点系统浮层里的粘贴」
  要赌那个浮层的位置与可用性，而且正文得先进剪贴板（API 29+ 虽然只有持有焦点的应用读得到，
  仍是多一处落点）。

### 现在的做法：`FLAG_INPUT_METHOD_EDITOR`（API 33+）

系统给无障碍服务留了一条侧门：服务配置里声明 `flagInputMethodEditor` 之后，
`AccessibilityService.getInputMethod()` 给出一个 `InputMethod`，它的 `currentInputConnection`
就是**当前聚焦输入框**的连接：

```
commitText(text, cursor, null)   // 写文本
performEditorAction(action)      // 按输入框自己声明的动作键（发送 / 搜索）
sendKeyEvent(KEYCODE_ENTER)      // 没声明动作时的退路
```

AOSP 对这条 flag 的原话是「makes the AccessibilityService an input method editor with a subset of
input method capabilities: get the InputConnection and get text selection change notifications」——
**不需要权限、不需要用户切换输入法、不需要额外授权**：用户已经开着的那个无障碍服务就是通道，
键盘仍是用户自己那个。连接由系统按「谁拿着焦点」发出，**与控件树可不可读无关**。

### 实现（两处 + 一个纯函数）

| 位置 | 管什么 |
|---|---|
| `res/xml/accessibility_service_config.xml` | `accessibilityFlags` 里加 `flagInputMethodEditor`（一行） |
| `accessibility/TextChannel.kt` | 通道本体：拿连接、写文本、按动作键、报状态（只写不读） |
| `device/TextInput.kt` | 两条路都不通时报哪个码 —— **纯函数**，单测钉住 |

- **顺序不能反**：控件树优先（它自己会 `ACTION_FOCUS`，不要求输入框先有焦点），通道兜底。
- `press(enter)` 走同一条顺序 —— 只补上打字、补不上发送，任务还是做不完。
- **清单里少这一条 flag 是静默失败**（编译过、无障碍照样启用、只有输入时才发现）→
  `AccessibilityServiceConfigTest` 钉住；真机验证步骤见本节末尾。
- 失败码五条，每条对应一个不同的下一步：`text_unsupported_os`（Android 13 以下）/
  `text_flag_missing`（升级后没重新启用无障碍）/ `text_commit_rejected`（有连接但写不进去）/
  `text_no_channel`（**最常见**：没有输入框聚焦 → 先点一下输入框）/ `text_set_text_rejected`。
- 审计：`ev=text_input via=tree|ime_channel|none chars=N`、`ev=ime_enter via=…`。

### 已知限制

- **输入框必须先有焦点**。这是这条通道唯一的前置条件，也是它最实际的短板：视觉路径点不中
  输入框时，它一样帮不上忙。模型侧拿到的提示是「先 `tap_target` 点一下输入框」。
- **只写不读**：`getSurroundingText` 用不到，所以通道那条路**没有读回验证**（控件树那条有
  `readback`）。工具结果里如实写「未读回验证」，确认留给下一步动作。
- **API 33 以下没有这条通道**（minSdk 28）：退回原来的行为，失败码 `text_unsupported_os`。
- **`flagInputMethodEditor` 属于服务配置**：启用无障碍时才解析。升级 APK 之后没重新启用过的话
  它不生效 —— `TextChannel.flagActive()` 读的是系统缓存的那份 `AccessibilityServiceInfo`，
  所以能**分辨**「配置没生效」与「没有输入框聚焦」这两种完全不同的原因。
- 正文不进日志、不进审计：`TextChannel` 只报系统版本 / 配置 / 有没有连接 / 输入框类型。

### 真机验证（待做）

1. `adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action TEXT_CHANNEL`
   → logcat（`TappetDevice`）应报 `text channel: supported=true flagActive=true connection=… editor=…`。
   **`flagActive=false` 就是没生效**：去设置里把无障碍关掉再打开一次，再验一遍。
2. 树可读的界面（系统设置搜索框）：点中输入框 → `TYPE_TEXT` → 应报 `via=tree`（**不该**掉进通道）。
3. **通道的写入与动作键**（隔离验证）：点中一个输入框，
   `--es action IME_TEXT --es text "hello"` → logcat 应报 `ime text: ok=true chars=5`，
   且文字真的出现在框里；`--es action IME_ENTER` → 应报 `ime enter: ok=true`，
   搜索框/输入框自己的动作键被触发。这两步绕开控件树，是「通道成没成」的唯一确定性判据 ——
   正常路径在树可读时走的是控件树。
4. 树不可读的界面（微信）：`capture_frame` → `tap_target` 点中输入框 → `TYPE_TEXT` → 应报
   `via=ime_channel`；再 `PRESS --es key ENTER` 验动作键。
5. 没点中输入框时 `TYPE_TEXT` → 应报 `text_no_channel`（而不是含糊的「没有可见输入框」）。

## 11. 增量读屏与页面指纹（2026-10-21）

解决的是「每步都把整棵树重发一遍」的成本：全量上限 240 行，一个长任务里同一屏往往要读几十次，
其中绝大多数节点根本没变。实现在 `device/ScreenDiff.kt`（纯函数）与
`accessibility/TappetAccessibilityService.executeReadScreen`。

### 契约

| 情况 | 返回 | ref |
|---|---|---|
| 首次读 / 换 App / 上次或本次截断 / `full=true` | 全量（同旧格式） | 前序编号（下标） |
| 两次读之间只有小变化 | 增量 | 未变节点沿用旧编号，新节点从「旧最大编号 + 1」往后接 |
| 两次读之间完全没变 | `unchanged since last read, refs still valid` | 全部沿用 |
| 变化量 > 50% | 退回全量 | 重排为下标 |

增量行首的标记：`[+12]` 新增、`[~12]` 变化（文字 / 描述 / 位置 / 状态任一）、`[-9]` 消失
（保留消失前的编号）。未变化的节点不重发，ref 沿用上一次；屏幕完全没变时只回一行
`unchanged since last read, refs still valid`。

**不附加「全部可操作节点」清单**（2026-10-22 改）：计算器实测，整屏都是按钮时重列 25 个可点节点，
让增量从约 150 字符涨到 1723 字符，和全量（1652）几乎一样。上下文被截断的兜底是模型主动传
`full=true`，而不是让每次增量都为所有场景付这份成本。

工具描述同时要求：「同一屏内连续操作不必每步重读，只有界面跳转或确认最终结果时再读屏」。
计算器 trace（37×48）里，旧描述导致两次多余回读；编号在跳转前一直有效，连续按键可以直接点。

**滑动反馈（2026-10-22）**：`swipe` 在动作前后各算一次**内容哈希**（`device/ScreenDiff.kt#contentHash`，
包含文字与外框），把「界面有没有变」写进结果。模型因此不必每滑一次就读一次屏；
`false` 表示「似乎没变」（可能已到列表尽头，或手势被系统吞掉），不影响动作本身的 `ok`。
用控件树而不是截图：截图有频率限制，滑动又是连续动作。

变化量阈值是 `DIFF_MAX_CHURN_RATIO = 0.5`：整页替换时逐行 diff 既难读，
弱匹配（同标签但不同位置）也更容易把「删一行 + 加一行」错认成「同一行变了」。

### 匹配与 ref 稳定性

三轮由强到弱，**不做同类名同深度的兜底匹配**：

1. 完全一致（类名、深度、外框、文字、描述、状态）；
2. 同位置（类名、深度、外框）→ 文字或状态变化，沿用旧 ref；
3. 同标签（类名、深度、文字、描述、状态）→ 位置变化，沿用旧 ref。

剩下的才是新增 / 消失。列表滚动的场景里，兜底匹配会把两行不相干内容认成同一行，
模型据此点错的风险比多几行 diff 大。

`lastNodeHandles` 从 `List` 改成 `Map<ref, AccessibilityNodeInfo>`：ref 不再等于下标，
语义点击 / 坐标命中 / 语义滚动都按 ref 取句柄（同坐标取最后插入的 = 最深，顺序仍是前序）。

**读屏状态是任务级的**：debug `TASK` 广播在任务开始时、`press(home)` 成功后都会清空
`lastSnapshot`。跨任务沿用会让新任务的第一次读屏回一句 `unchanged` —— 而模型在新会话里
根本看不到上一屏的内容（实测 `scroll` 任务因此卡住）。页面记忆（指纹 → 访问次数）不受影响。

### 页面指纹与页面记忆（roadmap §1.3）

每次读屏按 `(包名, 节点结构)` 算 8 位十六进制指纹：只哈希类名、深度、状态位，
**文字 / 描述 / 坐标一律不进**。因此同一页面在天气数值、消息内容、时间变化后仍是同一指纹，
滚动或增删节点才会变。

指纹用于行尾一行提示：`page=3f9a12cd new` 或
`page=3f9a12cd known visits=4 last=tap@(540,300)`。

页面记忆落在 `SharedPreferences("tappet_page_memory")`（`device/PageMemoryStore.kt`）：

- 只存 `访问次数 | 上次成功动作 | 时间戳`，动作只保留类型与坐标（`tap@(x,y)`、`swipe up`、`type`），
  **不存任何正文** —— 即使文件被读走也还原不出屏幕内容；
- 最多 64 页，按时间淘汰；
- 跨会话保留：页面缓存的价值就在「这页上次怎么操作」，只活在内存里重启即失效。

已知局限：模板完全相同的页面（同一套布局的设置子页）会撞指纹。所以它只用于提示，
**不参与任何判定** —— 撞了指纹最多是提示不准，不会让模型跳过错内容。
`visits` 是「读屏次数」，不是独立访问次数。

### 验证

- `ScreenDiffTest`（JVM）：未变 / 文字变化 / 新增 / 消失 / 移动、ref 连续、阈值判定、
  diff 与 unchanged 渲染、指纹忽略正文、page hint 两态。
- 真机：同一屏连续两次 `READ_SCREEN` 广播，第二次应回 `unchanged`；
  点开一个二级页再返回，应看到 `[+n]` / `[-n]` 或 `known` 提示。

### 兜底开关

`read_ui_tree(full=true)` 强制全量。工具描述已明确告诉模型：**看不到上一次完整输出时用它**
（上下文被截断是最现实的触发条件）。


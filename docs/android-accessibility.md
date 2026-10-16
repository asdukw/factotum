# Android 无障碍执行层：系统探测与「四问」运行手册

**状态（2026-10-03）**：系统侧探测完成；**四问探针已实现并在真机跑通**
（Tappet 控制组 + QQ 试验场，结论见 §7）。执行层走**控件树优先、截图兜底**，
可以开始写工具；QQ 是后续两个试验任务的目标 App（选联系人聊天 / 群聊发言 @ 回复）。

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

## 5. 四问 Runbook（已实现）

### 代码分层（ROM 无关契约 / Android 实现 / OEM 档）

- `app/src/main/java/com/tappet/app/device/` —— **通用契约**：
  `DeviceCapability`（能力清单）、`CapabilityResult`、`DeviceBridge`（操作入口）、
  `DeviceBridgeState`（连接与结果状态）、`OemProfiles`（OemProfile：启用路径、adb 命令、已知限制）。
- `app/src/main/java/com/tappet/app/accessibility/` —— **AOSP 无障碍实现**：
  `TappetAccessibilityService`（`DeviceBridge` 实现，不绑 ROM）。
- `ui/probe/` —— 探针页只依赖 `DeviceBridge` 契约，不引用具体服务类。
- `app/src/debug/` —— debug-only 的 adb 广播入口 `ProbeCommandReceiver`（release 不含）。

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

四问可用两种方式触发：

1. **UI**：App 首页右上角「探针」→ 按钮带 8 秒倒计时，切到目标 App 停稳后自动执行。
2. **adb（仅 debug 构建）**：目标 App 在前台时直接广播，无需倒计时 ——

```powershell
adb shell am broadcast -n com.tappet.app/.accessibility.ProbeCommandReceiver `
  -a com.tappet.app.debug.PROBE --es capability READ_TREE   # SCREENSHOT / SET_TEXT / TAP
```

结果同时进 `Logcat`（tag `TappetProbe`，只记计数 / 类名 / 坐标 / 错误码，不落正文）。

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

微信 8.0.78 对无障碍**完全不暴露控件树**：`read_screen` 只返回 1 个节点，
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

所以触摸探索开启期间（= 触摸探索）**点击/长按/上下滚动必须走 `performAction`
语义动作**（`ACTION_CLICK` / `ACTION_LONG_CLICK` / `ACTION_SCROLL_FORWARD|BACKWARD`），
`tap_point` 退化为「点中坐标节点的语义点击」。实测微信会话行、EditText、发送按钮、
ListView 滚动都能用语义动作完成；但**部分节点（如聊天列表右上角搜索图标）报告
`isClickable=true`、`performAction` 返回 true 却无任何反应** —— 语义路径不是万能的。

反过来，触摸探索**常开**会让用户的手动单击变成「探索 + 双击」、单指拖动变成探索，
手机没法正常用 —— 所以只能按任务开关。

### 落地方案：任务期触摸探索（按任务开关）

- `accessibility_service_config.xml` 声明 `canRequestTouchExplorationMode="true"`，
  `onServiceConnected` 里立刻 `setTouchExplorationForTask(false)` 复位，默认不影响用户手动操作。
- `TappetAccessibilityService.setTouchExplorationForTask(enabled)` 用 `setServiceInfo` 改
  `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`；实测系统在 ~600ms 内切换
  `touchExplorationEnabled`，无需重启服务。
- 任务生命周期（`DshStreamer` / `NodeDebugReceiver`）开始时开、结束时关（`finally`）。
- 读屏拿到 < 2 个节点时，payload 追加 `EMPTY_TREE_HINT`，让模型知道该停下来找用户，
  而不是盲点盲滑。
- debug 入口：`--es action AGENT_MODE --ez on true|false`。

### 已知限制

目标 App **必须**在 触摸探索开启之后才启动进程。若微信本来就在后台运行，
本次就读不到节点。现在的处置分两种（都写进了 `EMPTY_TREE_HINT`）：

- **只读任务**：模型改用 `screenshot` 从图上读界面。实测（2026-10-11）微信进程早于
  触摸探索启动、读屏只有 1 个节点时，模型照提示切到截图，完整读出了会话列表的
  标题、每个会话与摘要、底部导航。图像链路见 [docs/dsh-android.md](dsh-android.md) P10。
- **要点击/输入**：触摸探索开着时注入手势被吞、语义动作又必须有节点，所以点不动；
  模型会停下请用户在系统设置 → 应用管理里「强行停止」该 App 再重来。

彻底解决「已经在跑的 App」还差一条不依赖控件树的**输入**通道
（例如剪贴板 + 长按粘贴，手势在 触摸探索关掉时是可用的），目前没做。

### 真机验证（2026-10-11，不含联系人与聊天内容）

- 任务「打开微信，在聊天列表里找到指定联系人，读出最近消息说明上下文」：
  **10 次工具调用、0 错误**。产品路径（任务自动开关 触摸探索）下微信读屏 319 节点，
  正确找到联系人、向上滚动一次列表后点开会话、按左右头像区分说话人并复述上下文；
  任务结束 `touchExploration` 回到 false。
- 任务「给该联系人发一条符合上下文的消息」：**11 次工具调用、0 错误**，
  语义路径完成 `tap_node(EditText)` → `type_text` → `tap_node(发送)`，消息确认送达。
- 微信已在运行（进程早于 触摸探索）：模型按 `EMPTY_TREE_HINT` 停下，说明原因并给出
  「强行停止」这一步，全程没有盲点盲滑。


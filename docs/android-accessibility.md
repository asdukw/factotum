# 无障碍执行层

屏幕操作只有一条腿：`AccessibilityService`。控件树优先，树不可读时走视觉路径；文本输入在树不可读时走
无障碍 IME 通道。读屏、截图、手势与文本输入都依赖无障碍服务，服务被关掉时它们一起失效。
日历、闹钟与定时任务不走无障碍（见 [tools.md](tools.md)）。

改执行层之前先读本页和 [tools.md](tools.md)；真机操作入口见
[device-verification.md](device-verification.md) §3。

## 1. 代码分层

| 位置 | 职责 |
|---|---|
| `device/DeviceAction.kt` | 与 ROM 无关的原子动作契约 |
| `accessibility/ZoneuAccessibilityService.kt` | AOSP 无障碍实现，`DeviceBridge` 的唯一实现 |
| `bridge/DeviceBridgeServer.kt` | 127.0.0.1 + 每进程随机 token 的本地 HTTP 桥 |
| `device/OemProfiles.kt` | 按 OEM 给出启用路径、adb 命令与已知限制 |
| `app/src/debug/` | 仅 debug 的 adb 广播入口，release 不含 |

换 ROM 只加 `OemProfile`；新增能力扩 `DeviceBridge`，UI 与工具描述不动。

服务配置（`res/xml/accessibility_service_config.xml`）必须同时声明读树、手势、截图和
`flagInputMethodEditor`。少一条是静默失败：编译过、服务也能启用，只有真正用到时才发现。
`AccessibilityServiceConfigTest` 钉住这几条。

## 2. 控件树

`read_ui_tree` 读 `getRootInActiveWindow()` / `getWindows()`，把节点渲染成带 `ref` 的紧凑行。
全量上限 240 行。

### 增量读屏

同一屏连续操作默认只返回与上一次的差异，避免每步重发整棵树。

| 情况 | 返回 | ref |
|---|---|---|
| 首次读、换 App、上次或本次截断、`full=true` | 全量 | 前序编号（下标） |
| 只有小变化 | 增量：`[+n]` 新增、`[~n]` 变化、`[-n]` 消失 | 未变节点沿用旧编号 |
| 完全没变 | `unchanged since last read, refs still valid` | 全部沿用 |
| 变化量 > 50% | 全量 | 重排 |

匹配由强到弱：完全一致 → 同位置（类名、深度、外框）→ 同标签（类名、深度、文字、描述、状态）。
不做「同类名同深度」兜底，避免列表滚动时把两行不相干内容认成同一行。实现与阈值在
`device/ScreenDiff.kt`（`DIFF_MAX_CHURN_RATIO = 0.5`）。

读屏状态是任务级的：任务开始和 `press(home)` 成功后清空 `lastSnapshot`，新任务不会拿到
上一次的增量参照。`read_ui_tree(full=true)` 是上下文被截断时的兜底。

### 页面指纹与页面记忆

每次读屏按 `(包名, 节点结构)` 算 8 位指纹，只哈希类名、深度和状态位，**文字、描述、坐标不进**。
行尾给 `page=<hash> new` 或 `page=<hash> known visits=N last=tap@(x,y)`。

页面记忆存 `SharedPreferences("zoneu_page_memory")`（`device/PageMemoryStore.kt`），最多 64 页：
只存访问次数、上次成功动作类型与坐标、时间戳，不存任何正文。它只做提示，不参与判定；
模板相同的页面会撞指纹，最多提示不准。

## 3. 视觉路径

树没有可读节点时（判据 `ScreenSnapshot.hasNoReadableNode()`，不是节点总数），payload 追加
`EMPTY_TREE_HINT`，模型改走 `capture_screenshot` → `tap_target`。

### 帧契约

- `capture_screenshot` 把截图缩到最长边 2048，payload 带 `screenshotId / width / height /
  screenWidth / screenHeight / offsetX / offsetY / base64`。
- 模型给的坐标一律是**该截图的图像像素**。屏幕像素由 `ScreenFrame.toScreen` 换算
  （`round(offset + x * screenW / imageW)`，夹到边界），这是唯一的换算点。
- 屏幕尺寸取缩放前的捕获位图，不取 `displayMetrics`；不一致时记 `ev=frame_mismatch`。
- 截图有状态：界面变了旧截图作废，返回 `STALE_SCREENSHOT`；bbox 越界返回
  `OUT_OF_SCREENSHOT`，**不 clamp**。
- 动作后重新截图算 8×8 指纹，只回 `changed` 布尔。`changed=false` 时 `ok` 仍为 true
  （`NO_VISIBLE_CHANGE`）——它是提示，不是失败。小面积变化（勾选框、行高亮）看不出来。

图占请求预算（默认 20MiB，历史图每轮重发）。profile 启用 `image-offload`，超预算时丢最老的图
重试，代价是**永久丢帧**。长视觉任务应尽量少截图。

视觉路径不解决「模型能不能在图上找到目标」；首点命中率只能靠真机与审计回路量。

## 4. 文本输入

顺序固定：**控件树优先，IME 通道兜底**。

1. 树可读：找 `isEditable` 节点，`ACTION_FOCUS` + `ACTION_SET_TEXT`，读回校验。
2. 树不可读：`flagInputMethodEditor` 让 `AccessibilityService.getInputMethod()` 拿到当前
   聚焦输入框的 `InputConnection`，用 `commitText` 写入；`press(enter)` 用
   `performEditorAction`，没有声明动作时退回 `KEYCODE_ENTER`。

通道要求输入框**已有焦点**：视觉路径点不中输入框时它同样帮不上忙。通道只写不读，没有读回验证。
Android 13 以下没有这条通道。

失败码各自对应不同的下一步，不能合成一句：

| 码 | 含义 |
|---|---|
| `text_no_channel` | 没有输入框聚焦，先点一下输入框 |
| `text_flag_missing` | 升级后没重新启用无障碍，服务配置未生效 |
| `text_unsupported_os` | Android 13 以下 |
| `text_commit_rejected` | 有连接但写不进去 |
| `text_set_text_rejected` | 控件树路径写不进去 |

`device/TextInput.kt` 是选择失败码的纯函数，`TextChannel.flagActive()` 区分「配置没生效」与
「没有焦点」。审计只记 `ev=text_input via=tree|ime_channel|none chars=N`，正文不落日志。

## 5. 触摸探索与 TalkBack

任务期**不**开触摸探索。原因：触摸探索开启后系统会把 `dispatchGesture` 注入的手势当作探索事件
吞掉，点击/滑动只能退回 `performAction`，而树不可读时又没有节点可用；并且目标 App 是否暴露
节点是它进程启动时采样一次并缓存的，App 内无法让别的应用重新采样。

- 任务生命周期不调用 `setTouchExplorationForTask`，手势始终可用。
- 机制与 debug 入口 `AGENT_MODE` 保留供实验；`onServiceConnected` 复位为 false。
- 用户自己开着 TalkBack 时全局触摸探索恒为真，`touchExplorationActive` 的 `performAction`
  兜底分支**不能删**——那是读屏用户唯一可用的路径。
- `setTouchExplorationForTask` 的语义兜底同样保留。

## 6. ROM 与设备

- `adb install -r` 会摘掉无障碍服务，装完必须重新启用。
- ColorOS 会对带无障碍的 App 弹「保持开启 (3)」倒计时；不点可能被关闭。
- ColorOS 在启用/重装后推迟后台广播，测试前先把 App 拉到前台。
- `uiautomator dump` 会顶掉无障碍约 1 秒，自动化期间不要用。
- ColorOS 会冻结后台进程，任务期间靠 `NodeTaskService` 前台服务；前台服务也不保证不被冻结。
- 侧载 App 位于「受限设置」；从设置界面启用服务前需允许受限设置。`pm grant` / `appops set`
  被 ColorOS 拦截，别反复试。
- `takeScreenshot` 对 `FLAG_SECURE` 窗口返回 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`；
  FLAG_SECURE 是逐窗口属性，不能用一个 App 的结论代表全部。

## 7. 真机验证

debug 广播驱动原子动作：

```powershell
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action READ_SCREEN
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action CAPTURE_FRAME
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action TAP_TARGET --es screenshot_id f1 --ei x1 400 --ei y1 1000 --ei x2 543 --ei y2 1047
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action TEXT_CHANNEL
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action IME_TEXT --es text "hello"
```

判据：同一屏连续两次 `READ_SCREEN`，第二次回 `unchanged`；换页后出现 `[+n]` / `[-n]`；
`CAPTURE_FRAME` 报「图像 WxH｜屏幕 WxH」；树可读时 `TYPE_TEXT` 走 `via=tree`，
树不可读且已点中输入框时走 `via=ime_channel`；未聚焦时返回 `text_no_channel`。

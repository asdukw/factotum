# dsh-plugin-android-tools

![test](https://github.com/asdukw/dsh-plugin-android-tools/actions/workflows/test.yml/badge.svg)

[DeepSeek Harness](https://deepseek.com/harness/)（`dsh`）插件：把设备 UI 自动化桥变成
agent 工具 —— 读屏、截图、点按、长按、输入、系统键、启动应用、滑动。

插件本身是一个**平台无关**的薄 HTTP 客户端：它只注册工具并把调用转发给实现下方契约的
**桥宿主**；读控件树、注入手势等设备相关工作都在宿主里。参考宿主运行在 Android 上
（`AccessibilityService`），但任何实现该契约的系统都能用同一个插件。

[English](README.md)

## 前置条件

- 运行中的 `dsh`，带 `@deepseek-ai/dsh-tools` >= `0.1.0-rc.6`（peer dependency）。
- **一个桥宿主**：本仓库只发布客户端这一半，宿主不在这里发布。可以对接已有宿主，
  或按下方契约实现（见[编写宿主](#编写宿主)）；仓库内附带一个可运行的 mock 宿主
  用于测试。没有宿主时，工具调用会以明确错误失败。

## 工具

| 工具 | 说明 |
|---|---|
| `read_ui_tree(full?)` | 读取当前屏幕控件树，输出形如 `[12] TextView "发送" clickable @(900,2200)` 的行。连续读屏只回差异（`[+ref]` 新增 / `[~ref]` 变化 / `[-ref]` 消失），`full=true` 强制全量 |
| `tap_node(ref)` | 点击上次 `read_ui_tree` 输出里的节点 |
| `capture_screenshot()` | 截取一张**截图**：图 + 尺寸信息（图像/屏幕尺寸、`screenshot_id`） |
| `tap_target(screenshot_id,x1,y1,x2,y2)` | 点击你在该截图里看到的目标 —— 外框用**图像像素**，换算由宿主做 |
| `long_press_target(screenshot_id,x1,y1,x2,y2,duration_ms?)` | 长按截图里的目标（默认 700ms，可选 300–2000ms） |
| `long_press(ref,duration_ms?)` | 长按上次 `read_ui_tree` 输出里的节点 |
| `type_text(text)` | 把文本写进当前聚焦输入框；只输入不发送 |
| `press(key)` | 按 `back` / `home` / `recents` / `enter`；取消/退出用 `back` 代替 Esc |
| `launch_app(query)` | 按应用名（模糊）或包名启动 |
| `swipe(direction)` | 向 `up` / `down` / `left` / `right` 滑动约半屏 |
| `run_command(cmd,timeout_ms?)` | 在手机上的 Linux（内嵌 proot + Debian）里执行 shell 命令，回 stdout / stderr / 退出码 |

`run_command` 与其余工具不是一回事：那些是**操作界面**（可逆、看得见），它改的是**文件系统**（没有撤销，也不在屏幕上）。
它是真命令 —— 能跑 `git` / `python` / `apt` / `npm`，但每条命令是独立的一次会话（`cd`、环境变量、后台进程都不保留），
且没有 `docker` / `systemd` / `iptables`（proot 不是真内核）。

**它只在宿主注入 `FACTOTUM_PROOT=1` 时才注册** —— 没有这个运行时的旧版宿主不会在工具表里看到一个必然失败的工具。

它和日历 / 闹钟一样**过审批闸门**：`tools/pre-execute` 返回 `ask`，审计里的 `reason` 只写「执行 shell 命令」，
命令原文走 `displayReason`（只进面板，不落盘）。

工具是与具体 App 无关的原子动作，由模型组合成任务。
`capture_screenshot` 的图要经**附件服务**（`ctx.attachments`）落盘才能进模型请求。宿主没挂
附件服务时它不会报错，而是退回纯文字（说明截图成功但图没交给模型）。桥宿主把截图编码成
`{ mediaType, width, height, base64 }` 放进 `payload` 即可。


## 定时任务

通过对话调用 `schedule_task` 创建；`list_scheduled_tasks` 查询 id 与下次触发；
`update_scheduled_task` 修改 / 停用 / 恢复；`delete_scheduled_task` 删除。三个写工具全部走审批。

创建必填 `title / prompt / hour / minute`，时间为设备本地 24 小时制。`schedule_type` 支持：

| 规则 | 参数与示例 |
|---|---|
| `daily`（默认） | 每天8点 → hour=8, minute=0；旧调用兼容 |
| `weekly` | 每周三9点 → weekday=3, hour=9, minute=0；周一=1、周日=7 |
| `monthly` | 每月15号18点 → day_of_month=15, hour=18, minute=0；短月无该日跳过 |
| `yearly` | 每年6月1日9点 → month=6, day_of_month=1, hour=9, minute=0；2月29日跳过非闰年 |
| `once` | 2027年1月5日10点 → date="2027-01-05", hour=10, minute=0；过去时间拒绝，领取后停用 |

仅提供该规则相关字段。更新省略字段不变，切规则清旧参数并要求新参数；缺失或歧义的日期 / 时间先问用户，
不能静默变成每天。列表回 `schedule / timezone / next_trigger`，停用或完成时无下次触发。
不新增宿主权限，不提供 cron；精确闹钟授权、ROM 后台限制、锁屏与原有写审批仍然适用。

## 系统提示变量

插件注册一个提示变量 `device_now` —— 设备本地时间，形如 `2026-10-25 02:17 周日（UTC+08:00）`，
每次组装系统提示时重新解析。在提示里用它：`当前设备时间：{{device_now}}`。没有它模型就没有时钟：
任何带相对日期的任务，它都会先打开时钟 App、点进世界时钟，只为了问今天几号。
变量只在宿主挂了 `system-prompt` 时存在（插件声明 `inject: ['tools', 'systemPrompt']`），所以提示里
引用了 `{{device_now}}` 却没装这个插件时，组装会直接报错 —— 比发一份带空洞的提示好。

## 安装

```bash
# 从 git 安装，锁定 release tag：
dsh plugin add github:asdukw/dsh-plugin-android-tools#v0.3.0

# 或安装任意 GitHub Release 附带的 tarball：
dsh plugin add ./dsh-plugin-android-tools-0.3.0.tgz

# npm 发布启用后也可以：
dsh plugin add dsh-plugin-android-tools
```

包内声明了 `dsh.bundle` 层（`cordis.patch.yml`），安装时自动插入插件行；之后重启 `dsh`。

## 配置

| 变量 | 含义 |
|---|---|
| `ANDROID_BRIDGE_URL` | 桥的基础地址，如 `http://127.0.0.1:37812` |
| `ANDROID_BRIDGE_TOKEN` | 每个请求校验的共享 token，通常由宿主每次启动随机生成 |
| `FACTOTUM_PROOT` | 宿主在内嵌 Debian（proot）就绪时设为 `1`；缺省则不注册 `run_command` |

宿主 App 或启动器一般会注入这两个变量；也可以在启动 `dsh` 的环境里手动导出。
任一缺失时所有工具调用以 `ANDROID_BRIDGE_URL/ANDROID_BRIDGE_TOKEN not set` 失败。

## 使用

推荐的循环是**先读屏、操作、再读屏确认**：

```text
User: 打开时钟应用，设置一个 5 分钟计时器
Agent: launch_app("Clock") → read_ui_tree → tap_node(12) → type_text("5") → …
```

想在没有真机的情况下试跑，用仓库自带的 mock 宿主（固定屏幕、无需设备）：

```bash
node examples/mock-host.mjs   # mock host on http://127.0.0.1:37812 (token: dev-token)
export ANDROID_BRIDGE_URL=http://127.0.0.1:37812
export ANDROID_BRIDGE_TOKEN=dev-token
```

mock 会确认所有动作，并为 `read_ui_tree` 返回一份固定控件树。

## 桥契约

```
POST {ANDROID_BRIDGE_URL}/action
header: x-android-bridge-token: <ANDROID_BRIDGE_TOKEN>
body:   { "action": "read_ui_tree" | "tap_node" | "long_press"
                    | "capture_screenshot" | "tap_target" | "long_press_target"
                    | "type_text" | "press" | "launch_app" | "swipe",
          ...动作参数 }
```

成功：`{ "ok": true, "payload": "…" }`；失败：`{ "ok": false, "summary": "…" }`。

| 动作 | 参数 | payload |
|---|---|---|
| `read_ui_tree` | `full`（布尔，可选） | 控件树文本，一行一个节点（`[ref] 类名 "文本" [flags] @(x,y)`）；宿主可以回与上次的增量 |
| `tap_node` | `ref`（整数） | 结果摘要 |
| `tap_target` | `screenshot_id`、`x1`、`y1`、`x2`、`y2`（图像像素） | 结果摘要；`changed` 说明界面有没有变 |
| `long_press_target` | 同 `tap_target`，另加可选 `duration_ms` | 结果摘要；`changed` |
| `long_press` | `ref`，可选 `duration_ms` | 结果摘要 |
| `type_text` | `text`（字符串） | 结果摘要 |
| `press` | `key`（`back` / `home` / `recents` / `enter`） | 结果摘要 |
| `launch_app` | `query`（字符串） | 结果摘要 |
| `swipe` | `direction`（`up` / `down` / `left` / `right`） | 结果摘要 |
| `capture_screenshot` | – | `{ "mediaType", "width", "height", "screenshotId", "screenWidth", "screenHeight", "offsetX", "offsetY", "base64" }` 的 JSON 字符串 |

Android 宿主仍接受 `press_back` 作为 `press(back)` 的兼容别名，但插件不再把它注册成模型工具。
桥应只监听本地（如 loopback）并校验 token：读屏内容可能包含个人数据，桥还能驱动设备。
payload 只交给模型，不要写日志。

## 编写宿主

宿主就是任何能读 UI、注入输入的进程；上方契约就是全部接口：

| 宿主所在平台 | 典型构件 |
|---|---|
| Android | `AccessibilityService`（`getRootInActiveWindow`、`dispatchGesture`、`ACTION_SET_TEXT` 等） |
| Windows | UI Automation |
| macOS | Accessibility API（`AXUIElement`） |
| iOS | WebDriverAgent / XCTest |
| 其他 | 任何能产出 `read_ui_tree` 文本并接受点击的系统 |

检查清单：

- 只监听 loopback（或做强鉴权），每个请求校验 `x-android-bridge-token`；
- 把 `action` + 参数映射到平台原语，返回 `{ok, payload}` 或 `{ok, summary}`；
- `read_ui_tree` 按文档格式一行一个有效节点 —— 模型依赖 `[ref]` 稳定，`tap_node` 才能点中；
- 宿主如果回增量，未变化的节点必须沿用上一次的 `[ref]`，并且 `full=true` 必须仍能回全量
  （模型的上下文可能已经把上一次输出截断掉）；
- ROM/OEM 怪癖都留在宿主里，工具接口保持不变。

`examples/mock-host.mjs` 是约 60 行的参考实现（解析/鉴权/回包形状）。

## 平台说明

- 插件里没有任何 OS 相关代码 —— 只说 HTTP。要移植到别的平台，写一个桥宿主即可，
  插件本身不用改。
- ROM/OEM 差异（无障碍权限、手势注入怪癖、控件树差异）都放在桥后面，工具接口保持稳定。

## 通用边界

本插件只暴露与 App 无关的基础原语。聊天、购物、设置等业务语义由模型组合完成，
不在插件里内置页面专用工具。

## 开发

烟测无需安装依赖（用 loader stub 顶替 `@deepseek-ai/dsh-tools`）：

```bash
npm test
```

发布：推 `v*` tag。`release` workflow 会跑测试、打包，并创建附带 `.tgz` 的 GitHub
Release。npm 发布是同一 workflow 里的 gated job：先本地发首个版本（`npm login` 后
`npm publish --access public`），在 npmjs.com 添加 trusted publisher（package →
Settings → Trusted Publisher → GitHub Actions：user `asdukw`、repository
`dsh-plugin-android-tools`、workflow filename `release.yml`、允许 `npm publish`），
然后启用：

```bash
gh variable set NPM_PUBLISH_READY --body true -R asdukw/dsh-plugin-android-tools
```

## 许可

MIT

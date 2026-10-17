# tools/audit —— 任务审计回路

替代「人工跑一遍、翻日志、凭印象说哪里不对」的取证层。
**这一层是确定性的脚本，判断留给 `tappet-auditor` agent。**

## 为什么要结构化

审计要回答的是「这次执行对不对、路径能不能更省」。这是**对比题**：
没有可累积的指标，「改了工具描述之后步数少了没有」永远答不上来。
所以这里把 dsh 会话的原始记录归一化成两样东西：

- `events.jsonl` —— 每步一行（任务 / 助手回合 / 工具结果 / 收尾 / **设备标记**）
- `metrics.json` —— 步数、工具调用分布、错误、空转、载荷字符、图像、设备标记数、墙钟
- `device-log.txt` —— App 侧审计标记原文（logcat，tag `TappetAudit`）
- `runs/index.json` —— 所有历史运行的指标索引，**跨运行的模式只在这里看得出来**

## 设备标记（为什么还要抓 logcat）

有些状态**不在 dsh 会话里**，只在 App 侧知道。当前三类：

| 事件 | 含义 |
|---|---|
| `exploration_request` | 我们**请求**把触摸探索开/关（`want=` / `changed=`）。任务路径已不再自动开（2026-10-17），现在只有 debug 广播会打 |
| `exploration_observed` | 动作执行前观测到的**实际**状态（`active=`），只在变化时打 |
| `frame_mismatch` | 截图捕获位图的尺寸与 `displayMetrics` 不一致（`capture=` / `metrics=`，只有尺寸数字）。视觉路径的坐标换算以捕获尺寸为准，这条标记让 ColorOS 的显示缩放 / 异形屏造成的偏差**看得见** |

前两者必须分开，`exploration_observed` 才是审计用的那一个。因为系统取的是**所有已启用
无障碍服务的或**：用户自己开着屏幕阅读器（TalkBack 等）时，全局触摸探索一直是开的，
我们置位或清零自己的 flag 都不改变实际状态。**拿请求值当实际值，会在用户开着屏幕阅读器时
把「预期的手势失效」误判成故障，方向正好反了。**

`exploration_observed` 在**动作前**读，不是在切换后回读 —— `serviceInfo` 由系统异步生效，
刚写完那一瞬读到的可能是旧值。

App 侧用固定 tag `TappetAudit` 打 `ev=<名> <k>=<v>` 一行；`collect.mjs` 抓这段 logcat
（`adb logcat -d -v epoch -s TappetAudit:I`），按 **epoch 毫秒**并进 `events.jsonl`
（dsh 记录的 `time` 也是 `Date.now()`，同一时基）。窗口外的标记会被丢掉并在
`metrics.deviceMarkers.dropped` 里如实计数——logcat 缓冲区常留着上一次任务的标记。

`metrics.deviceMarkers.total` 为 0 是**警报**，不是「状态正常」：说明标记没抓到
（logcat 被冲掉，或 App 侧 `AUDIT_TAG` 改了没同步）。`run-suite.mjs` 每个任务前会
`logcat -c`，让采集到的标记只属于该任务。

**加新标记**：在 Kotlin 里调 `audit("事件名", "k" to v)`，值里不要有空格；
不用改 `collect.mjs`，它会把任何 `ev=` 行原样并进事件流。

## 用法

```bash
node tools/audit/selftest.mjs                  # 离线自检（解码器，不需要设备）
node tools/audit/run-suite.mjs                 # 跑固定任务集 + 逐个采集
node tools/audit/run-suite.mjs --only calc     # 只跑 id 匹配的任务
node tools/audit/collect.mjs --label my-run    # 只采集最新会话
node tools/audit/collect.mjs --session <目录名> --label baseline
```

设备必须在线；离线时脚本会直说，不会假装有数据。

然后把 `tools/audit/runs/` 交给 **`tappet-auditor`** 子 agent 出报告。

## 三个刻意的设计

**1. 包名不硬编码。** 唯一的来源是 `android/app/build.gradle.kts` 的 `applicationId`，
每次现读。上一轮改名时有一批手工脚本因为硬编码旧包名一起失效 —— 而且是**静默**失效：
路径不存在，报出来像"没有 session 目录"，看着像设备问题。这类脚本一律现读。
需要覆盖时用环境变量 `TAPPET_PACKAGE`。

**2. 会话容器目录也不硬编码。** dsh 的 `sessions/--data-user-0-<包名>-files-dsh-home--`
是派生的，改名就变。这里直接列目录找。

**3. 任务集固定，且不动老 id。** 见 `suite.json` 的 `note`：改了任务文本等于换了任务，
历史数据作废。任务默认选只读或可逆的——审计要跑很多遍，不能每次都真发消息。

## 隐私

`runs/` 里的 `events.jsonl` 与 `raw.zstd` **含屏幕正文**（读屏 payload 原文），
已在 `.gitignore` 里。`collect.mjs` 默认把载荷裁到 600 字符（`--max-payload` 可调），
但那是为了可读性，不是脱敏——**整个 `runs/` 目录都按敏感数据对待**。
审计 agent 的报告里不得出现正文，只能写结构（长度 / 节点数 / 类名 / 坐标）。

## 前身

早期有一批手工脚本（拉会话、打印轨迹）散在 `tmp/` 下，已被这套东西取代并清理。
它们的毛病是共同的：把轨迹打印成给人看的散文、硬编码包名、改名后**静默**失效。
`tmp/` 现在是纯本地草稿区（整体 gitignore，不提交占位文件）——
**要留存的东西放 `tools/` 或 `docs/`，不要放 `tmp/`**。

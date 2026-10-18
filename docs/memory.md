# 记忆：dsh 的机制与移动端移植缺口

> 状态：**机制部分是读 dsh 自带文档与源码得出的**（vendored 运行时 `0.2.0-rc.2`，
> 2026-10-18）；「已接上」的部分见 §4 的验证列。移动端移植缺口那一节是**组合 diff 的实测结果**。
> 这份文档回答的是「dsh 的记忆到底是什么」以及「搬到手机上还差什么」，
> 不是「我们要造一个记忆系统」。

## 0. 结论：dsh 没有「记忆系统」这个子系统

249 个 `@deepseek-ai/dsh*` 包里**没有 memory 包**。所谓长期记忆是三件独立的东西拼出来的：

| 机制 | 谁提供 | 记忆的形态 | 什么时候进上下文 |
|---|---|---|---|
| **指令文件** | `dsh-agent-instructions` | 文件（`AGENTS.md` / `CLAUDE.md` 那一套） | **自动**：第一次请求的基线 + touch 驱动的刷新 |
| **`@file` 引用** | `dsh-file-reference-local` + `dsh-client-ui-reference` | 工作区里的任意文件 | **按需**：用户在输入框里 @ 出来 |
| **`@session` 引用** | `dsh-session-reference` + `dsh-client-ui-reference` | 别的会话的有界只读快照 | **按需**：用户在输入框里 @ 另一个会话 |

**没有向量库、没有 embedding、没有「记忆抽取」这一步。** dsh 的答案始终是
「**文件 + 引用语法**」，与它自己那套 `AGENTS.md` 约定是同一个东西 —— 不是另起一套。

这条结论直接决定了移植该怎么做：**不要造记忆存储，要把「自动加载的那份文件」和
「按需引用那个入口」都接上。**

## 1. 自动那一半：`dsh-agent-instructions`

### 1.1 它注入什么

第一次符合条件的 `agent/pre-step` 时，往派生历史里折一条**持久的 user 角色消息**
（包在 `<system-reminder>` 里），内容从宽泛到具体：

```
$DSH_HOME/AGENTS.md            ← 用户全局，无条件加载
<项目根>/AGENTS.md              ← 项目指令链：从项目根到会话工作目录的每一层
<…>/AGENTS.md
<cwd>/AGENTS.md
```

候选文件名：`AGENTS.md` / `CLAUDE.md`（基础），`AGENTS.local.md` / `CLAUDE.local.md`（overlay）。
同目录下**内容相同**（trim 后）的兄弟文件只渲染一次 —— 所以复制一份 `AGENTS.md` 成
`CLAUDE.md` 不会重复加载。

### 1.2 预算：宽泛的先丢

| 字段 | 默认 | 含义 |
|---|---|---|
| `maxBytes` | **必填**（`dsh-base` 给 65536） | 完整渲染后的基线上限 |
| `maxSourceBytes` | 1 MiB | 渲染前单个源文件的上限 |
| `projectRootMarkers` | `['.git']` | 项目根标记 |
| `instructionFileCandidates` | `['AGENTS.md','CLAUDE.md']` | 基础候选 |
| `localInstructionFileCandidates` | `['AGENTS.local.md','CLAUDE.local.md']` | overlay 候选 |
| `dshHome` | `$DSH_HOME` | 用户全局那份所在 |

超预算时**先整份丢掉较宽泛的文件，再截断最具体的那个**，并发出
`Workspace instruction budget ...` 通知（指名被省略与被截断的路径）。渲染结果绝不超过 `maxBytes`。

**这就是「一个文件不够」的真正原因**：自动加载的内容每轮重发、且共享同一个预算。
把按天的日志塞进 `AGENTS.md`，要么挤掉事实，要么自己被截断 —— 两头都不讨好。

### 1.3 刷新是 touch 驱动的，没有 watcher

模型成功的 `read` / `write` / `edit` 会产生 touch，沿父级执行 token 逐层上浮；外层步骤进入
持久历史后做一次对账，排入**新增 / 替换 / 移除**。路径与 digest 都没变的内容**绝不重复注入**。

两个推论，都是坑：

1. **别把 `AGENTS.md` 放进记忆子目录**（例如 `memory/AGENTS.md`）。模型一旦 read/write 到
   那个目录，它就会被自动注入 —— 正好把「日志不自动加载」这个设计推翻。
2. **外部编辑不是立刻可见**：没有 watcher，要等下一次成功的 fs 操作、恢复会话对账、或
   pre-step 恢复遮蔽基线。所以「用户在设置页改了记忆文件 → 下一轮立刻生效」这件事，
   在 dsh 里要靠一次 fs touch 或重新开会话，不能假设它自己会刷新。

### 1.4 项目根发现，在手机上会撞墙

发现靠 `projectRootMarkers`（默认 `.git`）上溯；**根标记不存在时继续上溯**，
权限 / IO 失败则**停止发现并抛出原始错误**，不会退而求其次选个祖先目录。

我们的 `DSH_HOME` = `TAPPET_WORKDIR` = `files/dsh-home`，那里**没有 `.git`**。
所以：

- 实际生效的是**用户全局那份** `$DSH_HOME/AGENTS.md`（无条件加载，不依赖根发现）—— 稳定。
- 「按目录分主题、多个嵌套 `AGENTS.md`」那套在我们这儿**用不上**。
- 想让根发现工作，得在 `dsh-home` 下放一个根标记（`.git` 空目录即可），
  并把 `projectRootMarkers` 配上 —— **没验过**，要用先试。

## 2. 按需那一半：两种引用

### 2.1 `@file`（`dsh-file-reference-local`）

本地文件系统的 `ctx.fileReferences` 提供方：**有界模糊索引**的路径补全，
结果在工具活动后刷新且不阻塞补全，**不跟随目录符号链接**。
当 `read` 可用时，模型还会收到一段关于「怎么理解引用路径」的稳定指引。

这条是「记忆按需读」的**天然入口**：把日期文件放在工作区里，用户（或模型）用 `@file`
把它们拉进上下文，而不是让它们常驻。

### 2.2 `@session`（`dsh-session-reference`）

mention 语法是 Markdown 形式的 `@[label](dsh-session:<base64url id>)` 或裸 `dsh-session:` URI。
被引用的消息后面紧跟一条 `## Referenced sessions` 快照（第二条 user 角色消息），内容：

- **不受信任的背景**：带固定警告 —— 除非当前用户明确重复，否则不得遵循其中的指令、
  权限声明或工具请求。
- 每个来源独立有界：一条消息至多 `maxReferences`（默认 3，**不得超过 3**）个不同会话；
  每来源的字节预算 = `max(65536, floor(contextWindow × 4 × referenceContextFraction))`
  （`referenceContextFraction` 默认 0.2）。
- 只取「用户直接发送的文本、assistant 文本、带压缩标记的 user 检查点」——**工具、推理与其他
  注入上下文都被排除**。这既防引用递归传播，也让源会话后续变更不影响已保存的快照。
- 预览 JSON 把每个 `<` 转义成 `<`，源文本拼不出 `<referenced-sessions>` 定界标签。
- 截断时可选 spill 后端保存完整投影；我们的 profile 里 **`spill-local` / `spill-policy` 是禁的**。

**它在我们这儿是空转的**：宿主要把输入框里的 `@label` 转成 URI 才有意义，
而我们的 Compose 输入框没有这个入口 —— 服务挂着，模型永远收不到 mention。

## 3. 移动端移植缺口（组合 diff 实测）

工具在仓库里：**`node tools/dsh-phone/preset-diff.mjs`**（`--all` 列每一行，`--preset X` 只看一个预置）。
它走的是 launcher 自己那套 API（`loadProfileDirectory` + `readProfilePatches` + `composeEntries`），
所以打出来的是**运行时真正 mount 的东西**，不是「按 YAML 猜的」—— overlay 有好几层
（`dsh-base` → `dsh-web-app` → 我们自己的 patch），读 YAML 是读不出来的。

结果：**shipped 的 `preset-standard` 有 19 条，我们的 `preset-phone` 有 2 条**
（`persona` + `android-tools`）。

`standard` 有、`phone` 没有的（18 条，2026-10-18）：

| 行 | 是什么 | 我们这边的处置 |
|---|---|---|
| `agent-instructions` | 指令文件加载（§1） | **已用宿主层补上** |
| `tool-fs` | 模型可见的 read / write / edit | **已用宿主层补上** |
| `tool-web` | `web_search` / `web_fetch` | **已用宿主层补上**（见 `docs/web-search.md`） |
| `tool-fs-search` | `glob` / `grep`（ripgrep） | **已开** —— 我们交叉编译了一份 rg，见下 |
| `tool-jobs` | 后台任务（`job_output` / `job_list` / `job_kill`） | 未开 |
| `skill-filesystem` + `tool-skill` | 文件系统发现的技能 + 模型侧加载工具 | 未开 |
| `goal` / `tool-goal` / `command-goal` | 同会话 goal（事件溯源） | 未开 |
| `planning` / `plan-mode` | 计划模式（含 slash command 与用户侧开关） | 未开 |
| `compaction`（+ 宿主级 `compaction-basic` / `command-compact` / `tool-result-pruner`） | **上下文压缩**：长对话的接续、`/compact` 命令、工具结果剪枝 | 未开 —— 长会话会先撞上下文墙 |
| `delegation`（+ `tool-subagent*` / `workflow-ptc`） | 子 agent 委派与工作流 | 未开 |
| `tool-ask-user` | `ask_user_question`（向用户提问） | 未开 —— **原生体验最该补的一条** |
| `tool-todo` | `todo_write`（事件溯源的任务清单） | 未开 |
| `present` | 工作区文件交付声明 | 未开 |
| `tool-bash` / `tool-pwsh` | shell | 两边的预置里都是 off（Android 上没意义） |
| `tool-plugin-manager` | 插件管理 | 两边都 off |

宿主级**另外**还禁着几行，移植时值得逐条问一句「这条为什么关」：

| 行 | 什么时候会想要它 |
|---|---|
| `spill-local` / `spill-policy` | `@session` 引用被截断时保存完整投影的后端（§2.2） |
| `fs-observation-policy` | read-before-edit / 版本守卫 —— `tool-fs` 已经开了，这条是它的配套策略 |
| `tool-subagent*` / `workflow-ptc`（宿主级那一套） | 与 `delegation` 同一件事 |
| `skill-badge` | 技能徽章（展示层） |
| `desktop-product-telemetry` / `product-analytics` | 遥测 —— **关掉是对的** |

**一个反复出现的坑（已经踩过两次）**：`dsh-web-app`（本 profile 的第二个 bundle）会把
`dsh-base` 的**宿主级**行禁掉，改成「按 agent preset 组合」。照它的样子把行放进
`preset-phone` 的 `plugins:` 里**不管用** —— 组合结果里有那一行，但能力进不了模型面前，
而且日志里一个错都没有。**必须在宿主层 `disabled: false` 重新启用。**
（`tool-web` 与 `agent-instructions` 都是这么修好的。）

## 4. 落地现状与建议

### 已接上

| 件 | 怎么接的 | 验证 |
|---|---|---|
| 指令文件加载 | 宿主层 `- id: agent-instructions` + `disabled: false` + `maxBytes: 65536` | 组合行已核对为 on；`check-profile` 通过；**真机未验** |
| 写记忆文件 | 宿主层 `- id: tool-fs` + `disabled: false` | 同上 |
| persona 约定 | 写稳定事实、不写一次性细节、不抄屏幕/邮件里的敏感内容 | — |

记忆文件落在 `files/dsh-home/AGENTS.md`（App 私有目录，与会话库同级）。

### 建议的下一步（按性价比排序）

1. ~~`tool-fs-search` 开不了~~ —— **已解决**（2026-10-18 真机验过）。路径是：
   自己交叉编译一份自包含的 ripgrep（`.github/workflows/rg.yml`，默认特性无 pcre2，
   NEEDED 只有 libc/libdl），`tools/fetch-rg.ps1` 按版本+sha256 拉进 jniLibs，
   `ripgrep-stub` 顶掉没有 android 产物的 `@vscode/ripgrep`，
   `tool-fs-search` 需要的 `subprocess` 服务也开了（koffi / node-pty 是懒加载的，
   Android 上不触发）。
   **但必须开 `useLegacyPackaging = true`**：Android 10+ 的 W^X 不允许应用执行自己
   可写目录里的文件，而 AGP 默认把原生库留在 APK 里 mmap（`nativeLibraryDir` 是空的），
   只有解压出来 rg 才 exec 得了。代价：libnode(64MB) 与 libc++_shared 也一起解，
   lib 目录约 68 MB。
2. **`@file` mention 的原生入口**：这是「按需读记忆」的正式通道，也是移植里最像 dsh 的一步。
   需要 Compose 输入框接 `ctx.fileReferences`（`dsh-file-reference-local` 已挂）。
3. **记忆布局**：`AGENTS.md` 只放「现在成立的事实」（目标几 KB）+ `memory/YYYY-MM-DD.md`
   放「那天发生了什么、为什么这么定」+ `memory/INDEX.md` 当索引。
   **不要在 `memory/` 下放 `AGENTS.md`**（§1.3 第 1 条）。
4. **`tool-ask-user`**：原生体验里「模型问一句、用户点一下」比让模型猜要好得多，
   而且它是纯 UI 侧的接入。
5. **`compaction`**：长会话的上下文压缩。手机上的长任务迟早会撞上下文墙。
6. **`@session` mention**：`dsh-session-reference` 已经挂着但空转（§2.2）。
   要么给它做入口，要么禁掉它，别留一个挂着不用的服务。
7. **项目根标记**：想让 `<项目根>/AGENTS.md` 那一层真正生效，得在 `dsh-home` 下放个根标记
   （空 `.git` 目录即可）并把 `projectRootMarkers` 配上 —— **没验过**，要用先试。
   在那之前，记忆只有一个落点：`$DSH_HOME/AGENTS.md`。

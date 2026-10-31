# 记忆与上下文注入

dsh 没有独立的「记忆系统」。长期记忆由三件互相独立的事拼成：自动加载的指令文件、按需的文件引用、
按需的会话引用。zoneu 接上了其中一部分；本页记录当前接线与缺口，改 profile 或记忆布局前先读。

## 1. 自动加载：`dsh-agent-instructions`

第一次符合条件的 `agent/pre-step` 时，运行时把 `$DSH_HOME/AGENTS.md` 作为持久的 user 消息注入
（包在 `<system-reminder>` 里）。候选文件名是 `AGENTS.md` / `CLAUDE.md`，overlay 是
`AGENTS.local.md` / `CLAUDE.local.md`；同目录内容相同的兄弟文件只渲染一次。

预算 `maxBytes` 默认 65536。超预算时先整份丢掉较宽泛的文件，再截断最具体的那个，并发通知。
自动加载的内容作为持久消息留在派生历史里、共享同一预算——所以不要把日志或一次性细节写进 `AGENTS.md`。

刷新是 touch 驱动的：模型成功 `read` / `write` / `edit` 后对账，路径与 digest 未变的内容不重复注入。
没有 watcher；外部编辑要等下一次 fs 操作或重开会话才生效。

**手机上只有一份生效**：`DSH_HOME = ZONEU_WORKDIR = files/dsh-home`，那里没有 `.git`，
项目根发现拿不到祖先目录。实际加载的是 `files/dsh-home/AGENTS.md`（App 私有目录）。
嵌套 `AGENTS.md` 那套在这里用不上。

推论：不要把 `AGENTS.md` 放进 `memory/` 子目录。模型一读写那个目录，它就会被自动注入，
等于把「日志不自动加载」这个设计推翻。

## 2. 按需引用

- **`@file`**：`dsh-file-reference-local` 已挂载，提供有界模糊路径补全，结果在工具活动后刷新，
  不跟随目录符号链接。**输入框没有 mention 入口**，所以用户目前无法主动 `@` 一个记忆文件。
- **`@session`**：`dsh-session-reference` 已挂载但空转。它要求宿主把输入框里的 mention 转成
  `dsh-session:` URI；Compose 输入框没有这个入口，模型永远收不到 mention。
  截断时的 `spill-local` / `spill-policy` 在 profile 里是禁用的。

两者要么补 UI 入口，要么从 profile 移除；不要留着挂载却不可达。

## 3. 已接上的能力

宿主层显式启用的行（`dsh-web-app` 会把 `dsh-base` 的宿主级行禁掉，只在 preset 里加不管用）：

| 能力 | 现状 |
|---|---|
| 指令文件加载 | `agent-instructions` + `disabled: false` + `maxBytes: 65536` |
| `read` / `write` / `edit` | `tool-fs` |
| `glob` / `grep` | `tool-fs-search`，交叉编译的 ripgrep + `ripgrep-stub` |
| `web_search` / `web_fetch` | `tool-web`，见 [web-search.md](web-search.md) |
| 后台任务、技能、goal、planning | `tool-jobs` / `skill-filesystem` + `tool-skill` / `goal` / `planning` |
| 上下文压缩 | `compaction` + `command-compact` + `tool-result-pruner` |
| `todo_write` | `tool-todo` |
| `ask_user_question` | `tool-ask-user` + 原生面板，见 [user-questions.md](user-questions.md) |

`tool-fs-search` 依赖的执行文件必须进 jniLibs，并且 `useLegacyPackaging = true`：
AGP 默认把原生库留在 APK 里 mmap，`nativeLibraryDir` 是空的，rg 无法 exec。

## 4. 缺口

| 项 | 状态 |
|---|---|
| `@file` mention 入口 | 未接 UI；服务已挂载 |
| `@session` mention 入口 | 未接 UI；服务已挂载但不可达 |
| 项目根发现 | 未启用；`dsh-home` 下没有根标记 |
| 子 agent 委派 / 工作流 | `delegation` / `workflow-ptc` 未开 |
| `present`（工作区文件交付） | 未开 |

## 5. 记忆布局

- `files/dsh-home/AGENTS.md`：现在成立的事实、约定与偏好，控制在几 KB。
- 需要留存的过程记录放工作区文件，由用户主动 `@file` 或模型 `read` 拉入，不自动加载。

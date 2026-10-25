# 用户提问（`ask_user_question`）：dsh 的机制与移动端接法

> 状态：**端到端已验证**（2026-10-18，Android 36 模拟器）。
> 验证的那条链路：模型调用 → 面板弹出 → 用户点选 → 答案回到模型。
> dsh 侧的机制部分是读它自带的文档与源码得出的（vendored 运行时 `0.2.0-rc.2`）。

## 0. 为什么这个功能不能只开一行配置

`tool-ask-user` 的依赖（`tools` + `userQuestions`）宿主层都有，所以**开了就能挂上、也能被调用**。
但它是**阻塞**的：等用户回答。没有 UI 接手时，实测结果是**整轮挂住** —— 会话一直 `running`、
没有任何后续事件，用户看到的是永远转圈。

**一个会永久挂住回合的工具，比没有这个工具更糟。** 所以它是「工具 + UI」成对的东西，
不能只开一半。

## 1. dsh 原生怎么做

### 1.1 工具形状（`dsh-tool-ask-user`）

```
ask_user_question(questions[])           # 一批，至少一题
  每题： id(必填) / question(必填) / detail? / header? / options?[] / multiSelect? / intent?
  选项： { label(必填), description? }
```

推荐项放第一个、label 末尾加 `(Recommended)`。

### 1.2 答案形状与三条语义

```
{ answers: [ { id, selected: string[], custom?: string } ] }
```

- **单选**：`custom` **覆盖** `selected`（selected 留空）—— 两者互斥。
- **多选**：`custom` **补充** `selected` —— 两者可以同时带。
- **跳过 = `{ id, selected: [] }`**：是一个空答案，不是「没答」。
- 一批答案必须**每题一条**，少一条宿主以 `BAD_ANSWER` 拒绝。

### 1.3 等待与超时（`ctx.userQuestions`）

- `ask()` 前台阻塞等回答；`askTimed()` 让 agent 先继续，问题留在会话里。
- UI 用 **`attachWait`** 接手等待：拿到 Host 算好的剩余时长，本地倒计时；聚焦暂停、有「慢慢回答」。
- **没有接手方时（含最后一个 client 断开之后），服务在原 deadline 放行模型。**
  所以它不会真的永久挂死 —— 只是默认 deadline 很长。
- 回答后把回复作为 `user-question-reply` 消息 **steer** 给 agent。

### 1.4 答案从哪来：waterfall

答案的入口是 Cordis waterfall：

```
'user-questions/request'(this: Scoped<Agent>, request, next) => Promise<AskUserQuestionAnswer>
```

**一个 answerer 就是一个注册在这个事件上的监听器。** 官方 Web UI
（`dsh-client-ui-user-questions`）在浏览器侧就是这么干的，经 `dsh-api-remotes` 转发过去。

### 1.5 持久化靠 projection

问题**不存单独状态**：`userQuestions` projection 从已记录的工具调用派生。
所以**重连的客户端会再收到它**，Host 重启后仍在。

## 2. 我们的接法

### 2.1 为什么不走 Web 那条路

我们的 Kotlin 宿主走的是 **SDK stdio JSON-RPC**（方法表只有 `agent/*` 与 `session/*`），
**不连** Web 的 typert 通道。所以答案不能照抄 Web 的 `ctx.remote.userQuestions.answer(...)`。

### 2.2 自己当 answerer

`tools/dsh-phone/plugins/user-questions/` 在 `user-questions/request` 上注册监听器，
把请求转给 App 的 UI，等答案回来再返回。

**必须 `{ prepend: true }`** —— 这是这个插件唯一的注册约束，原因是一个真机踩到的坑：

`dsh-api-remotes` 也在同一个 waterfall 上注册，它把请求推进 **remote 队列**等浏览器回答。
我们的 App 不连那条通道，于是**没人消费、请求永远不落地**（实测：会话一直 `running`，
几分钟没有后续事件）。waterfall 按注册顺序跑、第一个不调 `next()` 的赢，所以要排到它前面。

### 2.3 三层结构

| 件 | 干什么 |
|---|---|
| `tools/dsh-phone/plugins/user-questions/` | answerer：透传形状、等答案；**接不了就 `next()` 让路** |
| `bridge/UserQuestionBridgeServer.kt` | 本地桥之一（设备桥 / 连接器桥 / 提问桥 / 审批桥，同一模式）：问题进 UI、答案回插件。**这条请求是阻塞的** |
| `ui/chat/UserQuestionSheet.kt` | 底部 sheet：一屏一题、选项 / 自由文本 / 跳过 |

状态放在 `agent/UserQuestionHub.kt` 这个**单例**里，不放 ViewModel：桥那边挂起的协程属于进程，
而 UI 会随导航重建 —— 放进 ViewModel 会让「切走再切回来」变成「答案丢了、插件还在等」。

### 2.4 绝不编答案

插件的返回值会被宿主当成**用户说的话**。所以桥没配、用户关掉面板、桥报错、超时 ——
四种情况一律 `next()` 让路。**空答案只能来自用户点了「跳过」。** 这条有单测逐条钉住
（`test/answerer.mjs`）。

## 3. UI 语义（照抄 dsh）

- 一屏一题 + 顶部页码（dsh 的 Web 版也是翻页器）。
- 单选**点了就走**；多选要按「继续」。
- 自由文本随时可写；单选时与选项互斥、多选时叠加。
- 「跳过」= 空答案。
- **关闭不是回答**：下滑关掉 → 让路。

## 4. 没做的部分

| 项 | 为什么 |
|---|---|
| **倒计时 / 慢慢回答** | dsh 的倒计时属于 **timed 等待**（`attachWait`），而我们接的是普通 `ask()`（前台阻塞），没有窗口可倒。要做得先把 timed 那条链路也接上。 |
| **已回答问题的只读回看卡** | dsh 从 projection 渲染只读卡。我们目前只有工具调用卡 —— 用户答完看不到自己答了什么。 |
| **`plan-review` intent** | dsh 用 `intent` 标记「这是在审阅计划」，`dsh-plan-mode` 会设置它。我们的 UI 走通用选项列表（dsh 说这样也是对的：不认识的 UI 渲染通用流程即可）。 |

## 5. 验证到什么程度

| 项 | 状态 |
|---|---|
| 插件单测（形状透传 / 四种「接不了」全让路） | ✅ |
| `check-profile`（真解析器） | ✅ |
| 装配（插件进 zip） | ✅ |
| profile 三处名字一致 + tool-ask-user 已启用 | ✅ JVM 单测 |
| spotless / detekt / lintDebug / 单测 | ✅ |
| **端到端（模型问 → 面板 → 用户答 → 模型收到）** | ✅ **已验证**（模拟器）：`ask answered questions=1 answers=1` → `task done ok=true tools=1` → 模型回「你选了**吃面**🍜」 |

**端到端验过的步骤**：装包 → 提交「用 ask_user_question 问我晚饭吃面还是吃饭」→ 底部 sheet 弹出、
选项与自由文本都在 → 点「吃面」→ 模型下一句确认了「吃面」。
日志：`adb logcat -s FactotumQuestion`（桥只记题数与答案数，**不记问题正文与答案正文** ——
与设备桥「只记动作与结构化结果」同一条规矩）。

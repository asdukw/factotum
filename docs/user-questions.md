# 用户提问（`ask_user_question`）

模型通过原生底部面板向用户提问并等待回答。工具是阻塞的：没有 UI 接手时整轮会挂住，
所以它必须「工具 + UI」成对存在，不能只开一半。改插件、桥或面板之前先读本页。

## 1. dsh 侧的形状

```
ask_user_question(questions[])
  每题：id / question / detail? / header? / options?[] / multiSelect? / intent?
  选项：{ label, description? }
```

答案形状：

```
{ answers: [ { id, selected: string[], custom?: string } ] }
```

- 单选：`custom` 覆盖 `selected`；多选：`custom` 补充 `selected`。
- 跳过 = `{ id, selected: [] }`，是空答案，不是「没答」。
- 一批答案必须每题一条，少一条宿主以 `BAD_ANSWER` 拒绝。

答案入口是 `user-questions/request` waterfall。官方 Web UI 经 `dsh-api-remotes` 在浏览器侧
注册 answerer；我们的 App 不连那条通道。

## 2. zoneu 的接法

| 件 | 职责 |
|---|---|
| `tools/dsh-phone/plugins/user-questions/` | answerer：透传形状、等答案；没配桥/已取消才 `next()` |
| `bridge/UserQuestionBridgeServer.kt` | 本地桥，阻塞等 UI 回答 |
| `ui/chat/UserQuestionSheet.kt` | 底部 sheet：一屏一题、选项 / 自由文本 / 跳过 |
| `agent/UserQuestionHub.kt` | 进程级单例状态 |

- **answerer 必须 `{ prepend: true }`**：`dsh-api-remotes` 也注册在同一 waterfall，它把请求
  推进没人消费的 remote 队列。排在它后面，请求永远不落地。
- **状态放单例，不放 ViewModel**：桥那边挂起的协程属于进程，UI 会随导航重建；
  放进 ViewModel 会导致「切走再切回来，答案丢了、插件还在等」。
- **绝不编答案**：空答案只能来自用户点了「跳过」；`test/answerer.mjs` 逐条钉住。
- **桥在但没答时不能 `next()`**：下一个 answerer（`dsh-api-remotes`）把请求推进没有消费者的
  remote 队列，`next()` 会让 `ask()` 永远不返回、turn 一直开着，后续消息全部排队。用户关掉
  面板 / 等待超时 / 桥报错时，插件抛错让工具以明确失败结束本轮；只有桥没配或请求已被取消
  才让路。

## 3. UI 语义

- 一屏一题，顶部页码。
- 单选点了就走；多选按「继续」。
- 自由文本随时可写；单选时与选项互斥，多选时叠加。
- 「跳过」= 空答案；下滑关闭 = 本轮以明确错误结束，不是回答（也不能让给下一个 answerer）。
- 推荐项放第一个，label 末尾加 `(Recommended)`。

## 4. 未实现

| 项 | 说明 |
|---|---|
| 倒计时 / 慢慢回答 | 属于 dsh 的 timed 等待（`attachWait`）；当前接的是普通 `ask()`，没有窗口可倒 |
| 已回答问题的只读回看 | 当前只有工具调用卡，用户答完看不到自己答了什么 |
| `plan-review` intent | 走通用选项列表；不认识的 intent 按通用流程渲染即可 |

## 5. 验证

```powershell
node tools/dsh-phone/plugins/user-questions/test/answerer.mjs
.\tools\bundle-dsh.ps1
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

端到端：提交「用 ask_user_question 问我晚饭吃面还是吃饭」→ 面板弹出 → 点「吃面」→
模型确认。判据是 `task done ok=true tools=1`，且模型复述了所选答案。
`adb logcat -s ZoneuQuestion` 只记题数与答案数，不记问题正文与答案正文。

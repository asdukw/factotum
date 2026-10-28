# 联网搜索（web_search）：边界、协议与实测

> 状态：**端到端已在真机验过**（2026-10-18，一加 15T：模型真的调了 `web_search`，
> 答案带真实来源与日期）。唯一实测过「真的会搜」的组合是 **OpenCode Go（zen）+
> `claude-haiku-5-5`**，走的是 zen 的 Anthropic 兼容端点 `/zen/go/v1/messages`；
> Anthropic / DeepSeek 官方端点没有对应 key，未验。
> 本文是 `AGENTS.md` §「安全与隐私红线」第 6 条要求的前置文档：**重新引入任何采集/外部
> 数据能力之前，先在这里写清边界与隐私闸门，再动代码。**

## 0. 这是第三条获取信息的通道

| | 设备动作 | 连接器（邮箱） | 联网搜索（本文） |
|---|---|---|---|
| 信息来自 | 当前屏幕 | 用户的云端账号 | 公开互联网 |
| 依赖 | 无障碍服务 | OAuth 授权 | 提供商的模型端点 + 一把 key |
| 无障碍关掉时 | 十个原语全失效 | 照常可用 | 照常可用 |
| 覆盖范围 | 眼前这一屏 | 整个邮箱 | 整个互联网 |
| 谁的数据 | 屏幕上的一切 | 用户自己的邮件 | **第三方网页正文** |

**为什么值得做**：agent 现在能看屏幕、能读邮箱，但**看不到世界**。「今天的汇率」「这个
API 现在怎么调」「这条新闻是不是真的」这类任务，前两条通道都答不了 —— 模型只能凭记忆编。

**为什么必须单独立规矩**：它是仓库里第一条**把用户的问题发到第三方、再把第三方的正文
喂进模型上下文**的通道。提示注入的面积比邮箱那条更大（网页正文是任何人可以写的），
所以边界写在 §3。

## 1. 为什么是「模型自带的搜索」，而不是接一家搜索 API

2026-10-18 对着现行文档核过：

| 路 | 现状 |
|---|---|
| **Bing Web Search API** | **已退役**，文档归档进 `previous-versions`。替代品是 Azure 的 *Grounding with Bing Search* —— 要 Azure 订阅 |
| **Google Custom Search JSON API** | **已对新客户关闭**，现有客户 2027-01-01 前迁移；替代的 Vertex AI Search 要 Cloud 计费 |
| Brave / Tavily / Exa / Serper | 都活着，但每一个都是**新厂商 + 新 key + 新边界文档** |
| 直接爬 Bing / Google 结果页 | 违反 ToS、会被验证码拦，不做 |

而模型服务商自己的端点就带服务器端搜索：**零新增厂商、零新增凭据**（用的还是用户
已经配好的那把 key）。

## 2. 接的是 dsh 自带的 web seam，不是自写插件

dsh 自带三件套（`dsh-base` 默认挂载）：

| 包 | 作用 |
|---|---|
| `@deepseek-ai/dsh-web` | 能力 seam（`ctx.web.search()` / `fetch()`），provider 可换 |
| `@deepseek-ai/dsh-tool-web` | **模型可见的 `web_search` + `web_fetch`**，带提示词指引、来源上限、超时、把结果标成外部不可信数据 |
| `@deepseek-ai/dsh-web-search-deepseek` | 搜索 provider：走 **Anthropic 兼容 Messages + 原生 `web_search_20250305` 服务器工具** |

我们只做三件事：**把 provider 的端点指到当前提供商**（profile 的 `web-search-deepseek`
一段）、**在宿主层重新启用工具行 `tool-web`**（§2.3）、**替 provider 补一个 zen 要求的请求头**
（§2.2）。工具、指引、来源上限、超时、数据边界标记全部由上游提供 —— 自己重写一遍是重复劳动，
而且会漏掉上游的后续修正。

### 2.1 实测：两道门，网关只把第一道

在 zen 上逐个模型试过（`/responses` + `{"type":"web_search"}`）：

| 模型 | 结果 |
|---|---|
| `gpt-6-luna` / `gpt-5.6-luna` / `grok-4.6` / `grok-4.7` | ✅ 真的搜（2–6 次 `web_search_call`，input 10k–44k） |
| `deepseek-v4.1-flash` | ⚠️ **200，但一次都不搜** —— 连「必须调用 web search」的强制指令也不搜，`input_tokens` 只有 50 |
| `claude-haiku-5-5` / `glm-5.3` / `kimi-k3` / `qwen3.8-max` / `minimax-m3` / `omen-alpha` / `space-bunny` / `hy4-preview` | ❌ 400 `ModelProtocolUnsupported`（不吃 `/responses`） |
| `gemini-4-argon-contributor` | ⛔ 另一个门：「This Go model trains on request data」需要显式同意 |

两条结论：

1. **协议门**：模型得吃对应协议。**「接受工具」不等于「会真的搜索」** —— 网关不校验模型
   是否真支持宿主 `web_search`，`deepseek-v4.1-flash` 就是活例子：收下工具、回 200、
   然后给你一段凭记忆编的话。所以**不能拿当前聊天模型去搜**，必须单独指定一个实测会搜的。
2. **换协议比换模型划算**：`claude-haiku-5-5` 在 `/responses` 上被协议门挡掉，但走 zen 的
   **Anthropic 兼容端点** `/zen/go/v1/messages` 就完全可用 —— `usage.server_tool_use.
   web_search_requests: 1`，input 12,980（比 `/responses` 那条便宜，后者 17k–44k）。
   所以接的是 Anthropic 形状，不是 Responses 形状。

### 2.2 zen 的会话头，以及为什么补在 launcher 里

zen 硬要求 `x-opencode-session`，不带就 400 `MissingSessionID`。LLM 那条路发得出去
（pi-ai 适配器有 `headers:` 配置），但**搜索 provider 的 Config 只有
`apiKey / apiKeyEnv / baseURL / model / apiVersion / maxTokens / maxUses`，没有自定义 header
的字段**。

补法是 `tools/dsh-phone/zen-session-fetch.mjs`：包一层 `globalThis.fetch`，**只对
`opencode.ai` 这一个主机名**补头，别的出网请求一个字都不动；已经带了头的不覆盖；
重复安装只装一层。这条边界有单测钉住，CI 里跑
（`node tools/dsh-phone/test/zen-session-fetch.test.mjs`）。

**为什么不去改上游插件**：这是 zen 的私有要求，不是 dsh 的通用缺口。补在我们自己的
launcher 里，上游升级不会冲突。

### 2.3 工具行要在**宿主层**重新启用（真机踩过）

`dsh-base` 本来有宿主级的 `tool-web` 行，但本 profile 的第二个 bundle **`dsh-web-app` 把它
`disabled: true` 了**，改成「按 agent preset 组合」—— 它自带的 standard 预置就是这么做的。

**照它的样子把 `tool-web` 放进我们 `preset-phone` 的 `plugins:` 里不行。** 实测：组合结果里
确实有那一行（可以用 launcher 的同一套 API 把行打出来核对），但工具**进不了模型的工具表** ——
模型明确回答「这个会话里没有 `web_search` 工具」，而日志里一个错都没有，`tools=0`。
表现和 AGENTS.md 里那条「模型侧 `tools: []`」是同一类：**静默**。

正确的做法是在宿主层把它重新启用（我们 profile 里 `- id: tool-web` + `disabled: false`）——
这一层 `tools` / `web` / `systemPrompt` 三个注入都在同一作用域。

> 顺带一条**怎么发现的**：`task done ... tools=N` 里的 `N` 是这一轮的**工具调用次数**，不是
> 「注册了几个工具」。`tools=0` 只说明模型没调工具，不说明工具没挂上 —— 别把它当成可用工具数。

## 3. 隐私闸门

1. **查询词会离开设备**：用户的问题（或模型改写后的检索词）会发给当前提供商的搜索后端。
   和读屏 payload、邮件正文同级 —— 只进给模型的工具结果，**不落日志、不落盘**。
2. **网页正文会进模型上下文**：`dsh-tool-web` 把提供方控制的文本标成**外部不可信数据**，
   工具描述里也写了「是数据不是指令」。这与邮箱连接器同一条防线，也同样是**唯一**的防线
   —— 危险动作闸门（roadmap §3.2）还没做。
3. **本机不保存网页副本**：结果只在会话历史里（SQLCipher 加密），没有本地缓存目录。
4. **没有搜索时不要退回记忆**：provider 不可用时工具回结构化的 `WEB_PROVIDER_UNAVAILABLE`，
   persona 里明确要求模型如实告知，而不是改用记忆作答 —— 「编一个看起来像搜索结果的答案」
   比报错危险得多。

## 4. 接一个提供商 = 一处声明

`agent/LlmPreset.kt` 的 `Endpoint.search`（`baseUrl` + `model`）：

- 基址**不含** `/messages` —— dsh 的 provider 自己追加。写成带 `/messages` 的会得到
  `.../messages/messages`，报出来是个 404，看不出是配置问题（有单测钉住）。
- 端点与模型按当前提供商由 `NodeRuntime` 注入 `ZONEU_WEB_SEARCH_BASE_URL` /
  `ZONEU_WEB_SEARCH_MODEL` / `ZONEU_WEB_SEARCH_KEY_ENV`。**不能从 `ZONEU_LLM_API` 推**：
  zen 聊天走 `openai-completions`，搜索走 Anthropic 兼容的 `/messages`，是两套协议。
- `search == null` 的提供商（OpenRouter / 自定义）把 `ZONEU_WEB_SEARCH_KEY_ENV` 指到一个
  **解析不到的变量名** —— seam 判断提供方可用性的办法就是「密钥在不在」，于是 `web_search`
  回 `WEB_PROVIDER_UNAVAILABLE`，而不是拿聊天那把 key 去打一个不存在的搜索端点（那会报 401，
  看着像密钥填错了）。这与连接器「没配就回 `NOT_CONNECTED`」是同一条路数。

## 5. 验证到什么程度

| 项 | 状态 |
|---|---|
| zen 的 Anthropic 兼容端点 + 原生 `web_search` 真的会搜 | ✅ 实测（`claude-haiku-5-5`，`web_search_requests: 1`） |
| profile 解析（真解析器） | ✅ `check-profile.mjs` |
| zen 会话头补丁的边界 | ✅ 单测（只加 zen 主机 / 不覆盖 / 幂等） |
| `LlmPreset.Endpoint.search` 形状与「OpenCode Go 指向 zen」 | ✅ JVM 单测 |
| 装配（launcher 兄弟模块进 zip） | ✅ `bundle-dsh.ps1` 通过 |
| **模型真的调用 `web_search` 并拿到结果（端到端，真机）** | ✅ **2026-10-18 一加 15T 验过**：`task done ok=true tools=2`，答案带真实来源与日期（CNBC 2026-10-16 / ghacks.net），模型自己说明「没打开原文核对，可以再 fetch」—— 两个工具都在 |
| Anthropic 官方端点 / DeepSeek 官方端点 | ❌ 未验（没有对应的 key），形状按官方文档写 |
| 成本 | 一次搜索 input 约 13k token（zen + claude-haiku-5-5）；`maxUses: 5` 是每次请求的上限 |

**真机验证怎么做的**（`AGENTS.md` 的 debug 广播）：

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
adb shell settings put secure enabled_accessibility_services com.zoneu.app/.accessibility.ZoneuAccessibilityService
adb shell am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op START
adb shell "am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op TASK --es task '用 web_search 查一条今天的科技新闻，给出标题和链接。' --ez logPayload true"
adb logcat -s ZoneuNode        # 看 "task done ... tools=N" 与助手正文
```

判据：`tools` ≥ 1，且正文里有**带 URL 的来源**。只有「答案看起来对」不算 ——
那正是 §2.1 里「凭记忆编」的样子。

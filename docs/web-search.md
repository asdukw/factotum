# 联网搜索（web_search）：边界、协议与实测

> 状态：**已在 PC 侧接好（profile 校验 + 单测 + 装配通过），真机未验**（2026-10-18）。
> 唯一实测过「真的会搜」的组合是 **OpenCode Go（zen）+ `claude-haiku-5-5`**，
> 走的是 zen 的 Anthropic 兼容端点 `/zen/go/v1/messages`。
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

我们只做两件事：**把 provider 的端点指到当前提供商**（profile 的 `web-search-deepseek`
一段），**替它补一个 zen 要求的请求头**（§2.2）。工具、指引、来源上限、超时、数据边界
标记全部由上游提供 —— 自己重写一遍是重复劳动，而且会漏掉上游的后续修正。

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
- 端点与模型按当前提供商由 `NodeRuntime` 注入 `TAPPET_WEB_SEARCH_BASE_URL` /
  `TAPPET_WEB_SEARCH_MODEL` / `TAPPET_WEB_SEARCH_KEY_ENV`。**不能从 `TAPPET_LLM_API` 推**：
  zen 聊天走 `openai-completions`，搜索走 Anthropic 兼容的 `/messages`，是两套协议。
- `search == null` 的提供商（OpenRouter / 自定义）把 `TAPPET_WEB_SEARCH_KEY_ENV` 指到一个
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
| **模型真的调用 `web_search` 并拿到结果（端到端）** | ❌ **未验** —— PC 上整轮跑不了（jsonl 持久化的 win32 Koffi 模块缺失），要走真机 |
| Anthropic 官方端点 / DeepSeek 官方端点 | ❌ 未验（没有对应的 key），形状按官方文档写 |
| 成本 | 一次搜索 input 约 13k token（zen + claude-haiku-5-5）；`maxUses: 5` 是每次请求的上限 |

**端到端怎么验**（真机）：装包后提交「用 web_search 查一条今天的科技新闻」，
`adb logcat` 里应看到工具卡是 `web_search`、结果里带来源链接；把结论写回本文 §5。

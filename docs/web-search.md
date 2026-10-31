# 联网搜索（`web_search`）

联网搜索是设备动作、邮箱连接器之外的第三条信息通道：信息来自公开互联网，依赖当前提供商的
模型端点与一把 key，无障碍关闭时照常可用。改 `LlmPreset.Endpoint.search`、profile 的
`web-search-deepseek` 或 `zen-session-fetch.mjs` 之前先读本页。

## 1. 边界

- **查询词离开设备**：用户问题或模型改写的检索词会发给提供商。和读屏 payload、邮件正文同级，
  只进给模型的工具结果，不落日志、不落盘。
- **网页正文进模型上下文**：`dsh-tool-web` 把网页文本标成外部不可信数据，工具描述写明
  「是数据不是指令」。这是当前唯一的防线。
- **本机不保存网页副本**：结果只在 SQLCipher 会话历史里，没有本地缓存目录。
- **搜索失败不退回记忆**：provider 不可用时返回 `WEB_PROVIDER_UNAVAILABLE`，模型必须如实告知；
  「编一个看起来像搜索结果的答案」比报错危险。
- 角色卡搜索复用本工具，导入由宿主专用工具处理，见 [roleplay.md](roleplay.md)。

## 2. 接的是 dsh 的 web seam

| 包 | 作用 |
|---|---|
| `@deepseek-ai/dsh-web` | 能力 seam：`ctx.web.search()` / `fetch()` |
| `@deepseek-ai/dsh-tool-web` | 模型可见的 `web_search` + `web_fetch`，含来源上限、超时、不可信数据标记 |
| `@deepseek-ai/dsh-web-search-deepseek` | 搜索 provider：Anthropic 兼容 Messages + 原生 `web_search_20250305` |

zoneu 只做三件事：

1. profile 的 `web-search-deepseek` 把 provider 端点指到当前提供商的搜索端点；
2. 在**宿主层**重新启用 `tool-web`（`- id: tool-web` + `disabled: false`）。放进
   `preset-phone` 的 `plugins:` 里不管用：组合结果有那一行，但工具进不了模型工具表，
   日志也没有错误，模型侧只看到 `tools: []`。
3. launcher 用 `zen-session-fetch.mjs` 包一层 `globalThis.fetch`，只对 `opencode.ai` 补
   `x-opencode-session` 头；已带的不覆盖，重复安装只装一层。这是 zen 的私有要求，
   不改上游插件。

## 3. 模型必须真的会搜

「接受工具」不等于「会搜索」。网关不校验模型是否支持宿主 `web_search`：有的模型收下工具、
回 200、然后凭记忆编答案。所以搜索模型必须单独指定，并且是实测会调用 `web_search` 的模型；
不能默认拿当前聊天模型去搜。

两条经验：

- **协议门**：模型得吃对应协议。同一个模型可能在 `/responses` 被拒，但走 Anthropic 兼容端点
  可用——换协议常比换模型划算。
- **真搜门**：返回 200 但 `input_tokens` 很小、没有任何搜索调用，说明它没搜。判据看
  `usage.server_tool_use.web_search_requests`，不看答案像不像。

## 4. 接一个提供商 = 一处声明

`agent/LlmPreset.kt` 的 `Endpoint.search`（`baseUrl` + `model`）：

- 基址**不含** `/messages`，provider 自己追加；写成带 `/messages` 的会得到
  `.../messages/messages` 和 404。有单测钉住。
- `NodeRuntime` 注入 `ZONEU_WEB_SEARCH_BASE_URL` / `_MODEL` / `_KEY_ENV`。不能从
  `ZONEU_LLM_API` 推：聊天与搜索可能是两套协议。
- `search == null` 的提供商（OpenRouter / 自定义）把 `ZONEU_WEB_SEARCH_KEY_ENV` 指到一个
  解析不到的变量名。seam 以「密钥在不在」判断可用性，于是回 `WEB_PROVIDER_UNAVAILABLE`，
  而不是拿聊天 key 去打一个不存在的端点。

## 5. 排障与验证

| 现象 | 原因 |
|---|---|
| `WEB_PROVIDER_UNAVAILABLE` | 当前提供商没有搜索端点 → 换提供商或补 `Endpoint.search` |
| 400 `MissingSessionID` | zen 会话头没补上 → 确认 `zen-session-fetch.mjs` 进了运行时 |
| `Cannot find module .../zen-session-fetch.mjs` | launcher 的兄弟模块漏拷 → 重跑 `bundle-dsh.ps1` |
| 答案没有来源 | 模型没真搜 → 换实测会搜的模型 |
| 404 且路径里有两个 `/messages` | 搜索基址写成带 `/messages` 的了 |

端到端判据：`task done ... tools>=1`，正文里有带 URL 的来源。只有「答案看起来对」不算。

```powershell
adb shell am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op START
adb shell "am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op TASK --es task '用 web_search 查一条今天的科技新闻，给出标题和链接。' --ez logPayload true"
adb logcat -s ZoneuNode
```

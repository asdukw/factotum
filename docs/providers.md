# 提供商、模型与运行时路由

**什么时候读**：改 `agent/LlmPreset.kt`、设置页的提供商 / 模型、`NodeRuntime` 的 env 注入，
或 `tools/dsh-phone/profile/cordis.patch.yml` 之前。

## 1. 加一个提供商 = 四处同一件事

| 位置 | 管什么 |
|---|---|
| `agent/LlmPreset.kt` | 端点 / 调用风格 / 默认（已验证）模型 / 推理格式 |
| `ui/settings/` | 列表 / 详情 / 模型选择；新增目录项会自动出现 |
| `AgentConfigStore` | 按 `provider.<id>.*` 存覆盖；每个提供商一把 `SecretVault` 密钥 |
| `node/NodeRuntime.kt` | 注入 `ZONEU_LLM_API` / `_BASE_URL` / `_MODEL` / `_MODELS` / `_THINKING_FORMAT` / `_ZEN`，以及 `PROVIDER` 常量 |
| `tools/dsh-phone/profile/cordis.patch.yml` | 单条 provider 路由，端点来自 env |

**验收**：`bundle-dsh.ps1`（内部跑 `check-profile.mjs`，会真的执行 `!!js` 表达式，含
`ZONEU_LLM_MODELS` 的动态清单）+ `./gradlew.bat :app:testDebugUnitTest`，然后重装 APK。

## 2. 硬规则（每条都踩过）

- **`NodeRuntime.PROVIDER` 必须与 profile 里的 provider 键逐字相同**：漏改一处时报的是
  `no adapter registered for provider "xxx"`，看着像运行时缺插件（`NodeRuntimeProfileTest` 钉住）。
- **端点、调用风格、模型必须成套**：OpenAI 是 `openai-responses`，Anthropic 是
  `anthropic-messages`，DeepSeek / OpenCode Go / OpenRouter / 自定义是 `openai-completions`。
  风格拿错不会在启动时报错，只会在第一次请求时以 404/400 的样子炸出来。
- **不维护模型清单**：`LlmPreset` 只留一个 `defaultModel`，用途是「已验证」
  组合判定与运行时兜底，**不是界面候选列表**。模型来自端点的 `GET /models`（详情 → 模型 →
  获取；结果落盘 `provider.<id>.remote_models`），拉不到的端点由用户手填 id。
- **模态由构建期索引决定**：`assets/model-modalities.json` 是 `bundle-dsh.ps1` 从 pi-ai 目录
  生成的（~60KB），运行时 `ZONEU_LLM_MODELS` 与运行时声明共用。顺序：当前 provider 条目 >
  跨 provider **结论一致**的同 id 条目 > text（欠声明只是提前拒图，过度声明会在请求中途被
  端点拒）。界面**不显示**可读图 / 仅文本标签（用户要求）。索引是生成物（已被忽略），
  没跑过 `bundle-dsh.ps1` 的开发构建会全部按 text 声明。
- **模型清单是动态的，且必须包含当前选中的模型**：路由键 `zoneu` 不是 pi-ai 目录里的
  provider，适配器不会从安装目录补模型 —— 未声明的 id 报 `UNKNOWN_MODEL`（**不是**退化成
  纯文本）。`ProviderConfig.llmModelsJson` 按选中模型生成 `ZONEU_LLM_MODELS`。profile 里
  只留一条 fallback，供 PC 侧校验与没有 env 的直接运行。
- **同一时间只启用一个提供商**：内嵌运行时每个进程只连一个端点。「启用」互斥，
  「全部停用」是显式状态（`ENABLED_NONE`），不会回落到默认提供商再把旧 key 用起来。
  启用 / 选中模型都要求三样齐全：端点、模型、密钥；新装的提供商是 0 个模型。
- **只有 OpenCode Go 的目录默认组合在真机上跑通过**；用户改过端点 / 模型 / 调用风格后，
  界面自动撤掉「已验证」。

## 3. 可用模型与切换语义

- **可用模型列表**（`provider.<id>.models`）：设置页从端点返回的清单勾选，或手填 id；卡片上的
  「N 个模型」= 可用模型数。
- 聊天页底部的模型条打开选择 sheet，选中后 `setModel + setEnabled + NodeRuntime.reload`，
  立即生效。
- **重载会换 dsh 会话命名空间**：界面历史保留，但模型上下文从下一条消息重新开始
  （dsh SDK 只有 create、无 resume）。
- 清密钥 / 停用：保存后会通过 `runtime.reload` 在当前 Node 进程内重新挂载并立即生效，不需要重启 App；重载会换 dsh 会话命名空间，界面聊天历史保留，但模型上下文从下一条消息重新开始。
- 设置页 / 端点预选的改动：保存后对正在运行的 runtime 应用；如果 runtime 尚未启动，下次正常启动读取最新配置。

## 4. 排障

| 现象 | 原因 / 处理 |
|---|---|
| `no adapter registered for provider "xxx"` | `NodeRuntime.PROVIDER` 与 profile 里的 provider 键不一致 → 两处一起改 |
| 换服务商后第一次请求 404/400 | 端点、调用风格、模型没成套（Anthropic 是 `anthropic-messages`，不是 OpenAI 兼容层） |
| 手填模型 id 报 `UNKNOWN_MODEL` | profile 没拿到 `ZONEU_LLM_MODELS`（退回了静态 fallback）→ 动态清单必须含选中模型；`ProviderConfig.llmModelsJson` 有单测 |
| 模型不读图 / 被声明成纯文本 | `assets/model-modalities.json` 缺失或没有该 id → 跑 `bundle-dsh.ps1`；索引里没有的 id 按 text 声明（安全方向），不要手改生成物 |
| 端点模型列表拉取报 HTTP 4xx | 密钥与端点不匹配 / 该端点不提供 `/models` → 手填模型 id；列表只是辅助，不影响手填 |
| dsh 报 `provider "zoneu" has an empty baseURL`，App 崩 | 启用的自定义提供商端点被清空 → `toAgentConfig` 会回落目录默认并清空 key；若仍出现，检查 `current()` 的 `canEnable` 分支 |
| 设置里清掉 key / 停用后，当前进程仍能发请求 | 保存后会通过 runtime.reload 重新读取 Keystore 并覆盖旧 key；如果运行时正在执行任务，等任务结束后再应用。 |
| 切换模型后模型看不到之前的对话 | 设计如此：reload 换 dsh 会话命名空间，界面历史保留但模型上下文重新开始（SDK 只有 create 无 resume） |
| 重载超时 / 重载后 initialize 失败 | 看 `NODEJS-MOBILE` 的 `[launcher] reload ...` 与 `runtime.ready`；超时 60s 后 `ZoneuNode` 会报重载失败 |
| 提供商列表显示「缺 Key」 | 该提供商没有密钥；启用 / 选中模型都要求先保存 key（否则会把正在用的那家停掉、App 变成未配置） |
| 装完包跑任务报缺 API key | 密钥由使用者自填：聊天页抽屉 → 设置（构建期不带密钥，设计如此） |
| 设置页保存了却没生效 | active provider 保存后会触发 runtime.reload；未启动 runtime 会在下一次启动时读取，inactive provider 会在启用/切换时应用 |
| 设置页显示「已配置」但请求 401 | 那把 key 与所选服务商不匹配 → 换预选或重填 key |

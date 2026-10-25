package com.factotum.app.agent

/**
 * LLM 服务商目录：端点、调用风格、推理格式、默认/已验证模型。
 *
 * **模型清单不在这里维护**（2026-10-17 用户决定）：设置页从端点 `GET /models` 拉取并落盘，
 * 拉不到的端点由用户手填 id。这里只留一个 [Endpoint.defaultModel]，用途仅两件：
 * 1. 判定「已验证」组合（目录端点 + 这个模型 + 目录调用风格）；
 * 2. 启用的提供商暂时没有模型时，给运行时一个**可挂载**的兜底（此时密钥会被清空，
 *    不会真的发请求）。
 *
 * 端点 / 调用风格 / 模型三件事仍然必须成套，缺一样就连不上：
 * - [Endpoint.api]：pi-ai 的调用风格。OpenAI 走 `openai-responses`，Anthropic 走
 *   `anthropic-messages`，DeepSeek / OpenCode Go / OpenRouter 与自定义端点走
 *   `openai-completions`。**不能一律按 OpenAI 兼容处理** —— Anthropic 的原生协议不是
 *   chat/completions，风格拿错只会在第一次请求时得到 404/400。
 * - [Endpoint.baseUrl]：与调用风格配套的端点。
 * - [Endpoint.thinkingFormat] 只在**对方没有原生推理协议**时才需要：zen / DeepSeek 的
 *   reasoning 字段靠它解析；OpenAI 的 responses、Anthropic 的 messages 与 OpenRouter 由
 *   目录里的 compat 自带推理块，留空。
 *
 * **这个枚举同时是存储键**：`provider.<id>.*` 与每个提供商各自的 Keystore 别名都带着 [id]。
 * 改 id = 设备上已存的配置与密钥全部读不回来。真要改，先写迁移。
 *
 * **验证状态**：只有 [OpenCodeGo] 的目录默认组合（端点 + [Endpoint.defaultModel]）在真机上
 * 跑通过；用户改过端点 / 模型 / 调用风格后，界面自动撤掉「已验证」。
 */
enum class LlmPreset(
  val id: String,
  val label: String,
  /** 提供商列表里的一句说明（第二行）。 */
  val description: String,
  /** 成套的端点定义。做成一个类型而不是散着的参数，是因为这几个值**必须一起对**。 */
  val endpoint: Endpoint,
  /** 「自定义」：不覆盖用户填的端点与模型。 */
  val custom: Boolean = false,
  /** 是否已在真机上跑通过。界面据此如实标注。 */
  val verified: Boolean = false,
) {
  OpenCodeGo(
    id = "opencode-go",
    label = "OpenCode Go",
    description = "一个 key 用 DeepSeek、GLM、Kimi、Qwen",
    endpoint =
      Endpoint(
        api = "openai-completions",
        baseUrl = "https://opencode.ai/zen/go/v1",
        defaultModel = "deepseek-v4.1-flash",
        thinkingFormat = "deepseek",
        zenSessionHeader = true,
        // 真机实测（2026-10-18）：/zen/go/v1/messages + web_search_20250305 能搜
        // （web_search_requests: 1，input ~13k）。同一个 claude-haiku-5-5 在 zen 的
        // /responses 上是 400 ModelProtocolUnsupported —— 所以走 Anthropic 形状更宽。
        search = SearchEndpoint(baseUrl = "https://opencode.ai/zen/go/v1", model = "claude-haiku-5-5"),
      ),
    verified = true,
  ),
  OpenAi(
    id = "openai",
    label = "OpenAI",
    description = "GPT 系列；可切 Responses API",
    endpoint =
      Endpoint(
        api = "openai-responses",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-6-sol",
        thinkingFormat = null,
        supportsResponseApi = true,
      ),
  ),
  Anthropic(
    id = "anthropic",
    label = "Anthropic",
    description = "Claude 系列（原生 messages 协议）",
    endpoint =
      Endpoint(
        api = "anthropic-messages",
        baseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-5",
        thinkingFormat = null,
        // 形状按官方文档写，**没有用真 key 跑过**（本仓库手上只有 zen 的 key）。
        search = SearchEndpoint(baseUrl = "https://api.anthropic.com/v1", model = "claude-haiku-5-5"),
      ),
  ),
  DeepSeek(
    id = "deepseek",
    label = "DeepSeek",
    description = "DeepSeek 官方 API",
    endpoint =
      Endpoint(
        api = "openai-completions",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        thinkingFormat = "deepseek",
        // dsh 的搜索 provider 自带这组默认值（它本来就是给 DeepSeek 写的），
        // 所以这里只是把它显式写出来 —— 仍然**没有用真 key 跑过**。
        search = SearchEndpoint(baseUrl = "https://api.deepseek.com/anthropic/v1", model = "deepseek-v4-flash"),
      ),
  ),
  OpenRouter(
    id = "openrouter",
    label = "OpenRouter",
    description = "一个 key 访问 OpenAI、Anthropic、Google 等模型",
    endpoint =
      Endpoint(
        api = "openai-completions",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-6-sol",
        thinkingFormat = null,
      ),
  ),
  Custom(
    id = "custom",
    label = "自定义",
    description = "任意 OpenAI 兼容端点；模型 id 由用户手填或从端点拉取",
    endpoint =
      Endpoint(
        // 任意 OpenAI 兼容端点：风格按最常见的猜，推理格式沿用本项目此前的行为
        // （profile 里原本就硬编码 deepseek）。对没有推理块的模型没有影响。
        api = "openai-completions",
        baseUrl = "",
        defaultModel = "",
        thinkingFormat = "deepseek",
        supportsResponseApi = true,
      ),
    custom = true,
  ),
  ;

  /**
   * 一个服务商的调用方式。字段之间的关系比字段本身重要：[api] 决定怎么发请求，[baseUrl] 必须是
   * 对应协议的端点，[defaultModel] 必须是被这个端点认的模型 —— 三者错配不会在启动时报错，
   * 只会在第一次请求时以 404/400 的形式炸出来。
   */
  data class Endpoint(
    /** pi-ai 的调用风格，写进 profile 的 `api`。 */
    val api: String,
    val baseUrl: String,
    /**
     * 只用于「已验证」组合判定与**运行时兜底**（提供商启用但模型为空时保证能挂载）；
     * 不再作为界面里的模型候选。模型列表来自端点 `/models` 或用户手填。
     */
    val defaultModel: String,
    /** 非空时写进 profile 的 `compat.thinkingFormat`。 */
    val thinkingFormat: String?,
    /** 只有 opencode zen 需要 `x-opencode-session` 这个头（zen 的硬要求）。 */
    val zenSessionHeader: Boolean = false,
    /** 该端点是否支持 Responses API 与 Chat Completions 两种调用风格（界面「Response API」开关）。 */
    val supportsResponseApi: Boolean = false,
    /**
     * 联网搜索（`web_search` 工具）走的 Anthropic 兼容端点；null = 该提供商没有**验证过**的。
     *
     * 与 [api] / [baseUrl] 是**两套独立的东西**：聊天用哪套协议，跟搜索用哪套协议没有关系。
     * zen 就是例子 —— 聊天走 `openai-completions`，而它的原生 `web_search` 在
     * Anthropic 兼容的 `/messages` 上（`/responses` 那条反而把 claude 系模型挡在协议门外）。
     * 所以这里必须单独声明，不能从 [api] 推。
     */
    val search: SearchEndpoint? = null,
  )

  /**
   * 联网搜索的端点与模型。基址**不含** `/messages` —— dsh 的搜索 provider 自己追加。
   *
   * 走的是「模型自带的服务器端搜索」而不是某家搜索 API：Bing Web Search API 已退役、
   * Google Custom Search JSON API 已对新客户关闭，而模型服务商自己的端点就带这个能力
   * —— 零新增厂商、零新增凭据（用的还是同一把 key）。见 docs/web-search.md。
   */
  data class SearchEndpoint(
    /** Anthropic 兼容基址，provider 会追加 `/messages`。 */
    val baseUrl: String,
    /** Anthropic 格式的模型名。**必须是实测会真的搜索的那个** —— 见 docs/web-search.md 的「真搜门」。 */
    val model: String,
  )

  val api: String
    get() = endpoint.api

  val baseUrl: String
    get() = endpoint.baseUrl

  val defaultModel: String
    get() = endpoint.defaultModel

  val thinkingFormat: String?
    get() = endpoint.thinkingFormat

  val zenSessionHeader: Boolean
    get() = endpoint.zenSessionHeader

  /** 是否显示「Response API」开关：端点同时支持两种协议时才显示。 */
  val responseApiToggle: Boolean
    get() = endpoint.supportsResponseApi

  companion object {
    /** 没存过预选时的默认：本项目一直在用的那条路径。 */
    val default: LlmPreset = OpenCodeGo

    fun byId(id: String?): LlmPreset? = entries.firstOrNull { it.id == id }

    /**
     * 按 base URL 反推预选。给两种情况用：老版本升上来（只存了 baseUrl，没有预选字段）、
     * 以及开发机在 local.properties 里写了个端点（想让调用风格自动跟上）。
     * 比不出来就返回 null，由调用方决定算不算「自定义」。
     */
    fun forBaseUrl(baseUrl: String?): LlmPreset? {
      val normalized = baseUrl?.trim()?.trimEnd('/') ?: return null
      if (normalized.isEmpty()) return null
      return entries.firstOrNull { !it.custom && it.baseUrl.trimEnd('/') == normalized }
    }
  }
}

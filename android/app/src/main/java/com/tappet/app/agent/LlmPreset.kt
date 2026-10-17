package com.tappet.app.agent

/**
 * LLM 服务商目录：端点、调用风格、模型清单。
 *
 * **这些值不是照文档抄的，是从运行时里 pi-ai 的模型目录读出来的**
 * （`android/.dsh-cache/runtime/node_modules/@earendil-works/pi-ai/dist/providers/data` 下各家一份
 * json，版本由 tools/bundle-dsh.ps1 的 pin 决定）。三件事必须成套，缺一样就连不上：
 *
 * - [Endpoint.api]：pi-ai 的调用风格。OpenAI 走 `openai-responses`，Anthropic 走
 *   `anthropic-messages`，DeepSeek / OpenCode Go / OpenRouter 与自定义端点走
 *   `openai-completions`。**不能一律按 OpenAI 兼容处理** —— Anthropic 的原生协议不是
 *   chat/completions，风格拿错只会在第一次请求时得到 404/400。
 * - [Endpoint.baseUrl]：与调用风格配套的端点。目录里每个模型都自带 baseUrl，这里取的就是它。
 * - [models]：都取自目录里**真实存在**的 id。dsh 认不出的 id 只会被当成未知模型
 *   （模态退化成纯文本，图读不了），所以宁可只给存在的。
 *
 * [Endpoint.thinkingFormat] 只在**对方没有原生推理协议**时才需要：zen / DeepSeek 的 reasoning
 * 字段靠它解析；OpenAI 的 responses、Anthropic 的 messages 与 OpenRouter 由目录里的 compat
 * 自带推理块，留空。
 *
 * **这个枚举同时是存储键**：`provider.<id>.*` 与每个提供商各自的 Keystore 别名都带着 [id]。
 * 改 id = 设备上已存的配置与密钥全部读不回来。真要改，先写迁移。
 *
 * **验证状态**：只有 [OpenCodeGo] 这条路径在真机上跑通过（本项目的默认端点）。其余几个的
 * 端点 / 风格 / 模型 id 都对得上目录，但**没有用真 key 发过一次请求** —— 界面里如实标着，
 * 别把「配置项填对了」当成「能跑」。
 */
enum class LlmPreset(
  val id: String,
  val label: String,
  /** 提供商列表里的一句说明（第二行）。 */
  val description: String,
  /** 成套的端点定义。做成一个类型而不是散着的参数，是因为这几个值**必须一起对**。 */
  val endpoint: Endpoint,
  /** 该提供商在界面里可选、且在运行时目录里声明过的模型。 */
  val models: List<Model>,
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
      ),
    models =
      listOf(
        Model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", supportsImage = true),
        Model("glm-5.3", "GLM-5.3", supportsImage = false),
        Model("kimi-k3", "Kimi K3", supportsImage = true),
        Model("qwen3.8-max", "Qwen3.8 Max", supportsImage = true),
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
    models =
      listOf(
        Model("gpt-6-sol", "GPT-6 Sol", supportsImage = true),
        Model("gpt-5.6-sol", "GPT-5.6 Sol", supportsImage = true),
        Model("gpt-5.5", "GPT-5.5", supportsImage = true),
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
      ),
    models =
      listOf(
        Model("claude-sonnet-5", "Claude Sonnet 5", supportsImage = true),
        Model("claude-opus-5-5", "Claude Opus 5.5", supportsImage = true),
        Model("claude-haiku-4-5", "Claude Haiku 4.5", supportsImage = true),
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
      ),
    models =
      listOf(
        Model("deepseek-flash", "DeepSeek V4.1 Flash", supportsImage = true),
        Model("deepseek-v4-pro", "DeepSeek V4 Pro", supportsImage = false),
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
    models =
      listOf(
        Model("openai/gpt-6-sol", "OpenAI · GPT-6 Sol", supportsImage = true),
        Model("anthropic/claude-sonnet-5", "Anthropic · Claude Sonnet 5", supportsImage = true),
        Model("google/gemini-3.5-flash", "Google · Gemini 3.5 Flash", supportsImage = true),
        Model("moonshotai/kimi-k3", "MoonshotAI · Kimi K3", supportsImage = true),
        Model("x-ai/grok-4.7", "xAI · Grok 4.7", supportsImage = true),
      ),
  ),
  Custom(
    id = "custom",
    label = "自定义",
    description = "任意 OpenAI 兼容端点；目录外的模型 id 只能跑文本",
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
    models = emptyList(),
    custom = true,
  ),
  ;

  /**
   * 一个服务商的调用方式。字段之间的关系比字段本身重要：[api] 决定怎么发请求，[baseUrl] 必须是
   * 对应协议的端点，[defaultModel] 必须是该端点认的模型 —— 三者错配不会在启动时报错，
   * 只会在第一次请求时以 404/400 的形式炸出来。
   */
  data class Endpoint(
    /** pi-ai 的调用风格，写进 profile 的 `api`。 */
    val api: String,
    val baseUrl: String,
    val defaultModel: String,
    /** 非空时写进 profile 的 `compat.thinkingFormat`。 */
    val thinkingFormat: String?,
    /** 只有 opencode zen 需要 `x-opencode-session` 这个头（zen 的硬要求）。 */
    val zenSessionHeader: Boolean = false,
    /** 该端点是否支持 Responses API 与 Chat Completions 两种调用风格（界面「Response API」开关）。 */
    val supportsResponseApi: Boolean = false,
  )

  /** 目录里真实存在的一个模型。`supportsImage` 决定视觉路径能不能把截图交给它。 */
  data class Model(
    val id: String,
    val label: String,
    val supportsImage: Boolean,
  )

  val api: String
    get() = endpoint.api

  val baseUrl: String
    get() = endpoint.baseUrl

  val defaultModel: String
    get() = endpoint.defaultModel

  /** 只给旧界面 / 测试用的模型 id 列表；新界面直接用 [models]。 */
  val modelHints: List<String>
    get() = models.map { it.id }

  val thinkingFormat: String?
    get() = endpoint.thinkingFormat

  val zenSessionHeader: Boolean
    get() = endpoint.zenSessionHeader

  /** 是否显示「Response API」开关：端点同时支持两种协议时才显示。 */
  val responseApiToggle: Boolean
    get() = endpoint.supportsResponseApi

  fun model(id: String): Model? = models.firstOrNull { it.id == id }

  companion object {
    /** 没存过预选时的默认：本项目一直在用的那条路径。 */
    val default: LlmPreset = OpenCodeGo

    fun byId(id: String?): LlmPreset? = entries.firstOrNull { it.id == id }

    /**
     * 全局模型目录：手填的 id 可能出现在任意一家提供商的清单里（运行时按 id 声明模态）。
     * 只看目录，**不**代表这个 id 在用户当前端点上可用 —— 端点是否认它是另一回事。
     */
    fun catalogModel(id: String): Model? =
      entries.asSequence().flatMap { it.models.asSequence() }.firstOrNull { it.id == id }

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

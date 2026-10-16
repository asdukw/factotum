package com.tappet.app.agent

/**
 * LLM 服务商的预选。
 *
 * **这些值不是照文档抄的，是从运行时里 pi-ai 的模型目录读出来的**
 * （`android/.dsh-cache/runtime/node_modules/@earendil-works/pi-ai/dist/providers/data` 下各家一份
 * json，版本由 tools/bundle-dsh.ps1 的 pin 决定）。三件事必须成套，缺一样就连不上：
 *
 * - [Endpoint.api]：pi-ai 的调用风格。OpenAI 走 `openai-responses`，Anthropic 走
 *   `anthropic-messages`，DeepSeek 与 opencode zen 走 `openai-completions`。**不能一律按
 *   OpenAI 兼容处理** —— Anthropic 的原生协议不是 chat/completions，风格拿错只会在第一次
 *   请求时得到 404/400。
 * - [Endpoint.baseUrl]：与调用风格配套的端点。目录里每个模型都自带 baseUrl，这里取的就是它。
 * - [Endpoint.defaultModel] 与 [Endpoint.modelHints]：都取自目录里**真实存在**的 id ——
 *   dsh 认不出的 id 只会被当成未知模型（模态退化成纯文本，图读不了），所以宁可只给存在的。
 *
 * [Endpoint.thinkingFormat] 只在**对方没有原生推理协议**时才需要：zen / DeepSeek 的 reasoning
 * 字段靠它解析；OpenAI 的 responses 与 Anthropic 的 messages 自带推理块，留空。
 *
 * **验证状态**：只有 [OpenCodeGo] 这条路径在真机上跑通过（本项目的默认端点）。其余几个的
 * 端点 / 风格 / 模型 id 都对得上目录，但**没有用真 key 发过一次请求** —— 界面里如实标着，
 * 别把「配置项填对了」当成「能跑」。
 */
enum class LlmPreset(
  val id: String,
  val label: String,
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
    endpoint =
      Endpoint(
        api = "openai-completions",
        baseUrl = "https://opencode.ai/zen/go/v1",
        defaultModel = "deepseek-v4.1-flash",
        modelHints = listOf("deepseek-v4.1-flash", "glm-5.3", "kimi-k3", "qwen3.8-max"),
        thinkingFormat = "deepseek",
        zenSessionHeader = true,
      ),
    verified = true,
  ),
  OpenAi(
    id = "openai",
    label = "OpenAI",
    endpoint =
      Endpoint(
        api = "openai-responses",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-6-sol",
        modelHints = listOf("gpt-6-sol", "gpt-5.6-sol", "gpt-5.5"),
        thinkingFormat = null,
      ),
  ),
  Anthropic(
    id = "anthropic",
    label = "Anthropic",
    endpoint =
      Endpoint(
        api = "anthropic-messages",
        baseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-5",
        modelHints = listOf("claude-sonnet-5", "claude-opus-5-5", "claude-haiku-4-5"),
        thinkingFormat = null,
      ),
  ),
  DeepSeek(
    id = "deepseek",
    label = "DeepSeek",
    endpoint =
      Endpoint(
        api = "openai-completions",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        modelHints = listOf("deepseek-flash", "deepseek-v4-pro"),
        thinkingFormat = "deepseek",
      ),
  ),
  Custom(
    id = "custom",
    label = "自定义",
    endpoint =
      Endpoint(
        // 任意 OpenAI 兼容端点：风格按最常见的猜，推理格式沿用本项目此前的行为
        // （profile 里原本就硬编码 deepseek）。对没有推理块的模型没有影响。
        api = "openai-completions",
        baseUrl = "",
        defaultModel = "",
        modelHints = emptyList(),
        thinkingFormat = "deepseek",
      ),
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
    /** 界面里给的候选模型 id，都是目录里存在的条目。 */
    val modelHints: List<String>,
    /** 非空时写进 profile 的 `compat.thinkingFormat`。 */
    val thinkingFormat: String?,
    /** 只有 opencode zen 需要 `x-opencode-session` 这个头（zen 的硬要求）。 */
    val zenSessionHeader: Boolean = false,
  )

  val api: String
    get() = endpoint.api

  val baseUrl: String
    get() = endpoint.baseUrl

  val defaultModel: String
    get() = endpoint.defaultModel

  val modelHints: List<String>
    get() = endpoint.modelHints

  val thinkingFormat: String?
    get() = endpoint.thinkingFormat

  val zenSessionHeader: Boolean
    get() = endpoint.zenSessionHeader

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

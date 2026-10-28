// opencode zen 的会话头补丁。
//
// **为什么需要这一层**：zen 硬要求 `x-opencode-session`，不带就 400 `MissingSessionID`。
// LLM 那条路发得出去 —— pi-ai 适配器有 `headers:` 配置（见 profile 的 llm-pi-ai）。
// 但 dsh 自带的搜索 provider（`@deepseek-ai/dsh-web-search-deepseek`，走 Anthropic 兼容
// Messages + 原生 `web_search_20250305` 服务器工具）**没有自定义 header 的配置项**，
// 而 zen 恰好有 Anthropic 兼容端点 —— 把它接上就等于零新增厂商拿到联网搜索。
//
// **只对 zen 的主机名生效**：别的出网请求一个字都不动。这是「zen 的私有要求」，
// 不是 dsh 的通用缺口，所以补在我们自己的 launcher 里，不去改上游插件。

/** 需要补会话头的主机。只有一个 —— zen 的私有要求。 */
const ZEN_HOSTS = new Set(['opencode.ai'])

function hostnameOf(input) {
  const url =
    typeof input === 'string' ? input : input instanceof URL ? input.href : (input?.url ?? undefined)
  if (typeof url !== 'string' || url === '') return undefined
  try {
    return new URL(url).hostname
  } catch {
    return undefined
  }
}

/**
 * 装一层 `globalThis.fetch`：对 zen 的请求补 `x-opencode-session`（已经有了就不覆盖）。
 *
 * 幂等：重复调用只装一次（launcher 在同一进程里可能 reload 多次）。
 *
 * @param {object} [options]
 * @param {string} [options.session] 会话 id；缺省读 `ZONEU_LLM_SESSION`，再缺省 `zoneu-mobile`
 * @param {typeof fetch} [options.target] 要包装的 fetch，默认 `globalThis.fetch`
 * @param {(fetchImpl: typeof fetch) => void} [options.install] 安装器，默认写回 `globalThis`
 */
export function installZenSessionFetch(options = {}) {
  const target = options.target ?? globalThis.fetch
  const install =
    options.install ??
    ((fetchImpl) => {
      globalThis.fetch = fetchImpl
    })
  if (target?.__zoneuZenSession === true) return target

  const session = options.session ?? process.env.ZONEU_LLM_SESSION ?? 'zoneu-mobile'

  const wrapped = (input, init) => {
    if (!ZEN_HOSTS.has(hostnameOf(input))) return target(input, init)
    const headers = new Headers(init?.headers ?? input?.headers)
    if (!headers.has('x-opencode-session')) headers.set('x-opencode-session', session)
    return target(input, { ...init, headers })
  }
  wrapped.__zoneuZenSession = true
  install(wrapped)
  return wrapped
}

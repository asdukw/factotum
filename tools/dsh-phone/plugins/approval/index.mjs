// dsh plugin: `approval/request` 的手机 answerer —— 把「这次操作要不要允许」交给 App 的 UI 问用户。
//
// **dsh 原生的审批路径**（研究结论，见 docs/approvals.md）：
// - `tools/pre-execute` 瀑布可以把一次调用判成 `ask`（`PreToolDecision`）；
// - `@deepseek-ai/dsh-user-approval` 的 `ctx.approval.request()` 把 `ask` 交给注册在
//   Cordis waterfall `approval/request` 上的 **answerer**；
// - 结果词汇是封闭的：`allowed-once` / `rejected` / `cancelled` / `unavailable`，
//   只有 `allowed-once` 是授权；没有 answerer 时服务自己就以 `unavailable` 关闭（fail closed）。
//
// 手机上没有浏览器，官方 Web UI 的 answerer（`dsh-client-ui-approval`，经 `dsh-api-remotes`
// 转发）用不了，所以这个插件自己当 answerer，把请求转给 Kotlin 侧的 ApprovalBridgeServer。
//
// **桥不可用时返回 `unavailable`，不 `next()`**。这与 user-questions 插件的「接不了就让路」
// 不同，理由是后果不对称：`dsh-api-remotes` 也会挂在这个 waterfall 上，而它等的是浏览器回答；
// 手机上没人消费时让路会把整轮任务挂住，而 `unavailable` 是 dsh 自己定义的 fail-closed 结果，
// 模型会立刻拿到「这次没被允许」。宁可拒绝，不要挂起。
//
// **不要在这个插件里打印 reason / displayReason**：它们可能带收件人、主题等用户内容。
// 日志只允许记工具名与结果码。
export const name = 'dsh-plugin-approval'
export const inject = []

/** 等用户决定的时长上限。超时按取消处理（`cancelled`），不是拒绝。 */
const BRIDGE_TIMEOUT_MS = 10 * 60 * 1000

const OUTCOMES = new Set(['allowed-once', 'rejected', 'cancelled', 'unavailable'])

function bridge() {
  const baseUrl = process.env.ZONEU_APPROVAL_BRIDGE_URL
  const token = process.env.ZONEU_APPROVAL_BRIDGE_TOKEN
  if (!baseUrl || !token) return undefined
  return { baseUrl, token }
}

/** 只传桥认识的字段；`agent` / `signal` 不是 JSON 可传的。 */
function payloadOf(request) {
  const displayReason = request?.displayReason
  const locales =
    displayReason !== null && typeof displayReason === 'object'
      ? Object.fromEntries(Object.entries(displayReason).filter(([, value]) => typeof value === 'string'))
      : undefined
  return {
    toolName: String(request?.toolName ?? ''),
    callId: typeof request?.callId === 'string' ? request.callId : null,
    reason: typeof request?.reason === 'string' ? request.reason : undefined,
    displayReason: locales !== undefined && Object.keys(locales).length > 0 ? locales : undefined,
  }
}

/**
 * 交给 UI 去问，等一个封闭结果。
 *
 * 任何「没拿到有效答案」的情况都返回 `unavailable`（fail closed）；
 * 只有明确的取消（调用方 signal 中止或本地超时）返回 `cancelled`。
 */
async function askUi(request) {
  const endpoint = bridge()
  if (endpoint === undefined) return 'unavailable'
  const payload = payloadOf(request)
  if (payload.toolName === '') return 'unavailable'

  const signal = request?.signal
  if (signal?.aborted) return 'cancelled'
  const timer = AbortSignal.timeout(BRIDGE_TIMEOUT_MS)
  const abort = signal === undefined ? timer : AbortSignal.any([signal, timer])

  try {
    const response = await fetch(`${endpoint.baseUrl}/action`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-approval-bridge-token': endpoint.token },
      body: JSON.stringify({ action: 'approve', ...payload }),
      signal: abort,
    })
    const body = await response.json().catch(() => ({}))
    if (body?.ok !== true) return 'unavailable'
    return OUTCOMES.has(body.outcome) ? body.outcome : 'unavailable'
  } catch {
    // 调用方取消 / 本地超时 → cancelled（问题被撤回）；其余（桥挂了、响应坏了）→ fail closed。
    return timer.aborted || signal?.aborted ? 'cancelled' : 'unavailable'
  }
}

export function apply(ctx) {
  ctx.on(
    'approval/request',
    async function (request) {
      return await askUi(request)
    },
    // 排在 `dsh-api-remotes` 前面：它会把请求推进 remote 队列等浏览器回答，
    // 而手机上没有人消费（与 dsh-plugin-user-questions 同一条理由）。
    { prepend: true },
  )
}

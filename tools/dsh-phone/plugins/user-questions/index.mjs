// dsh plugin: `ask_user_question` 的宿主侧 answerer —— 把问题交给 App 的 UI 去问用户。
//
// **dsh 原生的答案路径**（研究结论，见 docs/user-questions.md）：
// `ctx.userQuestions` 是一个 seam，答案来自注册在 Cordis waterfall
// `user-questions/request` 上的 **answerer**（`dsh-client-ui-user-questions` 在浏览器里就是
// 这么一个 answerer，经 `dsh-api-remotes` 转发过去）。协议形状：
//
//   请求 `AskUserQuestionRequestEvent` = { questions[], agent?, signal?, wait?: {callId, timed?} }
//   每题 `{ id, question, detail?, header?, options?: [{label, description?}], multiSelect?, intent? }`
//   回答 `AskUserQuestionAnswer`     = { answers: [{ id, selected: string[], custom? }] }
//
// 三条语义照抄 dsh（别自己发明）：
//   - 单选时 `custom` **覆盖** `selected`（selected 留空）；多选时 `custom` **补充** `selected`；
//   - **跳过 = `{ id, selected: [] }`** —— 是一个空答案，不是「没答」；
//   - 这批答案必须**每题一条**，少一条宿主会以 BAD_ANSWER 拒绝。
//
// **为什么要 `prepend: true`**：`dsh-api-remotes` 也在这个 waterfall 上注册，它把请求推进
// remote 队列等浏览器回答 —— 而我们的 App 不连那条通道，于是**没人消费、请求永远不落地**
// （实测：会话一直 running，几分钟没有后续事件）。waterfall 按注册顺序跑、第一个不调 `next()`
// 的赢，所以要排到它前面。
//
// **接不了就让路**：桥没配、或用户关掉面板，一律 `next()` 交给下一个 answerer，
// 绝不自己编一个答案 —— 编出来的答案会被当成用户说的，比超时危险得多。
const BRIDGE_TIMEOUT_MS = 30 * 60 * 1000

function bridge() {
  const baseUrl = process.env.FACTOTUM_QUESTION_BRIDGE_URL
  const token = process.env.FACTOTUM_QUESTION_BRIDGE_TOKEN
  if (!baseUrl || !token) return undefined
  return { baseUrl, token }
}

/** 把 dsh 的请求体裁成 UI 需要的那几个字段（`agent` / `signal` 不是 JSON 可传的）。 */
function payloadOf(request) {
  return {
    callId: request?.wait?.callId ?? null,
    timed: request?.wait?.timed === true,
    questions: (request?.questions ?? []).map((item) => ({
      id: String(item?.id ?? ''),
      question: String(item?.question ?? ''),
      detail: typeof item?.detail === 'string' ? item.detail : undefined,
      header: typeof item?.header === 'string' ? item.header : undefined,
      multiSelect: item?.multiSelect === true,
      options: Array.isArray(item?.options)
        ? item.options.map((option) => ({
            label: String(option?.label ?? ''),
            description: typeof option?.description === 'string' ? option.description : undefined,
          }))
        : undefined,
    })),
  }
}

/**
 * 交给 UI 去问，等回答。
 *
 * 返回 `undefined` 表示「这次没拿到答案」（桥不可用 / 用户关掉 / 超时 / 被取消）——
 * 调用方据此 `next()` 让路，而不是编一个空答案。**空答案只能来自用户点了「跳过」。**
 */
async function askUi(request) {
  const endpoint = bridge()
  if (endpoint === undefined) return undefined
  const payload = payloadOf(request)
  if (payload.questions.length === 0) return undefined

  const signal = request?.signal
  if (signal?.aborted) return undefined
  const timer = AbortSignal.timeout(BRIDGE_TIMEOUT_MS)
  const abort = signal === undefined ? timer : AbortSignal.any([signal, timer])

  try {
    const response = await fetch(`${endpoint.baseUrl}/action`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-question-bridge-token': endpoint.token },
      body: JSON.stringify({ action: 'ask', ...payload }),
      signal: abort,
    })
    const body = await response.json().catch(() => ({}))
    if (body?.ok !== true || !Array.isArray(body.answers)) return undefined
    return { answers: body.answers }
  } catch {
    // 取消（用户关了会话 / 工具调用被中止）与超时都走这里：让路，不编答案。
    return undefined
  }
}

export const name = 'dsh-plugin-user-questions'
export const inject = []

export function apply(ctx) {
  ctx.on(
    'user-questions/request',
    async function (request, next) {
      const answer = await askUi(request)
      if (answer === undefined) return next()
      return answer
    },
    // 必须排在 dsh-api-remotes 前面，理由见文件头。
    { prepend: true },
  )
}

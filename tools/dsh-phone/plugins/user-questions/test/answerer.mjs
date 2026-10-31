// answerer 的行为：**桥没配/请求取消就让路；桥在但用户没答就报错结束本轮**。
//
// 这是这个插件唯一危险的地方 —— waterfall 的返回值会被宿主当成「用户说的」。桥挂了却回一个
// 空答案，模型会以为用户跳过了所有问题，然后据此继续做事。所以这里逐条钉。
// 另外：桥在但用户没答时不能 `next()` —— 下一个 answerer 没有消费者，会把整轮挂死。
import assert from 'node:assert/strict'

process.env.ZONEU_QUESTION_BRIDGE_URL = 'http://127.0.0.1:1'
process.env.ZONEU_QUESTION_BRIDGE_TOKEN = 'bridge-token'

const plugin = await import('../index.mjs')

let listener
plugin.apply({ on: (event, fn) => { listener = fn } })

const calls = []
let reply = { status: 200, body: { ok: true, answers: [] } }
let fetchThrows = false

globalThis.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), headers: init.headers ?? {}, body: JSON.parse(init.body) })
  if (fetchThrows) throw new Error('boom')
  return { ok: reply.status < 400, status: reply.status, json: async () => reply.body }
}

const request = {
  questions: [
    {
      id: 'q1',
      question: '先做哪个？',
      header: '选择',
      detail: '细节在这里',
      multiSelect: true,
      options: [{ label: '买牛奶', description: '顺路' }, { label: '写周报' }],
    },
  ],
  wait: { callId: 'call-1', timed: true },
}

/** 调一次 listener；返回 {answer, delegated} —— delegated 表示它调了 next()。 */
async function run(req = request) {
  let delegated = false
  const answer = await listener(req, async () => {
    delegated = true
    return { answers: [{ id: 'from-next', selected: [] }] }
  })
  return { answer, delegated }
}

// --- 1) 正常：透传形状 + 返回 UI 给的答案 ---
calls.length = 0
reply = { status: 200, body: { ok: true, answers: [{ id: 'q1', selected: ['买牛奶'], custom: '顺便取快递' }] } }
{
  const { answer, delegated } = await run()
  assert.equal(delegated, false)
  assert.deepEqual(answer, { answers: [{ id: 'q1', selected: ['买牛奶'], custom: '顺便取快递' }] })
  assert.equal(calls.length, 1)
  assert.match(calls[0].url, /\/action$/)
  assert.equal(calls[0].headers['x-question-bridge-token'], 'bridge-token')
  assert.equal(calls[0].body.action, 'ask')
  assert.equal(calls[0].body.callId, 'call-1')
  assert.equal(calls[0].body.timed, true)
  assert.deepEqual(calls[0].body.questions, [
    {
      id: 'q1',
      question: '先做哪个？',
      header: '选择',
      detail: '细节在这里',
      multiSelect: true,
      options: [{ label: '买牛奶', description: '顺路' }, { label: '写周报' }],
    },
  ])
}

// --- 2) 用户关掉面板：明确报错，**不是**让路，更不是回空答案 ---
calls.length = 0
reply = { status: 200, body: { ok: false, code: 'DISMISSED' } }
{
  let delegated = false
  await assert.rejects(
    listener(request, async () => {
      delegated = true
      return { answers: [] }
    }),
    /dismissed/,
  )
  assert.equal(delegated, false, '桥在但用户没答时不能 next()')
}

// --- 3) 桥报错 / 超时：同样明确报错 ---
calls.length = 0
fetchThrows = true
{
  let delegated = false
  await assert.rejects(
    listener(request, async () => {
      delegated = true
      return { answers: [] }
    }),
    /bridge became unavailable/,
  )
  assert.equal(delegated, false)
}
fetchThrows = false

// --- 4) 桥没配：压根不发请求，直接让路 ---
const url = process.env.ZONEU_QUESTION_BRIDGE_URL
delete process.env.ZONEU_QUESTION_BRIDGE_URL
calls.length = 0
{
  const { delegated } = await run()
  assert.equal(delegated, true)
  assert.equal(calls.length, 0, '没配桥时不该发请求')
}
process.env.ZONEU_QUESTION_BRIDGE_URL = url

// --- 5) 空问题数组：让路（宿主那边本来就要求至少一题） ---
calls.length = 0
{
  const { delegated } = await run({ questions: [] })
  assert.equal(delegated, true)
  assert.equal(calls.length, 0)
}

// --- 6) 已经取消的请求：让路，不发请求 ---
calls.length = 0
{
  const aborted = new AbortController()
  aborted.abort()
  const { delegated } = await run({ ...request, signal: aborted.signal })
  assert.equal(delegated, true)
  assert.equal(calls.length, 0)
}

console.log('ok: 形状透传 + 桥没配/取消让路 + 用户没答报错（都不编答案）')

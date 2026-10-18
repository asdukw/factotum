// answerer 的行为：**接不了就让路，绝不编答案**。
//
// 这是这个插件唯一危险的地方 —— waterfall 的返回值会被宿主当成「用户说的」。桥挂了却回一个
// 空答案，模型会以为用户跳过了所有问题，然后据此继续做事。所以这里逐条钉：
// 桥没配 / 用户关掉 / 桥报错 / 超时，四种情况都必须 `next()`。
import assert from 'node:assert/strict'

process.env.TAPPET_QUESTION_BRIDGE_URL = 'http://127.0.0.1:1'
process.env.TAPPET_QUESTION_BRIDGE_TOKEN = 'bridge-token'

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

// --- 2) 桥报错（ok:false）：让路，**不是**回一个空答案 ---
calls.length = 0
reply = { status: 200, body: { ok: false, code: 'DISMISSED' } }
{
  const { answer, delegated } = await run()
  assert.equal(delegated, true, '桥没给答案时必须让路')
  assert.deepEqual(answer, { answers: [{ id: 'from-next', selected: [] }] }, '必须返回下一个 answerer 的结果')
}

// --- 3) fetch 抛（桥挂了 / 超时 / 取消）：让路 ---
calls.length = 0
fetchThrows = true
{
  const { delegated } = await run()
  assert.equal(delegated, true)
}
fetchThrows = false

// --- 4) 桥没配：压根不发请求，直接让路 ---
const url = process.env.TAPPET_QUESTION_BRIDGE_URL
delete process.env.TAPPET_QUESTION_BRIDGE_URL
calls.length = 0
{
  const { delegated } = await run()
  assert.equal(delegated, true)
  assert.equal(calls.length, 0, '没配桥时不该发请求')
}
process.env.TAPPET_QUESTION_BRIDGE_URL = url

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

console.log('ok: 形状透传 + 四种「接不了」全部让路（不编答案）')

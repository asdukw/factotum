// answerer 的行为测试：桥的映射、fail-closed、取消、以及「绝不自己编一个允许」。
import assert from 'node:assert/strict'

const plugin = await import('../index.mjs')

const listeners = []
plugin.apply({ on: (event, handler, options) => listeners.push({ event, handler, options }) })
const handler = listeners[0].handler

const calls = []
let reply = { ok: true, outcome: 'allowed-once' }
let throwError = false

globalThis.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), init })
  if (throwError) throw new Error('bridge down')
  return { ok: true, status: 200, json: async () => reply }
}

function setBridge(enabled) {
  if (enabled) {
    process.env.ZONEU_APPROVAL_BRIDGE_URL = 'http://127.0.0.1:1'
    process.env.ZONEU_APPROVAL_BRIDGE_TOKEN = 'approval-token'
  } else {
    delete process.env.ZONEU_APPROVAL_BRIDGE_URL
    delete process.env.ZONEU_APPROVAL_BRIDGE_TOKEN
  }
}

function request(extra = {}) {
  return {
    toolName: 'mail_send',
    callId: 'call-1',
    reason: '发送 1 封邮件',
    displayReason: { en: 'Send 1 email', zh: '发送 1 封邮件' },
    ...extra,
  }
}

let nextCalls = 0
const next = async () => {
  nextCalls += 1
  return 'next-was-called'
}

// --- 1. 桥没配置：fail closed，不 `next()`（让路会挂住整轮） -------------------
{
  setBridge(false)
  assert.equal(await handler(request(), next), 'unavailable')
  assert.equal(calls.length, 0)
  assert.equal(nextCalls, 0, '桥不可用时不能让路给等浏览器的 answerer')
}

// --- 2. allowed-once：请求体裁正确，且不带 agent / signal ---------------------
{
  setBridge(true)
  reply = { ok: true, outcome: 'allowed-once' }
  assert.equal(await handler(request(), next), 'allowed-once')
  const call = calls.at(-1)
  assert.equal(call.url, 'http://127.0.0.1:1/action')
  assert.equal(call.init.method, 'POST')
  assert.equal(call.init.headers['x-approval-bridge-token'], 'approval-token')
  const body = JSON.parse(call.init.body)
  assert.deepEqual(body, {
    action: 'approve',
    toolName: 'mail_send',
    callId: 'call-1',
    reason: '发送 1 封邮件',
    displayReason: { en: 'Send 1 email', zh: '发送 1 封邮件' },
  })
  assert.ok(!('agent' in body), 'agent 不是 JSON 可传的')
  assert.ok(!('signal' in body), 'signal 不是 JSON 可传的')
}

// --- 3. 其余封闭结果原样带出 --------------------------------------------------
{
  for (const outcome of ['rejected', 'cancelled']) {
    reply = { ok: true, outcome }
    assert.equal(await handler(request(), next), outcome)
  }
}

// --- 4. 非法响应一律 fail closed ---------------------------------------------
{
  for (const bad of [{ ok: false }, { ok: true, outcome: 'allow-always' }, { ok: true }]) {
    reply = bad
    assert.equal(await handler(request(), next), 'unavailable')
  }
  throwError = true
  assert.equal(await handler(request(), next), 'unavailable')
  throwError = false
}

// --- 5. 调用方取消：直接 cancelled，不发请求 ----------------------------------
{
  const before = calls.length
  const controller = new AbortController()
  controller.abort()
  assert.equal(await handler(request({ signal: controller.signal }), next), 'cancelled')
  assert.equal(calls.length, before, '已中止的请求不该再打到桥')
}

// --- 6. 工具名为空：fail closed ----------------------------------------------
{
  assert.equal(await handler(request({ toolName: '  ' }), next), 'unavailable')
}

// --- 7. 全程不让路 ------------------------------------------------------------
assert.equal(nextCalls, 0, 'answerer 必须自己给出封闭结果')

// 返回的只是结果字符串：令牌不会出现在任何工具结果里。
for (const outcome of ['allowed-once', 'rejected', 'cancelled', 'unavailable']) {
  assert.ok(!outcome.includes('approval-token'))
}

console.log(`ok: approval answerer 行为正确（${calls.length} 次桥调用，全部 fail closed）`)

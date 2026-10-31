import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import * as plugin from '../index.mjs'
const { apply, approvalFor, executeCard } = plugin

process.env.ANDROID_BRIDGE_URL = 'http://127.0.0.1:12345'
process.env.ANDROID_BRIDGE_TOKEN = 'test-only'
const receipt = JSON.stringify({ kind: 'zoneu.character-card.result.v1', ok: true, characterId: 'imported-test' })
const response = () => ({ ok: true, json: async () => ({ ok: true, payload: receipt }) })
const execution = (id = 'call-1') => ({ callId: id, signal: new AbortController().signal })

function register() {
  const hooks = [], tools = []
  apply({ on: (event, handler) => hooks.push({ event, handler }), tools: { register: (tool) => tools.push(tool) } })
  return { hook: hooks[0].handler, tools }
}

test('actual defineTool retains all five card schemas and no model request ID is accepted', () => {
  const { tools } = register()
  assert.deepEqual(tools.filter(t => t.name.includes('character_card')).map(t => t.name), ['create_character_card', 'import_character_card_url', 'list_character_cards', 'read_character_card', 'update_character_card'])
  assert.equal(tools.length, 14)
  for (const tool of tools) {
    assert.ok(!('request_id' in tool.parameters.properties))
    assert.equal(tool.output.render({}, receipt)[0].text, receipt)
  }
  assert.deepEqual(tools[0].parameters.properties.fields.required, ['name', 'description'])
})

test('existing writes ask approval, unrelated tools pass through, audit reason never includes content', async () => {
  const { hook } = register()
  assert.equal(await hook({ name: 'web_search' }, () => 'next'), 'next')
  const fields = { name: 'Name\n‮', description: 'PRIVATE_BODY' }
  const create = await hook({ name: 'create_character_card', arguments: { fields } }, () => assert.fail())
  assert.equal(create.kind, 'ask')
  assert.equal(create.reason, '创建角色卡')
  assert.ok(!JSON.stringify(create).includes('PRIVATE_BODY'))
  const download = approvalFor('import_character_card_url', { url: 'https://example.com/private?token=PRIVATE_TOKEN' })
  assert.equal(download.kind, 'ask')
  assert.ok(download.displayReason.zh.includes('example.com'))
  assert.ok(!JSON.stringify(download).includes('PRIVATE_TOKEN'))
  assert.ok(!create.displayReason.zh.includes('‮'))
})

test('call identity comes from execution, bridge receives fields and full JSON text is returned', async () => {
  const requests = []
  const fetch = async (_url, options) => { requests.push(JSON.parse(options.body)); return response() }
  const fields = { name: '角色', description: '人设' }
  assert.equal(await executeCard('create_character_card', { fields, request_id: 'model-forged' }, execution(), fetch), receipt)
  await executeCard('create_character_card', { fields }, execution(), fetch)
  await executeCard('create_character_card', { fields }, execution('call-2'), fetch)
  assert.deepEqual(requests[0].fields, fields)
  assert.equal(requests[0].request_id, requests[1].request_id)
  assert.notEqual(requests[0].request_id, requests[2].request_id)
  assert.notEqual(requests[0].request_id, 'model-forged')
  await assert.rejects(executeCard('create_character_card', { fields }, {}, fetch), /CARD_EXECUTION_ID_REQUIRED/)
})

test('real dsh runtime blocks both tools without approval or on every non-grant', async () => {
  const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
  const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href)
  const { default: Tools } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')).href)
  let requests = 0
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => { requests++; return response() }
  try {
    for (const decision of [null, 'rejected', 'cancelled', 'unavailable', 'allowed-once']) {
      const ctx = new Context()
      ctx.provide('systemPrompt', { tools: () => () => {} })
      if (decision !== null) ctx.provide('approval', { request: async () => decision })
      const runtime = await ctx.plugin(Tools)
      const mounted = await ctx.plugin(plugin)
      try {
        for (const tool of ['create_character_card', 'import_character_card_url']) {
          const before = requests
          const args = tool === 'create_character_card' ? { fields: { name: 'name', description: 'text' } } : { url: 'https://example.com/card.json' }
          const result = await ctx.tools.execute({ name: tool, callId: `${decision}-${tool}`, arguments: args,
            agent: {}, signal: new AbortController().signal })
          assert.equal(result.isError, decision !== 'allowed-once')
          assert.equal(requests - before, decision === 'allowed-once' ? 1 : 0)
        }
      } finally { await mounted.dispose(); await runtime.dispose() }
    }
  } finally { globalThis.fetch = originalFetch }
})

test('host failure is a tool error and never becomes a success receipt', async () => {
  const fetch = async () => ({ ok: true, json: async () => ({ ok: false, code: 'CARD_HTTP_403', summary: 'PRIVATE' }) })
  await assert.rejects(executeCard('import_character_card_url', { url: 'https://example.com' }, execution(), fetch), /CARD_HTTP_403/)
})

test('abort notifies authenticated host cancellation with the same request identity', async () => {
  const controller = new AbortController(), requests = []
  const fetch = async (_url, options) => {
    const body = JSON.parse(options.body)
    requests.push(body)
    if (body.action === 'cancel_character_card') return response()
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
  }
  const promise = executeCard('import_character_card_url', { url: 'https://example.com' }, { callId: 'abort-call', signal: controller.signal }, fetch)
  controller.abort()
  await assert.rejects(promise, /CARD_CANCELLED_OR_TIMEOUT/)
  assert.equal(requests[1].action, 'cancel_character_card')
  assert.equal(requests[0].request_id, requests[1].request_id)
})

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import * as plugin from '../index.mjs'

process.env.ANDROID_BRIDGE_URL = 'http://127.0.0.1:12345'
process.env.ANDROID_BRIDGE_TOKEN = 'test-only'
const args = { character_id: 'local-test', expected_revision: 'r1', fields: { personality: 'PRIVATE_PERSONALITY' } }
const exec = (name = 'update_character_card') => ({ name, arguments: structuredClone(args), callId: 'update-call', agent: {}, signal: new AbortController().signal })
const preview = (overrides = {}) => ({ kind: 'zoneu.character-card.preview.v1', ok: true, character_id: args.character_id,
  expected_revision: args.expected_revision, fingerprint: 'a'.repeat(64), unchanged: false, already_applied: false,
  field_changes: [{ field: 'personality', before_length: 3, after_length: 19 }],
  changes: { changed_fields: ['personality'], added: 0, updated: 0, removed: 0 }, ...overrides })
const jsonResponse = (payload) => ({ ok: true, json: async () => ({ ok: true, payload: JSON.stringify(payload) }) })
const writeReceipt = { kind: 'zoneu.character-card.result.v1', ok: true, characterId: args.character_id,
  disposition: 'updated', revision: 'r2', applied_revision: 'r2', replay: false, unchanged: false }
const readReceipt = { kind: 'zoneu.character-card.read.v1', ok: true, untrusted: true, character_id: args.character_id,
  section: 'personality', editable: true, revision: 'r1', content: 'PRIVATE_PERSONALITY', complete: true }
const listReceipt = { kind: 'zoneu.character-card.list.v1', ok: true, untrusted: true, items: [] }

function definitions() {
  const tools = []
  plugin.apply({ on() {}, tools: { register: (tool) => tools.push(tool) } })
  return Object.fromEntries(tools.map(tool => [tool.name, tool]))
}

async function runtime(decision, body) {
  const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
  const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href)
  const { default: Tools } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')).href)
  const ctx = new Context()
  ctx.provide('systemPrompt', { tools: () => () => {} })
  let approvals = 0
  if (decision !== null) ctx.provide('approval', { request: async () => { approvals++; return decision } })
  const tools = await ctx.plugin(Tools), mounted = await ctx.plugin(plugin)
  try { await body(ctx, () => approvals) } finally { await mounted.dispose(); await tools.dispose() }
}

const execute = (ctx, name, arguments_) => ctx.tools.execute({ name, arguments: arguments_, callId: `call-${name}`, agent: {}, signal: new AbortController().signal })

test('real schemas require ID/revision/section at field level and reject hidden metadata/null/unknown fields', async () => {
  const tools = definitions()
  assert.deepEqual(tools.update_character_card.parameters.required, ['character_id', 'expected_revision'])
  assert.deepEqual(tools.read_character_card.parameters.required, ['character_id', 'section'])
  for (const tool of Object.values(tools)) {
    assert.ok(!('request_id' in tool.parameters.properties))
    assert.ok(!('preview_fingerprint' in tool.parameters.properties))
  }
  const invalid = [
    ['update_character_card', { character_id: 'local-test', fields: { personality: 'x' } }],
    ['read_character_card', { character_id: 'local-test' }],
    ['update_character_card', { ...args, fields: { personality: null } }],
    ['update_character_card', { ...args, fields: { raw: 'x' } }],
    ['update_character_card', { ...args, request_id: 'forged' }],
  ]
  for (const [name, value] of invalid) await assert.rejects(tools[name].execute(value, exec(name)))
})

test('authenticated read-only preview produces only fixed audit reason and safe diff display', async () => {
  const requests = []
  const execution = exec()
  const transport = async (_url, options) => {
    requests.push({ headers: options.headers, body: JSON.parse(options.body) })
    return jsonResponse(preview())
  }
  const gate = await plugin.preExecute(execution, () => assert.fail(), transport)
  assert.equal(gate.kind, 'ask')
  assert.equal(gate.reason, '修改角色卡')
  assert.match(gate.displayReason.zh, /personality 3→19/)
  assert.ok(!JSON.stringify(gate).includes('PRIVATE_PERSONALITY'))
  assert.equal(requests[0].headers['x-android-bridge-token'], 'test-only')
  assert.equal(requests[0].body.action, 'preview_character_card_update')
  assert.ok(!('preview_fingerprint' in requests[0].body))
  await plugin.executeCard('update_character_card', execution.arguments, execution, async (_url, options) => {
    const body = JSON.parse(options.body)
    assert.equal(body.request_id, requests[0].body.request_id)
    assert.equal(body.preview_fingerprint, 'a'.repeat(64))
    return jsonResponse(writeReceipt)
  })
})

test('approval binding rejects parameter, revision, call ID and field changes after preview without transport', async () => {
  for (const change of [
    e => { e.arguments.fields.personality = 'changed' }, e => { e.arguments.expected_revision = 'r2' },
    e => { e.callId = 'other-call' }, e => { e.arguments.fields.description = 'new field' },
  ]) {
    const e = exec()
    await plugin.preExecute(e, () => assert.fail(), async () => jsonResponse(preview()))
    change(e)
    await assert.rejects(plugin.executeCard('update_character_card', e.arguments, e, () => assert.fail()), /CARD_PREVIEW_CONFLICT/)
  }
  await assert.rejects(plugin.executeCard('update_character_card', args, exec(), () => assert.fail()), /CARD_PREVIEW_CONFLICT/)
})

test('real ToolRuntime reads work without answerer; every non-grant leaves all three writes unexecuted', async () => {
  const original = globalThis.fetch
  try {
    for (const decision of [null, 'rejected', 'cancelled', 'unavailable', 'allowed-once']) {
      const requests = []
      globalThis.fetch = async (_url, options) => {
        const body = JSON.parse(options.body); requests.push(body)
        if (body.action === 'preview_character_card_update') return jsonResponse(preview())
        if (body.action === 'read_character_card') return jsonResponse(readReceipt)
        if (body.action === 'list_character_cards') return jsonResponse(listReceipt)
        return jsonResponse(writeReceipt)
      }
      await runtime(decision, async (ctx, approvals) => {
        for (const [name, value] of [ ['list_character_cards', {}], ['read_character_card', { character_id: args.character_id, section: 'personality' }] ]) {
          const result = await execute(ctx, name, value)
          assert.equal(Boolean(result.isError), false)
          assert.equal(approvals(), 0)
        }
        for (const [name, value] of [ ['create_character_card', { fields: { name: 'name', description: 'body' } }],
          ['import_character_card_url', { url: 'https://example.com/card.json' }], ['update_character_card', args] ]) {
          const before = requests.filter(r => !['preview_character_card_update', 'read_character_card', 'list_character_cards'].includes(r.action)).length
          const result = await execute(ctx, name, value)
          assert.equal(Boolean(result.isError), decision !== 'allowed-once')
          const after = requests.filter(r => !['preview_character_card_update', 'read_character_card', 'list_character_cards'].includes(r.action)).length
          assert.equal(after - before, decision === 'allowed-once' ? 1 : 0)
        }
      })
    }
  } finally { globalThis.fetch = original }
})

test('real runtime denies failed malformed or mismatched previews before answerer and execution', async () => {
  const original = globalThis.fetch
  try {
    for (const response of [
      () => ({ ok: true, json: async () => ({ ok: false, code: 'CARD_REVISION_CONFLICT', summary: 'PRIVATE_HOST_SUMMARY' }) }),
      () => jsonResponse({ ...preview(), kind: 'zoneu.character-card.read.v1' }),
      () => jsonResponse(preview({ character_id: 'wrong-id' })),
      () => jsonResponse(preview({ unchanged: true })),
      () => jsonResponse(preview({ field_changes: [{ field: 'PRIVATE_CONTENT', before_length: 1, after_length: 2 }] })),
    ]) {
      const requests = []
      globalThis.fetch = async (_url, options) => { requests.push(JSON.parse(options.body)); return response() }
      await runtime('allowed-once', async (ctx, approvals) => {
        const result = await execute(ctx, 'update_character_card', args)
        assert.equal(result.isError, true)
        assert.equal(approvals(), 0)
        assert.deepEqual(requests.map(r => r.action), ['preview_character_card_update'])
        assert.ok(!JSON.stringify(result).includes('PRIVATE_HOST_SUMMARY'))
        assert.match(result.error.info.code, /^CARD_/)
        if (result.error.message.startsWith('CARD_REVISION_CONFLICT:')) {
          assert.equal(result.error.info.code, 'CARD_REVISION_CONFLICT')
          assert.match(result.error.message, /Re-read/)
          assert.ok(!JSON.stringify(result).includes('CARD_TRANSPORT_FAILED'))
        }
      })
    }
  } finally { globalThis.fetch = original }
})

test('unchanged and already-applied update paths bypass approval but remain preview-bound', async () => {
  const original = globalThis.fetch
  try {
    for (const value of [preview({ unchanged: true, field_changes: [], changes: { changed_fields: [], added: 0, updated: 0, removed: 0 } }),
      preview({ already_applied: true })]) {
      const requests = []
      globalThis.fetch = async (_url, options) => {
        const body = JSON.parse(options.body); requests.push(body)
        return jsonResponse(body.action === 'preview_character_card_update' ? value : writeReceipt)
      }
      await runtime(null, async (ctx, approvals) => {
        const result = await execute(ctx, 'update_character_card', args)
        assert.equal(Boolean(result.isError), false)
        assert.equal(approvals(), 0)
        assert.deepEqual(requests.map(r => r.action), ['preview_character_card_update', 'update_character_card'])
        assert.equal(requests[0].request_id, requests[1].request_id)
      })
    }
  } finally { globalThis.fetch = original }
})

test('each read requires its exact result kind and retains full JSON text without body in progress/error', async () => {
  for (const [tool, value, wrong] of [
    ['list_character_cards', {}, writeReceipt], ['read_character_card', { character_id: args.character_id, section: 'personality' }, listReceipt],
  ]) {
    await assert.rejects(plugin.executeCard(tool, value, exec(tool), async () => jsonResponse(wrong)), /CARD_INVALID_RECEIPT/)
  }
  const e = exec('read_character_card'), value = { character_id: args.character_id, section: 'personality' }
  assert.equal(await plugin.executeCard('read_character_card', value, e, async () => jsonResponse(readReceipt)), JSON.stringify(readReceipt))
  await assert.rejects(plugin.executeCard('read_character_card', value, e,
    async () => jsonResponse({ ...readReceipt, content: 'x'.repeat(32768) })), /CARD_INVALID_RECEIPT/)
  await assert.rejects(plugin.executeCard('cancel_character_card', {}, e, () => assert.fail()), /CARD_EXECUTION_ID_REQUIRED/)
})


test('valid local storage IDs survive write receipts; path-like or malformed IDs fail closed', async () => {
  for (const id of ['local.card-v1', 'a' + 'x'.repeat(199)]) {
    const value = { ...writeReceipt, characterId: id }
    assert.equal(await plugin.executeCard('create_character_card', { fields: { name: 'name', description: 'body' } },
      exec('create_character_card'), async () => jsonResponse(value)), JSON.stringify(value))
  }
  for (const id of ['../private', '.hidden', '_hidden', 'a/b', 'a\b', 'a'.repeat(201)]) {
    await assert.rejects(plugin.executeCard('create_character_card', { fields: { name: 'name', description: 'body' } },
      exec('create_character_card'), async () => jsonResponse({ ...writeReceipt, characterId: id })), /CARD_INVALID_RECEIPT/)
  }
})

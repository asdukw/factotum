import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import * as plugin from '../index.mjs'
import { preExecuteWorld, executeWorld, WORLD_WRITES as worldWrites, WORLD_READS as worldReads } from '../world-tools.mjs'

process.env.ANDROID_BRIDGE_URL = 'http://127.0.0.1:12345'
process.env.ANDROID_BRIDGE_TOKEN = 'test-only'
const samples = {
  create_world_book: { fields: { name: 'PRIVATE_NAME', description: 'PRIVATE_BODY' } },
  update_world_book: { book_id: 'book-1', expected_revision: 'r1', fields: { description: 'PRIVATE_BODY' } },
  copy_world_book: { book_id: 'book-1', expected_revision: 'r1' },
  delete_world_book: { book_id: 'book-1', expected_revision: 'r1' },
  update_world_book_bindings: { scope: 'character', target_id: 'role-1', expected_revision: 'r1', book_ids: ['book-1'] },
  list_world_books: { query: 'PRIVATE_QUERY' },
  read_world_book: { book_id: 'book-1', section: 'entries' },
  read_world_book_bindings: { scope: 'session', target_id: 'chat-1' },
  explain_world_activation: { session_id: 'chat-1', text: 'PRIVATE_TEST' },
}
const execution = name => ({ name, arguments: structuredClone(samples[name]), callId: `call-${name}`, agent: { session: { id: 'session-1' } }, signal: new AbortController().signal })
const response = value => ({ ok: true, json: async () => ({ ok: true, payload: JSON.stringify(value) }) })
function preview(name, mode = 'write') {
  const args = samples[name]
  return { kind: 'zoneu.world-book.preview.v1', ok: true, operation: name, mode, fingerprint: 'a'.repeat(64),
    ...(args.book_id ? { book_id: args.book_id } : {}), ...(args.scope ? { scope: args.scope, target_id: args.target_id } : {}),
    ...(args.expected_revision ? { expected_revision: args.expected_revision } : {}), field_changes: [],
    counts: { added: 0, updated: 0, removed: 0, character_references: 0, session_references: 0 } }
}
function result(name, mode = 'write') {
  const args = samples[name], base = { ok: true, revision: 'r2', replay: mode === 'replay' }
  if (name.endsWith('_bindings')) return { ...base, kind: 'zoneu.world-book.bindings.v1', scope: args.scope, target_id: args.target_id,
    untrusted: true, items: [], disposition: mode === 'unchanged' ? 'unchanged' : 'updated' }
  if (name === 'list_world_books') return { ...base, kind: 'zoneu.world-book.list.v1', untrusted: true, items: [] }
  if (name === 'read_world_book') return { ...base, kind: 'zoneu.world-book.read.v1', untrusted: true, book_id: args.book_id, section: args.section }
  if (name === 'explain_world_activation') return { ...base, kind: 'zoneu.world-book.explanation.v1', untrusted: true, session_id: args.session_id }
  const disposition = { create_world_book: 'created', update_world_book: 'updated', copy_world_book: 'copied', delete_world_book: 'deleted' }[name]
  return { ...base, kind: 'zoneu.world-book.result.v1', book_id: args.book_id ?? 'new-book', disposition: mode === 'unchanged' ? 'unchanged' : disposition }
}
function definitions() {
  const tools = []
  plugin.apply({ on() {}, tools: { register: value => tools.push(value) } })
  return Object.fromEntries(tools.map(tool => [tool.name, tool]))
}
async function runtime(decision, run) {
  const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
  const { Context } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href)
  const { default: Tools } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-tools')).href)
  const ctx = new Context()
  ctx.provide('systemPrompt', { tools: () => () => {} })
  let approvals = 0
  if (decision !== null) ctx.provide('approval', { request: async () => { approvals++; return decision } })
  const tools = await ctx.plugin(Tools), mounted = await ctx.plugin(plugin)
  try { await run(ctx, () => approvals) } finally { await mounted.dispose(); await tools.dispose() }
}

const runTool = (ctx, name, args = samples[name]) => ctx.tools.execute({ ...execution(name), arguments: args })

test('original five tools remain; exactly nine world tools and no internal operations are model-visible', () => {
  const tools = definitions()
  for (const name of ['create_character_card', 'import_character_card_url', 'list_character_cards', 'read_character_card', 'update_character_card']) assert.ok(tools[name])
  assert.equal(Object.keys(tools).length, 14)
  for (const name of [...worldWrites, ...worldReads]) {
    assert.ok(tools[name])
    for (const key of ['request_id', 'preview_fingerprint', 'action', 'operation']) assert.ok(!(key in tools[name].parameters.properties))
  }
})

test('actual DSL accepts every management field and rule without dropping enabled or recursion flags', async () => {
  const original = globalThis.fetch
  const full = { book_id: 'book-1', expected_revision: 'r1', fields: { name: 'x', description: '', tags: [], category: '', enabled: false }, entry_ops: [
    { op: 'add', fields: { enabled: false, keys: ['station'], content: '', order: 1, constant: true, depth: 0, secondary: [], secondary_logic: 'NOT_ALL',
      exclude: [], probability: 50, sticky: 1, cooldown: 2, position: 4, role: 1, use_probability: true, match_whole_words: true,
      case_sensitive: true, prevent_recursion: true, exclude_recursion: true } },
    { op: 'update', entry_id: 'entry-1', fields: { enabled: true } }, { op: 'remove', entry_id: 'entry-2' }, { op: 'move', entry_id: 'entry-3', index: 0 },
  ] }
  const calls = []
  globalThis.fetch = async (_url, options) => {
    const body = JSON.parse(options.body); calls.push(body)
    return response(body.action === 'preview_world_book_action' ? preview('update_world_book') : result('update_world_book'))
  }
  try {
    await runtime('allowed-once', async ctx => { const value = await runTool(ctx, 'update_world_book', full); assert.equal(Boolean(value.isError), false, JSON.stringify(value)) })
    for (const body of calls) {
      delete body.action; delete body.operation; delete body.request_id; delete body.preview_fingerprint
      assert.deepEqual(body, full)
    }
  } finally { globalThis.fetch = original }
})

test('actual DSL rejects missing fields, hidden metadata, null, raw and unknown properties before mutation', async () => {
  const tools = definitions()
  for (const [name, args] of [
    ['read_world_book', { book_id: 'book-1' }], ['update_world_book', { book_id: 'book-1' }],
    ['create_world_book', { fields: {} }], ['update_world_book', { ...samples.update_world_book, fields: { enabled: null } }],
    ['update_world_book', { ...samples.update_world_book, entry_ops: [{ op: 'update', entry_id: 'entry', fields: { raw: {} } }] }],
    ['delete_world_book', { ...samples.delete_world_book, request_id: 'forged' }],
  ]) await assert.rejects(tools[name].execute(args, execution(name)))
})

test('official runtime: all five writes preflight read-only, and every non-grant causes zero mutations', async () => {
  const original = globalThis.fetch
  try {
    for (const decision of [null, 'rejected', 'cancelled', 'unavailable', 'allowed-once']) {
      const requests = []
      globalThis.fetch = async (_url, options) => {
        const body = JSON.parse(options.body); requests.push(body)
        return response(body.action === 'preview_world_book_action' ? preview(body.operation) : result(body.action))
      }
      await runtime(decision, async (ctx, approvals) => {
        for (const name of worldReads) { const value = await runTool(ctx, name); assert.equal(Boolean(value.isError), false, JSON.stringify(value)) }
        assert.equal(approvals(), 0)
        for (const name of worldWrites) {
          const before = requests.length, toolResult = await runTool(ctx, name)
          assert.equal(Boolean(toolResult.isError), decision !== 'allowed-once', `${decision}/${name}`)
          assert.deepEqual(requests.slice(before).map(r => r.action), decision === 'allowed-once' ? ['preview_world_book_action', name] : ['preview_world_book_action'])
        }
      })
    }
  } finally { globalThis.fetch = original }
})

test('preview audit reason and display contain only safe IDs, field lengths and counts', async () => {
  for (const name of worldWrites) {
    const e = execution(name), calls = []
    const gate = await preExecuteWorld(e, () => assert.fail(), async (_url, options) => { calls.push(JSON.parse(options.body)); return response(preview(name)) })
    assert.equal(gate.kind, 'ask', JSON.stringify(gate))
    assert.ok(!JSON.stringify(gate).includes('PRIVATE'))
    assert.match(calls[0].request_id, /^[a-f0-9]{64}$/)
    assert.equal(calls[0].action, 'preview_world_book_action')
  }
})

test('mutated arguments/call/session/exec identity cannot reuse approval; malformed previews fail before answerer', async () => {
  for (const change of [e => { e.arguments.fields.description = 'changed' }, e => { e.callId = 'changed' }, e => { e.agent.session.id = 'changed' }]) {
    const e = execution('update_world_book')
    await preExecuteWorld(e, () => assert.fail(), async () => response(preview(e.name)))
    change(e)
    await assert.rejects(executeWorld(e.name, e.arguments, e, () => assert.fail()), /WORLD_PREVIEW_CONFLICT/)
  }
  const e = execution('update_world_book')
  await preExecuteWorld(e, () => assert.fail(), async () => response(preview(e.name)))
  await assert.rejects(executeWorld(e.name, e.arguments, { ...e }, () => assert.fail()), /WORLD_PREVIEW_CONFLICT/)
  for (const invalid of [{ ...preview(e.name), kind: 'zoneu.world-book.result.v1' }, { ...preview(e.name), book_id: 'other' },
    { ...preview(e.name), field_changes: [{ field: 'PRIVATE_BODY', before_length: 0, after_length: 1 }] }]) {
    assert.equal((await preExecuteWorld(execution(e.name), () => assert.fail(), async () => response(invalid))).kind, 'deny')
  }
})

test('no-op and replay bypass answerer but retain host fingerprint and disposition enforcement', async () => {
  const original = globalThis.fetch
  try {
    for (const mode of ['unchanged', 'replay']) {
      const requests = []
      globalThis.fetch = async (_url, options) => {
        const body = JSON.parse(options.body); requests.push(body)
        return response(body.action === 'preview_world_book_action' ? preview(body.operation, mode) : result(body.action, mode))
      }
      await runtime(null, async (ctx, approvals) => {
        assert.equal(Boolean((await runTool(ctx, 'update_world_book')).isError), false)
        assert.equal(approvals(), 0)
        assert.equal(requests[0].request_id, requests[1].request_id)
        assert.equal(requests[1].preview_fingerprint, 'a'.repeat(64))
      })
    }
  } finally { globalThis.fetch = original }
})

test('strict result kinds, scope, IDs, untrusted flag and 32KiB cap are verified', async () => {
  for (const name of worldReads) {
    const e = execution(name), value = result(name)
    for (const invalid of [{ ...value, kind: 'zoneu.world-book.result.v1' }, { ...value, untrusted: false }, { ...value, content: 'x'.repeat(32768) }]) {
      await assert.rejects(executeWorld(name, e.arguments, e, async () => response(invalid)), /WORLD_INVALID_RECEIPT/)
    }
  }
  const e = execution('update_world_book_bindings')
  await preExecuteWorld(e, () => assert.fail(), async () => response(preview(e.name)))
  await assert.rejects(executeWorld(e.name, e.arguments, e, async () => response({ ...result(e.name), kind: 'zoneu.world-book.result.v1', book_id: 'book-1' })), /WORLD_INVALID_RECEIPT/)
  await assert.rejects(executeWorld('resolve_world_request', {}, e, () => assert.fail()), /WORLD_EXECUTION_ID_REQUIRED/)
})


test('read-only host errors never leak body; no-op and replay receipts cannot claim fresh writes', async () => {
  const read = execution('list_world_books')
  await assert.rejects(executeWorld(read.name, read.arguments, read, async () => ({ ok: true,
    json: async () => ({ ok: false, code: 'WORLD_CURSOR_STALE', summary: 'PRIVATE_HOST_BODY' }) })), error =>
      error.message.startsWith('WORLD_CURSOR_STALE:') && !error.message.includes('PRIVATE'))
  for (const mode of ['unchanged', 'replay']) {
    const e = execution('update_world_book')
    await preExecuteWorld(e, () => assert.fail(), async () => response(preview(e.name, mode)))
    await assert.rejects(executeWorld(e.name, e.arguments, e, async () => response(result(e.name))), /WORLD_INVALID_RECEIPT/)
  }
})

test('active write abort sends authenticated cancellation with identity only', async () => {
  const controller = new AbortController(), e = execution('update_world_book'), requests = []
  e.signal = controller.signal
  await preExecuteWorld(e, () => assert.fail(), async () => response(preview(e.name)))
  const fetch = async (_url, options) => {
    const body = JSON.parse(options.body); requests.push(body)
    if (body.action === 'cancel_world_book_action') return response({})
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
  }
  const promise = executeWorld(e.name, e.arguments, e, fetch)
  controller.abort()
  await assert.rejects(promise, /WORLD_CANCELLED_OR_TIMEOUT/)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(requests[1].action, 'cancel_world_book_action')
  assert.equal(requests[1].request_id, requests[0].request_id)
  assert.equal(typeof requests[1].parameters, 'string')
  assert.ok(requests[1].parameters.includes('update_world_book'))
})

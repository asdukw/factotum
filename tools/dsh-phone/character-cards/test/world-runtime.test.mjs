import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { createRequire } from 'node:module'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { patchWorldRuntime } from '../../world-runtime-patch.mjs'
import * as worldPlugin from '../world-runtime.mjs'
const { createWorldRuntime, projectWorldPayload, projectionForPlan, validateWorldPlan } = worldPlugin

// The cache is read-only. Only a new, disposable directory receives transformed source.
const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
const cached = name => import(pathToFileURL(require.resolve(name)).href)
const llm = await cached('@deepseek-ai/dsh-llm')
const { Context } = await cached('@deepseek-ai/cordis')
const { LlmRuntime, createSystemMessage, createUserMessage, createAssistantMessage,
  createToolResultMessage, markAgentLoopRequest, isAgentLoopRequest } = llm
const adapterPath = require.resolve('@deepseek-ai/dsh-llm-pi-ai')
const rawAdapter = await readFile(adapterPath, 'utf8')
const scratch = await mkdtemp(join(tmpdir(), 'zoneu-world-test-'))
const patchedAdapter = patchWorldRuntime(rawAdapter)
const importUrl = specifier => {
  if (specifier.startsWith('node:')) return specifier
  // pi-ai intentionally offers import-only package exports; resolve its absolute
  // exported .js path through the read-only cache, not a fake require condition.
  const target = specifier.startsWith('@earendil-works/pi-ai/')
    ? resolve(dirname(adapterPath), '../../../@earendil-works/pi-ai/dist', specifier.slice('@earendil-works/pi-ai/'.length) + '.js')
    : specifier
  return pathToFileURL(require.resolve(target)).href
}
const loadableAdapter = patchedAdapter.replace(/from "([^".][^"]*)"/g, (_all, specifier) =>
  `from ${JSON.stringify(importUrl(specifier))}`)
await writeFile(join(scratch, 'adapter.mjs'), `${loadableAdapter}\nexport { resolveProfiles };\n`)
const { PiAiAdapter, Config, resolveProfiles } = await import(pathToFileURL(join(scratch, 'adapter.mjs')).href)
after(() => rm(scratch, { recursive: true, force: true }))

const ANCHOR = '<character_anchor id="world-runtime-test">UNIQUE_CHARACTER</character_anchor>'
const SYSTEM = `DEPLOYMENT_PREFIX\n\n${ANCHOR}\n\nDEPLOYMENT_SUFFIX`
const text = value => [{ type: 'text', text: value }]
const user = value => createUserMessage({ source: { kind: 'user' }, content: text(value) })
const synthetic = value => createUserMessage({ source: { kind: 'runtime-context' }, content: text(value) })
const assistant = (value, source = {}) => createAssistantMessage({ source: { provider: 'local', model: 'world-test', ...source }, content: text(value) })
const deepFreeze = value => {
  if (value && typeof value === 'object' && !(value instanceof AbortSignal)) {
    Object.freeze(value)
    for (const child of Object.values(value)) deepFreeze(child)
  }
  return value
}
const entry = (content, role = 'system', depth = 0, position = 'depth') =>
  ({ entry_id: `entry-${content.replace(/[^a-zA-Z0-9]/g, '-').slice(0, 100)}`, content, role, depth, position })
const plan = (entries = [], overrides = {}) => ({ kind: 'zoneu.world.request.v1', active: true,
  request_id: 'request-1', session_id: 'session-1', binding_revision: 'revision-1',
  character_anchor: ANCHOR, injections: entries, ...overrides })
const bridgeResponse = value => new Response(JSON.stringify({ ok: true, payload: JSON.stringify(value) }), {
  status: 200, headers: { 'content-type': 'application/json' },
})
const collect = async stream => { const out = []; for await (const chunk of stream) out.push(chunk); return out }
const failureOf = chunks => chunks.find(chunk => chunk.type === 'finish' && ['error', 'aborted'].includes(chunk.reason.kind))?.reason.failure
const assertSuccess = chunks => {
  assert.equal(failureOf(chunks), undefined, JSON.stringify(failureOf(chunks)))
  assert.ok(chunks.some(chunk => chunk.type === 'finish'), 'a real provider terminal chunk must be observed')
}
const options = (messages = [createSystemMessage(SYSTEM), user('TRIGGER')], extra = {}, marked = true) => {
  const value = deepFreeze({ provider: 'local', model: 'world-test', sessionId: 'session-1', messages, ...extra })
  return marked ? markAgentLoopRequest(value) : value
}
function sse(events) {
  return new Response(events.map(event => `event: ${event.type ?? 'message'}
data: ${typeof event === 'string' ? event : JSON.stringify(event)}\n\n`).join(''), {
    status: 200, headers: { 'content-type': 'text/event-stream', 'x-request-id': 'fake-local-request' },
  })
}
function completionResponse(tool = false) {
  const base = { id: 'chatcmpl-local', object: 'chat.completion.chunk', created: 1, model: 'world-test' }
  return sse([
    { ...base, choices: [{ index: 0, delta: tool ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_loop', type: 'function', function: { name: 'local_probe', arguments: '{}' } }] } : { role: 'assistant', content: 'LOCAL_ANSWER' }, finish_reason: null }] },
    { ...base, choices: [{ index: 0, delta: {}, finish_reason: tool ? 'tool_calls' : 'stop' }] },
    '[DONE]',
  ])
}
function responsesResponse() {
  const item = { id: 'msg_fake', type: 'message', status: 'completed', role: 'assistant', content: [{ type: 'output_text', text: 'LOCAL_ANSWER', annotations: [] }] }
  return sse([
    { type: 'response.created', response: { id: 'resp_fake', status: 'in_progress', model: 'world-test', output: [] } },
    { type: 'response.output_item.added', output_index: 0, item: { ...item, content: [] } },
    { type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } },
    { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: 'LOCAL_ANSWER' },
    { type: 'response.output_item.done', output_index: 0, item },
    { type: 'response.completed', response: { id: 'resp_fake', status: 'completed', model: 'world-test', output: [item], usage: { input_tokens: 10, output_tokens: 2, total_tokens: 12 } } },
  ])
}
function anthropicResponse() {
  return sse([
    { type: 'message_start', message: { id: 'msg_fake', type: 'message', role: 'assistant', model: 'world-test', content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 10, output_tokens: 0 } } },
    { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
    { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'LOCAL_ANSWER' } },
    { type: 'content_block_stop', index: 0 },
    { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 2 } },
    { type: 'message_stop' },
  ])
}

async function harness(api, configuration, body) {
  const config = { entries: [], ...configuration }
  const ctx = new Context()
  const fibers = []
  const wires = [], bridge = [], projected = [], markers = []
  const bindings = new Map()
  let projectionDepth = 0
  const originalFetch = globalThis.fetch
  const originalEnvironment = [process.env.ANDROID_BRIDGE_URL, process.env.ANDROID_BRIDGE_TOKEN]
  const bridgeTransport = async (url, init) => {
    assert.equal(url, 'http://127.0.0.1:1/action')
    assert.equal(init.headers['x-android-bridge-token'], 'test-only-token')
    const request = JSON.parse(init.body); bridge.push(request)
    if (config.bridge) return config.bridge(request, init)
    return bridgeResponse(plan(config.entries, { session_id: request.dsh_session_id }))
  }
  let world = createWorldRuntime({
    bridgeUrl: () => 'http://127.0.0.1:1', bridgeToken: () => 'test-only-token', timeoutMs: 3000,
    transport: bridgeTransport, ...config.runtime,
  })
  globalThis.fetch = async (url, init = {}) => {
    const address = typeof url === 'string' ? url : url.url ?? String(url)
    assert.ok(address.startsWith('http://127.0.0.1:1/'), `external network prohibited: ${address}`)
    if (address.endsWith('/action')) return bridgeTransport(address, init)
    const request = JSON.parse(init.body)
    wires.push(request)
    if (config.respond) return config.respond(request, wires.length)
    return api === 'openai-responses' ? responsesResponse() : api === 'anthropic-messages' ? anthropicResponse() : completionResponse()
  }
  try {
    fibers.push(await ctx.plugin(LlmRuntime))
    ctx.on('llm/stream', (request, next) => { markers.push(isAgentLoopRequest(request)); return config.plugin ? next() : world.stream(request, next) })
    if (!config.plugin) ctx.on('agent/request', (event, next) => { world.captureRequest(event); return next() })
    if (config.plugin) {
      process.env.ANDROID_BRIDGE_URL = 'http://127.0.0.1:1'
      process.env.ANDROID_BRIDGE_TOKEN = 'test-only-token'
      ctx.provide('zoneuWorldLifecycle', { identityFor: sessionId => bindings.get(sessionId) })
      ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
        if (message.role === 'user' && message.source?.kind === 'user') bindings.set(agent.session.id, {
          sessionId: agent.session.id, trigger: message.id, turn,
        })
      })
      fibers.push(await ctx.plugin(worldPlugin))
      world = ctx.get('zoneuWorldRuntime')
    }
    const parsed = Config({ providers: { local: { api, baseURL: 'http://127.0.0.1:1/v1',
      defaultInput: config.image ? ['text', 'image'] : ['text'],
      models: [{ id: 'world-test', contextWindow: 65536, maxTokens: 1024, ...config.model }],
      ...config.profile } } })
    const profiles = resolveProfiles(parsed.providers.get())
    const Adapter = config.originalAdapter ? (await cached('@deepseek-ai/dsh-llm-pi-ai')).PiAiAdapter : PiAiAdapter
    const adapter = new Adapter({ profiles: () => profiles, resolveApiKey: async () => 'test-only-key',
      resolveAttachments: () => config.attachments,
      projectRequest: async input => {
        projected.push(input)
        if (config.auxiliary && projectionDepth === 0) {
          projectionDepth++
          try { await config.auxiliary({ ctx, input }) } finally { projectionDepth-- }
        }
        return world.project(input)
      },
    })
    ctx.llm.registerAdapter(['local'], adapter)
    await body({ ctx, world, wires, bridge, projected, markers, fibers, adapter, bindings })
  } finally {
    globalThis.fetch = originalFetch
    for (const [key, value] of [['ANDROID_BRIDGE_URL', originalEnvironment[0]], ['ANDROID_BRIDGE_TOKEN', originalEnvironment[1]]]) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
    for (const fiber of fibers.reverse()) await fiber.dispose()
  }
}
const wireItems = (wire, api) => wire[api === 'openai-responses' ? 'input' : 'messages']
const contains = (item, value) => JSON.stringify(item).includes(value)
const filtered = (items, values) => items.filter(item => !values.some(value => contains(item, value)))

// Pure transformation drift gates use the exact cached rc.2 bundle, never a toy source.
test('patch: actual cached provider package is 0.2.0-rc.2 and projection patch is idempotent', async () => {
  const metadata = JSON.parse(await readFile(require.resolve('@deepseek-ai/dsh-llm-pi-ai/package.json'), 'utf8'))
  assert.equal(metadata.version, '0.2.0-rc.2')
  assert.equal(patchWorldRuntime(patchedAdapter), patchedAdapter)
  assert.equal((patchedAdapter.match(/const zoneuProjection =/g) ?? []).length, 1)
  assert.equal((patchedAdapter.match(/projectRequest: \(request\)/g) ?? []).length, 1)
  assert.equal(await readFile(adapterPath, 'utf8'), rawAdapter, 'cache must not be written')
})
const anchors = [
  '\t\t\t\tconst iterator = toStreamChunks(snapshot.models.streamSimple(model, context, {',
  '\t\t\t\t\theaders: requestHeaders(profile.headers)\n\t\t\t\t}), model.contextWindow',
  '\tconst adapter = new PiAiAdapter({\n\t\tprofiles,\n\t\tresolveApiKey,',
]
for (let i = 0; i < anchors.length; i++) {
  test(`patch: missing anchor ${i + 1} fails closed`, () => {
    assert.ok(rawAdapter.includes(anchors[i]))
    assert.throws(() => patchWorldRuntime(rawAdapter.replace(anchors[i], 'ANCHOR_REMOVED')), /WORLD_RUNTIME_PATCH_ANCHOR_MISMATCH/)
  })
  test(`patch: duplicate anchor ${i + 1} fails closed`, () => {
    assert.throws(() => patchWorldRuntime(`${rawAdapter}\n${anchors[i]}`), /WORLD_RUNTIME_PATCH_ANCHOR_MISMATCH/)
  })
}
test('patch: partially applied and mixed bundles fail instead of silently skipping', () => {
  const start = patchedAdapter.indexOf('\t\t\t\tconst zoneuProjection =')
  const end = patchedAdapter.indexOf('\t\t\t\t\t...profileOptions', start)
  const partial = rawAdapter.replace(anchors[0], patchedAdapter.slice(start, end).trimEnd())
  assert.throws(() => patchWorldRuntime(partial), /WORLD_RUNTIME_PATCH_ANCHOR_MISMATCH/)
  assert.throws(() => patchWorldRuntime(`${patchedAdapter}\n${anchors[0]}`), /WORLD_RUNTIME_PATCH_ANCHOR_MISMATCH/)
})

test('plan: reject malformed, duplicate, oversized and unsupported-role entries with safe codes', () => {
  assert.equal(validateWorldPlan({ kind: 'zoneu.world.request.v1', active: false }).active, false)
  for (const invalid of [null, {}, plan([entry('x'), entry('x')]), plan([entry('x', 'tool')]),
    plan([entry('x', 'user', 0, 'before')]), plan([entry('x', 'system', 13)]),
    plan([entry('  ')]), plan([], { session_id: 'private text with spaces' })]) {
    assert.throws(() => validateWorldPlan(invalid), error => error.code === 'WORLD_INVALID_PLAN' && !error.message.includes('private text'))
  }
  assert.throws(() => validateWorldPlan(plan([entry('x'.repeat(1024 * 1024 + 1))])), error => error.code === 'WORLD_PLAN_TOO_LARGE')
})
test('anchor: exact single character anchor inserts in system without changing deployment sections', () => {
  const input = deepFreeze({ context: { systemPrompt: SYSTEM, messages: [] }, model: { api: 'openai-completions' } })
  const snapshot = JSON.stringify(input)
  const result = projectionForPlan(input, plan([entry('BEFORE', 'system', 0, 'before'), entry('AFTER', 'system', 0, 'after')]))
  assert.equal(result.context.systemPrompt, `DEPLOYMENT_PREFIX\n\nBEFORE\n\n${ANCHOR}\n\nAFTER\n\nDEPLOYMENT_SUFFIX`)
  assert.equal(JSON.stringify(input), snapshot)
})
for (const badAnchor of [undefined, '', 'missing']) test(`anchor: absent or unmatched anchor ${String(badAnchor)} is rejected`, () => {
  assert.throws(() => projectionForPlan({ context: { systemPrompt: SYSTEM }, model: { api: 'openai-completions' } },
    plan([entry('BEFORE', 'system', 0, 'before')], { character_anchor: badAnchor })), error => error.code === 'WORLD_CHARACTER_ANCHOR_REQUIRED')
})
test('anchor: repeated exact anchor is rejected, not arbitrarily positioned', () => {
  assert.throws(() => projectionForPlan({ context: { systemPrompt: `${SYSTEM}\n${ANCHOR}` }, model: { api: 'openai-completions' } },
    plan([entry('BEFORE', 'system', 0, 'before')])), error => error.code === 'WORLD_CHARACTER_ANCHOR_REQUIRED')
})

for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages']) {
  test(`wire ${api}: real LlmRuntime + PiAiAdapter + pi-ai preserve before/after in leading system`, async () => {
    await harness(api, { entries: [entry('WORLD_BEFORE', 'system', 0, 'before'), entry('WORLD_AFTER', 'system', 0, 'after')] }, async ({ ctx, wires, bridge, projected }) => {
      const request = options(), snapshot = JSON.stringify(request)
      assertSuccess(await collect(ctx.llm.stream(request)))
      assert.equal(wires.length, 1)
      const system = api === 'anthropic-messages' ? wires[0].system : wireItems(wires[0], api)[0].content
      const serialized = typeof system === 'string' ? system : JSON.stringify(system)
      assert.ok(serialized.indexOf('DEPLOYMENT_PREFIX') < serialized.indexOf('WORLD_BEFORE'))
      assert.ok(serialized.indexOf('WORLD_BEFORE') < serialized.indexOf('UNIQUE_CHARACTER'))
      assert.ok(serialized.indexOf('UNIQUE_CHARACTER') < serialized.indexOf('WORLD_AFTER'))
      assert.ok(serialized.indexOf('WORLD_AFTER') < serialized.indexOf('DEPLOYMENT_SUFFIX'))
      assert.equal(bridge[0].trigger_message_id, request.messages[1].id)
      assert.equal(bridge[0].action, 'resolve_world_request')
      assert.notEqual(projected[0].options, request, 'LlmRuntime materializes maxTokens into a cloned envelope')
      assert.equal(isAgentLoopRequest(projected[0].options), false, 'identity is checked at middleware entry, not after cloning')
      assert.equal(JSON.stringify(request), snapshot)
    })
  })
  for (const role of ['system', 'user', 'assistant']) {
    test(`wire ${api}: depth=1 preserves requested ${role} role or explicitly refuses it`, async () => {
      const request = options([createSystemMessage(SYSTEM), user('OLDER_USER'), assistant('OLDER_ASSISTANT'), user('LATEST_USER')])
      let baseline
      await harness(api, {}, async ({ ctx, wires }) => { assertSuccess(await collect(ctx.llm.stream(request))); baseline = wires[0] })
      await harness(api, { entries: [entry('WORLD_DEPTH', role, 1)] }, async ({ ctx, wires }) => {
        const chunks = await collect(ctx.llm.stream(request))
        if (api === 'anthropic-messages' && role === 'system') {
          assert.equal(failureOf(chunks)?.code, 'WORLD_PROTOCOL_UNSUPPORTED')
          assert.equal(wires.length, 0, 'system depth cannot be silently converted to user')
          return
        }
        assertSuccess(chunks)
        const items = wireItems(wires[0], api), index = items.findIndex(item => contains(item, 'WORLD_DEPTH'))
        assert.ok(index > 0)
        assert.equal(items[index].role, role)
        assert.ok(contains(items[index - 1], 'OLDER_ASSISTANT'))
        assert.ok(contains(items[index + 1], 'LATEST_USER'))
        assert.deepEqual(filtered(items, ['WORLD_DEPTH']), wireItems(baseline, api))
      })
    })
  }
}

test('wire: depth 0 appends, large depth clamps to history start after the system prefix, stable tie order', async () => {
  await harness('openai-completions', { entries: [entry('AT_START', 'user', 12), entry('AT_END_1', 'assistant', 0), entry('AT_END_2', 'user', 0)] }, async ({ ctx, wires }) => {
    assertSuccess(await collect(ctx.llm.stream(options())))
    assert.deepEqual(wires[0].messages.map(item => item.role), ['system', 'user', 'user', 'assistant', 'user'])
    assert.equal(wires[0].messages[1].content, 'AT_START')
    assert.equal(wires[0].messages.at(-2).content, 'AT_END_1')
    assert.equal(wires[0].messages.at(-1).content, 'AT_END_2')
  })
})

const attachment = { attachmentId: 'image-local', mediaType: 'image/png', width: 1, height: 1, bytes: 4 }
const attachments = { readImageRequest: async () => ({ data: new Uint8Array([1, 2, 3, 4]), bytes: 4, mediaType: 'image/png', width: 1, height: 1 }) }
function richHistory(api) {
  const source = { provider: 'local', model: 'world-test', replayState: {
    response: { kind: 'pi-ai', version: 2, api, provider: 'local', model: 'world-test', stopReason: 'toolUse' },
    blocks: [{ type: 'reasoning', thinkingSignature: api === 'openai-responses' ? JSON.stringify({ type: 'reasoning', id: 'rs_local', summary: [], encrypted_content: 'ENCRYPTED_REASONING' }) : api === 'openai-completions' ? 'reasoning_content' : 'THINKING_SIGNATURE' },
      { type: 'text' }, { type: 'tool-call' }, { type: 'tool-call' }],
  } }
  return [createSystemMessage(SYSTEM), createUserMessage({ source: { kind: 'user' }, content: [...text('IMAGE_USER'), { type: 'image', attachment }] }),
    createAssistantMessage({ source, content: [{ type: 'reasoning', text: 'REASONING_TEXT' }, ...text('TOOL_PREFACE'),
      { type: 'tool-call', id: 'call_a', name: 'local_probe', arguments: '{"probe":1}' },
      { type: 'tool-call', id: 'call_b', name: 'local_probe', arguments: '{"probe":2}' }] }),
    createToolResultMessage({ callId: 'call_a', content: text('RESULT_A'), isError: false }),
    createToolResultMessage({ callId: 'call_b', content: text('RESULT_B'), isError: false }), user('FINAL_USER')]
}
for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages']) test(`wire ${api}: tool exchange is indivisible and native reasoning/images survive injection`, async () => {
  const request = options(richHistory(api)), snapshot = JSON.stringify(request)
  const configuration = { image: true, attachments, model: { reasoningEfforts: { off: null, low: 'low' } },
    ...(api === 'openai-completions' ? { profile: { compat: { requiresReasoningContentOnAssistantMessages: true } } } : {}) }
  let baseline
  await harness(api, configuration, async ({ ctx, wires }) => { assertSuccess(await collect(ctx.llm.stream(request))); baseline = wires[0] })
  await harness(api, { ...configuration, entries: [entry('BEFORE_TOOL_UNIT', 'user', 2), entry('AFTER_TOOL_UNIT', 'assistant', 1)] }, async ({ ctx, wires }) => {
    assertSuccess(await collect(ctx.llm.stream(request)))
    const items = wireItems(wires[0], api), before = items.findIndex(item => contains(item, 'BEFORE_TOOL_UNIT')),
      after = items.findIndex(item => contains(item, 'AFTER_TOOL_UNIT'))
    assert.ok(before >= 0 && after > before)
    const exchange = items.slice(before + 1, after)
    for (const needle of ['call_a', 'call_b', 'RESULT_A', 'RESULT_B']) assert.ok(contains(exchange, needle), `${needle} must remain inside one depth unit`)
    assert.ok(!contains(exchange, 'FINAL_USER'))
    assert.ok(contains(wires[0], 'data:image/png;base64,AQIDBA==') || contains(wires[0], 'AQIDBA=='))
    assert.ok(contains(wires[0], api === 'openai-responses' ? 'ENCRYPTED_REASONING' : 'REASONING_TEXT'))
    if (api === 'anthropic-messages') assert.ok(contains(wires[0], 'THINKING_SIGNATURE'))
    assert.deepEqual(filtered(items, ['BEFORE_TOOL_UNIT', 'AFTER_TOOL_UNIT']), wireItems(baseline, api))
    assert.equal(JSON.stringify(request), snapshot)
  })
})

test('wire Responses: signed standalone reasoning and its assistant output are one indivisible depth unit', async () => {
  const source = { provider: 'local', model: 'world-test', replayState: {
    response: { kind: 'pi-ai', version: 2, api: 'openai-responses', provider: 'local', model: 'world-test', stopReason: 'stop' },
    blocks: [{ type: 'reasoning', thinkingSignature: JSON.stringify({ type: 'reasoning', id: 'rs_standalone', summary: [], encrypted_content: 'STANDALONE_ENCRYPTED' }) }, { type: 'text' }],
  } }
  const request = options([createSystemMessage(SYSTEM), user('BEFORE_REASONING_USER'),
    createAssistantMessage({ source, content: [{ type: 'reasoning', text: 'STANDALONE_REASONING' }, ...text('REASONED_ASSISTANT')] }), user('AFTER_REASONING_USER')])
  await harness('openai-responses', { model: { reasoningEfforts: { off: null, low: 'low' } },
    entries: [entry('BEFORE_REASONING_UNIT', 'system', 2), entry('AFTER_REASONING_UNIT', 'user', 1)] }, async ({ ctx, wires }) => {
    assertSuccess(await collect(ctx.llm.stream(request)))
    const items = wires[0].input
    const indices = ['BEFORE_REASONING_UNIT', 'STANDALONE_ENCRYPTED', 'REASONED_ASSISTANT', 'AFTER_REASONING_UNIT', 'AFTER_REASONING_USER'].map(needle => items.findIndex(item => contains(item, needle)))
    assert.ok(indices.every(index => index >= 0))
    assert.deepEqual(indices, [...indices].sort((a, b) => a - b), 'world entries must surround the complete reasoning/output unit')
    assert.equal(indices[2], indices[1] + 1, 'no world injection may split reasoning from the matching assistant output')
  })
})

test('pure payload Responses: a final assistant answer after tool output is its own depth unit', () => {
  const items = [
    { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'FIRST' }] },
    { type: 'reasoning', id: 'rs_1', summary: [], encrypted_content: 'ENC_1' },
    { type: 'function_call', call_id: 'call_1', name: 'probe', arguments: '{}' },
    { type: 'function_call_output', call_id: 'call_1', output: 'RESULT_1' },
    { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'FINAL_ANSWER' }] },
    { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'SECOND' }] },
  ]
  const out = projectWorldPayload({ input: items }, { api: 'openai-responses' },
    [entry('BEFORE_FINAL', 'user', 2), entry('BEFORE_LAST', 'user', 1)])
  const wire = out.input, index = needle => wire.findIndex(item => JSON.stringify(item).includes(needle))
  const [beforeFinal, result, final, beforeLast, second] =
    ['BEFORE_FINAL', 'RESULT_1', 'FINAL_ANSWER', 'BEFORE_LAST', 'SECOND'].map(index)
  assert.ok([beforeFinal, result, final, beforeLast, second].every(position => position >= 0))
  assert.ok(beforeFinal > result && beforeFinal < final, 'depth 2 lands before the later assistant answer')
  assert.ok(beforeLast > final && beforeLast < second, 'depth 1 still counts the final answer as a unit')
  assert.deepEqual(items.map(item => item.type),
    ['message', 'reasoning', 'function_call', 'function_call_output', 'message', 'message'])
})

for (const wrongSession of ['session-2', undefined, 'session-1 ']) test(`identity boundary: host active plan session ${String(wrongSession)} cannot reach provider`, async () => {
  await harness('openai-completions', { bridge: () => bridgeResponse(plan([entry('FOREIGN_WORLD')], { session_id: wrongSession })) }, async ({ ctx, wires }) => {
    const chunks = await collect(ctx.llm.stream(options()))
    assert.ok(['WORLD_REQUEST_IDENTITY_REQUIRED', 'WORLD_INVALID_PLAN'].includes(failureOf(chunks)?.code))
    assert.equal(wires.length, 0)
  })
})

test('pure payload: frozen wire/history are unchanged; unrelated fields retain identity', () => {
  const original = deepFreeze({ messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: 'U' }], tools: [{ name: 'probe' }], arbitrary: { retained: true } })
  const snapshot = JSON.stringify(original)
  const projected = projectWorldPayload(original, { api: 'openai-completions' }, [entry('WORLD')])
  assert.equal(JSON.stringify(original), snapshot)
  assert.notEqual(projected.messages, original.messages)
  assert.equal(projected.messages[0], original.messages[0])
  assert.equal(projected.tools, original.tools)
  assert.equal(projected.arbitrary, original.arbitrary)
})
for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages']) test(`pure payload ${api}: dangling or unmatched tool results fail without projection`, () => {
  const model = { api }, key = api === 'openai-responses' ? 'input' : 'messages'
  const dangling = api === 'openai-completions' ? [{ role: 'assistant', tool_calls: [{ id: 'call_missing' }] }] :
    api === 'openai-responses' ? [{ type: 'function_call', call_id: 'call_missing' }] : [{ role: 'assistant', content: [{ type: 'tool_use', id: 'call_missing' }] }]
  const orphan = api === 'openai-completions' ? [{ role: 'tool', tool_call_id: 'orphan' }] :
    api === 'openai-responses' ? [{ type: 'function_call_output', call_id: 'orphan' }] : [{ role: 'user', content: [{ type: 'tool_result', tool_use_id: 'orphan' }] }]
  for (const items of [dangling, orphan]) assert.throws(() => projectWorldPayload(deepFreeze({ [key]: items }), model, [entry('WORLD', 'user')]), error => error.code === 'WORLD_TOOL_BOUNDARY')
})

test('identity: same-session unmarked and copied-marker helper requests perform zero bridge/injection', async () => {
  await harness('openai-completions', { entries: [entry('WORLD_MAIN')] }, async ({ ctx, wires, bridge }) => {
    const marked = options(), helper = deepFreeze({ ...marked })
    assert.equal(isAgentLoopRequest(helper), false)
    for (const request of [options(undefined, {}, false), helper]) assertSuccess(await collect(ctx.llm.stream(request)))
    assert.equal(bridge.length, 0)
    assert.ok(wires.every(wire => !contains(wire, 'WORLD_MAIN')))
    assertSuccess(await collect(ctx.llm.stream(marked)))
    assert.equal(bridge.length, 1)
    assert.ok(contains(wires.at(-1), 'WORLD_MAIN'))
  })
})
test('identity: nested unmarked same-session helper inside marked adapter does not inherit AsyncLocalStorage', async () => {
  const helper = options([createSystemMessage(SYSTEM), user('HELPER_REQUEST')], {}, false)
  await harness('openai-completions', { entries: [entry('WORLD_MAIN')], auxiliary: async ({ ctx }) => assertSuccess(await collect(ctx.llm.stream(helper))) }, async ({ ctx, wires, bridge }) => {
    assertSuccess(await collect(ctx.llm.stream(options())))
    assert.equal(bridge.length, 1)
    assert.equal(wires.length, 2)
    assert.ok(contains(wires[0], 'HELPER_REQUEST'))
    assert.ok(!contains(wires[0], 'WORLD_MAIN'))
    assert.ok(contains(wires[1], 'WORLD_MAIN'))
  })
})
test('production identity: marked daily request without lifecycle binding performs zero bridge/injection', async () => {
  await harness('openai-completions', { plugin: true, entries: [entry('WORLD_BOUND_ONLY')] }, async ({ ctx, wires, bridge }) => {
    assertSuccess(await collect(ctx.llm.stream(options())))
    assert.equal(bridge.length, 0)
    assert.equal(wires.length, 1)
    assert.ok(!contains(wires[0], 'WORLD_BOUND_ONLY'))
  })
})
test('production identity: lifecycle binding survives option cloning, while unmarked nested helpers remain clean', async () => {
  const helper = options([createSystemMessage(SYSTEM), user('HELPER_BOUND_REQUEST')], {}, false)
  await harness('openai-completions', { plugin: true, entries: [entry('WORLD_BOUND')], auxiliary: async ({ ctx }) => assertSuccess(await collect(ctx.llm.stream(helper))) }, async ({ ctx, wires, bridge, bindings, projected }) => {
    const request = options()
    bindings.set('session-1', { sessionId: 'session-1', trigger: request.messages[1].id, turn: 7, requestId: 'request-1' })
    assertSuccess(await collect(ctx.llm.stream(request)))
    assert.equal(bridge.length, 1)
    assert.equal(bridge[0].trigger_message_id, request.messages[1].id)
    assert.equal(bridge[0].request_id, 'request-1')
    assert.equal(bridge[0].turn, 7)
    assert.notEqual(projected.at(-1).options, request)
    assert.ok(!contains(wires[0], 'WORLD_BOUND'))
    assert.ok(contains(wires[1], 'WORLD_BOUND'))
  })
})
test('production identity: reply with mismatched request identity is rejected before provider dispatch', async () => {
  await harness('openai-completions', { plugin: true, bridge: () => bridgeResponse(plan([entry('WORLD_FOREIGN')], { request_id: 'foreign-request' })) }, async ({ ctx, wires, bindings }) => {
    const request = options()
    bindings.set('session-1', { sessionId: 'session-1', trigger: request.messages[1].id, requestId: 'request-1', turn: 1 })
    assert.equal(failureOf(await collect(ctx.llm.stream(request)))?.code, 'WORLD_REQUEST_IDENTITY_REQUIRED')
    assert.equal(wires.length, 0)
  })
})

test('identity: real user trigger survives later runtime-context and tool continuation', async () => {
  const trigger = user('USER_TRIGGER')
  const messages = [createSystemMessage(SYSTEM), trigger, synthetic('NOT_A_USER_TRIGGER'),
    createAssistantMessage({ source: { provider: 'local', model: 'world-test' }, content: [{ type: 'tool-call', id: 'continued_call', name: 'local_probe', arguments: '{}' }] }),
    createToolResultMessage({ callId: 'continued_call', content: text('CONTINUED_RESULT') })]
  await harness('openai-completions', { entries: [entry('WORLD_MAIN')] }, async ({ ctx, bridge }) => {
    assertSuccess(await collect(ctx.llm.stream(options(messages.slice(0, 3)))))
    assertSuccess(await collect(ctx.llm.stream(options(messages))))
    assert.deepEqual(bridge.map(request => request.trigger_message_id), [trigger.id, trigger.id])
  })
})
test('identity: marked request with only synthetic user context fails closed with no bridge or provider I/O', async () => {
  await harness('openai-completions', {}, async ({ ctx, bridge, wires }) => {
    const chunks = await collect(ctx.llm.stream(options([createSystemMessage(SYSTEM), synthetic('PRIVATE_SYNTHETIC')])))
    assert.equal(failureOf(chunks)?.code, 'WORLD_REQUEST_IDENTITY_REQUIRED')
    assert.equal(bridge.length, 0)
    assert.equal(wires.length, 0)
  })
})
test('identity: concurrent sessions keep their own bridge trigger and final wire injection', async () => {
  let aWaiting, releaseA
  const waiting = new Promise(resolve => { aWaiting = resolve })
  const released = new Promise(resolve => { releaseA = resolve })
  await harness('openai-completions', { bridge: async request => {
    if (request.dsh_session_id === 'session-A') { aWaiting(); await released }
    else releaseA()
    return bridgeResponse(plan([entry(`WORLD_${request.dsh_session_id}`)], { session_id: request.dsh_session_id }))
  } }, async ({ ctx, wires, bridge }) => {
    const a = options([createSystemMessage(SYSTEM), user('TRIGGER_A')], { sessionId: 'session-A' })
    const b = options([createSystemMessage(SYSTEM), user('TRIGGER_B')], { sessionId: 'session-B' })
    const first = collect(ctx.llm.stream(a))
    await waiting
    const [chunksA, chunksB] = await Promise.all([first, collect(ctx.llm.stream(b))])
    assertSuccess(chunksA); assertSuccess(chunksB)
    assert.deepEqual(bridge.map(request => [request.dsh_session_id, request.trigger_message_id]), [['session-A', a.messages[1].id], ['session-B', b.messages[1].id]])
    for (const wire of wires) {
      assert.notEqual(contains(wire, 'WORLD_session-A'), contains(wire, 'WORLD_session-B'))
      assert.equal(contains(wire, 'TRIGGER_A'), contains(wire, 'WORLD_session-A'))
    }
  })
})
test('retry: repeated dispatch of original frozen request never accumulates injections', async () => {
  await harness('openai-completions', { entries: [entry('WORLD_ONCE')], respond: (_request, index) => index === 1 ?
    new Response(JSON.stringify({ error: { message: 'LOCAL_FAILURE' } }), { status: 400, headers: { 'content-type': 'application/json' } }) : completionResponse() }, async ({ ctx, wires, bridge }) => {
    const request = options(), snapshot = JSON.stringify(request)
    assert.ok(failureOf(await collect(ctx.llm.stream(request))))
    assertSuccess(await collect(ctx.llm.stream(request)))
    assert.equal(wires.length, 2)
    assert.deepEqual(wires[0], wires[1])
    assert.equal(wires[1].messages.filter(message => contains(message, 'WORLD_ONCE')).length, 1)
    assert.equal(bridge.length, 2)
    assert.equal(JSON.stringify(request), snapshot)
  })
})

async function mountAgentPipeline(ctx, fibers) {
  for (const name of ['@deepseek-ai/dsh-session', '@deepseek-ai/dsh-session-projection', '@deepseek-ai/dsh-agent']) fibers.push(await ctx.plugin((await cached(name)).default))
  fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-system-prompt')).default, {
    includeHarnessIdentity: false, includeRuntimeContext: false, personaPrefix: SYSTEM,
  }))
  fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-tools')).default))
  fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-agent-loop')).default, { agents: [], maxParallelToolCalls: 2 }))
}
test('AgentLoop: real session pipeline marks both tool steps, retains one real user trigger, leaves durable history injection-free', { timeout: 15000 }, async () => {
  await harness('openai-completions', { plugin: true, entries: [entry('WORLD_LOOP')], respond: (_request, index) => completionResponse(index === 1) }, async ({ ctx, fibers, bridge, wires, markers }) => {
    await mountAgentPipeline(ctx, fibers)
    let toolsExecuted = 0
    ctx.tools.register((await cached('@deepseek-ai/dsh-tools')).defineContentToolFixture({ name: 'local_probe', description: 'Local fixture only', parameters: {},
      execute: async () => { toolsExecuted++; return text('LOCAL_TOOL_RESULT') } }))
    const agent = await ctx.agentLoop.create('session-1', { provider: 'local', model: 'world-test' })
    const errors = []
    ctx.on('agent/error', ({ error }) => { errors.push({ code: error.code, message: error.message }) })
    const trigger = user('ACTUAL_LOOP_TRIGGER')
    agent.followup(trigger)
    await agent.whenIdle()
    assert.deepEqual(errors, [])
    assert.equal(toolsExecuted, 1)
    assert.equal(wires.length, 2)
    assert.deepEqual(markers, [true, true])
    assert.deepEqual(bridge.map(request => request.trigger_message_id), [trigger.id, trigger.id])
    assert.ok(wires.every(wire => wire.messages.filter(message => contains(message, 'WORLD_LOOP')).length === 1))
    assert.ok(contains(wires[1], 'LOCAL_TOOL_RESULT'))
    assert.ok(!contains(agent.session.deriveMessages(), 'WORLD_LOOP'))
    assert.ok(agent.session.deriveMessages().some(message => message.id === trigger.id))
  })
})

for (const production of [false, true]) test(`AgentLoop compaction ${production ? 'production lifecycle' : 'captureRequest fallback'}: second tool step retains original trigger after history replacement`, { timeout: 15000 }, async () => {
  await harness('openai-completions', { plugin: production, entries: [entry('WORLD_COMPACT')], respond: (_request, index) => completionResponse(index === 1) }, async ({ ctx, fibers, bridge, wires }) => {
    await mountAgentPipeline(ctx, fibers)
    ctx.tools.register((await cached('@deepseek-ai/dsh-tools')).defineContentToolFixture({ name: 'local_probe', description: 'Local fixture only', parameters: {},
      execute: async () => text('LOCAL_TOOL_RESULT') }))
    const agent = await ctx.agentLoop.create('session-1', { provider: 'local', model: 'world-test' })
    const errors = []
    ctx.on('agent/error', ({ error }) => { errors.push({ code: error.code, message: error.message }) })
    const trigger = user('ACTUAL_COMPACTION_TRIGGER')
    let compacted = false
    ctx.on('agent/request', async (request, next) => {
      if (request.agent === agent && request.step === 2 && !compacted) {
        const session = agent.session
        const original = session.surface.nodes.map(seq => session.eventAt(seq)).find(event => event.type === 'user/message' && event.data.id === trigger.id)
        assert.ok(original, 'real first-step user message must be committed before compaction')
        const compactionId = 'compaction-local-test'
        const lifecycle = { compactionId, turn: request.turn }
        const start = session.append('compaction/start', lifecycle)
        const summary = session.append('compaction/summary', { compactionId, summary: 'LOCAL_COMPACT_SUMMARY', shadowedRange: { start: original.seq, end: original.seq },
          shadowedSeqs: [original.seq], shadowedTokenCount: 100, provider: 'local', model: 'world-test' })
        const checkpoint = createUserMessage({ content: text('LOCAL_COMPACT_SUMMARY'), source: (await cached('@deepseek-ai/dsh-compaction')).compactCheckpointSource(compactionId) })
        session.append('user/message', checkpoint, { surfaceOp: { op: 'replace', startSeq: original.seq, endSeq: original.seq }, sourceEventSeqs: [start.seq, summary.seq, original.seq] })
        session.append('compaction/end', lifecycle)
        compacted = true
        assert.ok(!session.deriveMessages().some(message => message.source?.kind === 'user'), 'current wire history no longer contains a real user')
      }
      return next()
    })
    agent.followup(trigger)
    await agent.whenIdle()
    assert.deepEqual(errors, [])
    assert.equal(compacted, true)
    assert.equal(wires.length, 2, 'compaction must not suppress the second real provider step')
    assert.deepEqual(bridge.map(request => request.trigger_message_id), [trigger.id, trigger.id])
    assert.ok(contains(wires[1], 'LOCAL_COMPACT_SUMMARY'))
    assert.ok(!contains(wires[1], 'ACTUAL_COMPACTION_TRIGGER'))
    assert.ok(contains(wires[1], 'WORLD_COMPACT'))
  })
})

for (const [name, bridge, expected] of [
  ['HTTP error', async () => new Response(JSON.stringify({ ok: false, body: 'PRIVATE_BRIDGE_BODY' }), { status: 500 }), 'WORLD_HOST_FAILED'],
  ['malformed JSON', async () => new Response('PRIVATE_BRIDGE_BODY', { status: 200 }), 'WORLD_TRANSPORT_FAILED'],
  ['transport exception', async () => { throw new Error('PRIVATE_BRIDGE_BODY') }, 'WORLD_TRANSPORT_FAILED'],
]) test(`safety: bridge ${name} fails safely without provider I/O or private body disclosure`, async () => {
  await harness('openai-completions', { bridge }, async ({ ctx, wires }) => {
    const chunks = await collect(ctx.llm.stream(options()))
    assert.equal(failureOf(chunks)?.code, expected)
    assert.ok(!contains(chunks, 'PRIVATE_BRIDGE_BODY'))
    assert.equal(wires.length, 0)
  })
})
test('safety: abort while waiting for bridge fails safely and never reaches provider', async () => {
  const controller = new AbortController()
  await harness('openai-completions', { bridge: async (_request, init) => {
    controller.abort(new Error('PRIVATE_ABORT_BODY'))
    init.signal.throwIfAborted()
  } }, async ({ ctx, wires }) => {
    const chunks = await collect(ctx.llm.stream(options(undefined, { signal: controller.signal })))
    assert.equal(failureOf(chunks)?.code, 'ABORTED')
    assert.ok(!contains(chunks, 'PRIVATE_ABORT_BODY'))
    assert.equal(wires.length, 0)
  })
})
test('safety baseline: unpatched cached provider already exposes upstream HTTP error message', async () => {
  await harness('openai-completions', { originalAdapter: true, respond: () => new Response(JSON.stringify({ error: { message: 'BASELINE_PROVIDER_BODY' } }), {
    status: 400, headers: { 'content-type': 'application/json' },
  }) }, async ({ ctx, bridge }) => {
    const chunks = await collect(ctx.llm.stream(options()))
    assert.ok(contains(failureOf(chunks), 'BASELINE_PROVIDER_BODY'), 'record the existing upstream behavior separately from the world seam')
    assert.equal(bridge.length, 0, 'unpatched adapter never invokes world projection')
  })
})
test('safety baseline: activated world request redacts provider rejection while preserving its routing code', async () => {
  const outcomes = []
  for (const originalAdapter of [true, false]) await harness('openai-completions', { originalAdapter, entries: [entry('PRIVATE_WORLD_BODY')], respond: () => new Response(JSON.stringify({ error: { message: 'PRIVATE_PROVIDER_BODY' } }), {
    status: 400, headers: { 'content-type': 'application/json' },
  }) }, async ({ ctx }) => {
    const chunks = await collect(ctx.llm.stream(options()))
    assert.ok(failureOf(chunks))
    assert.equal(contains(failureOf(chunks), 'PRIVATE_PROVIDER_BODY'), originalAdapter)
    assert.ok(!contains(chunks, 'PRIVATE_WORLD_BODY'))
    outcomes.push(failureOf(chunks))
  })
  assert.equal(outcomes[0].code, outcomes[1].code)
  assert.equal(outcomes[1].message, outcomes[1].code)
})

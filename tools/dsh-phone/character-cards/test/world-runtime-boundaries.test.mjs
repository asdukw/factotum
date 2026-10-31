import assert from 'node:assert/strict'
import { test, after } from 'node:test'
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises'
import { dirname, resolve, join } from 'node:path'
import { tmpdir } from 'node:os'
import { pathToFileURL } from 'node:url'
import { patchWorldRuntime } from '../../world-runtime-patch.mjs'
import { createWorldRuntime } from '../world-runtime.mjs'

// Independent boundary regressions. Both adapter variants use the original cached
// SDKs and real LlmRuntime; only disposable source copies are written.
const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
const cached = name => import(pathToFileURL(require.resolve(name)).href)
const llm = await cached('@deepseek-ai/dsh-llm')
const { Context } = await cached('@deepseek-ai/cordis')
const sourcePath = require.resolve('@deepseek-ai/dsh-llm-pi-ai')
const source = await readFile(sourcePath, 'utf8')
const scratch = await mkdtemp(join(tmpdir(), 'zoneu-world-boundaries-'))
after(() => rm(scratch, { force: true, recursive: true }))
const imports = code => code.replace(/from "([^"\n]+)"/g, (whole, specifier) => {
  if (specifier.startsWith('node:')) return whole
  const target = specifier.startsWith('@earendil-works/pi-ai/')
    ? resolve(dirname(sourcePath), '../../../@earendil-works/pi-ai/dist', specifier.slice('@earendil-works/pi-ai/'.length) + '.js')
    : specifier
  return `from ${JSON.stringify(pathToFileURL(require.resolve(target)).href)}`
})
const modules = {}
for (const [name, code] of [['original', source], ['patched', patchWorldRuntime(source)]]) {
  const path = join(scratch, name + '.mjs')
  await writeFile(path, imports(code) + '\nexport { resolveProfiles };\n')
  modules[name] = await import(pathToFileURL(path).href)
}
const text = value => [{ type: 'text', text: value }]
const trigger = llm.createUserMessage({ source: { kind: 'user' }, content: text('LOCAL_TRIGGER') })
const checkpoint = llm.createUserMessage({ source: { kind: 'compact-checkpoint', compactionId: 'c1' }, content: text('LOCAL_CHECKPOINT') })
const request = (messages = [trigger], extra = {}, mark = true) => {
  const options = Object.freeze({ provider: 'local', model: 'world-test', sessionId: 'session-1', messages: Object.freeze(messages), ...extra })
  return mark ? llm.markAgentLoopRequest(options) : options
}
const plan = (overrides = {}) => ({ kind: 'zoneu.world.request.v1', active: true, session_id: 'session-1',
  request_id: 'request-1', binding_revision: 'revision-1', injections: [
    { entry_id: 'boundary', position: 'depth', role: 'user', depth: 0, content: 'WORLD_BOUNDARY' },
  ], ...overrides })
const json = value => new Response(JSON.stringify({ ok: true, payload: JSON.stringify(value) }), { headers: { 'content-type': 'application/json' } })
const collect = async stream => { const chunks = []; for await (const chunk of stream) chunks.push(chunk); return chunks }
const failure = chunks => chunks.find(chunk => chunk.type === 'finish' && ['error', 'aborted'].includes(chunk.reason.kind))?.reason.failure
const contains = (value, sentinel) => JSON.stringify(value).includes(sentinel)
const completedResponse = () => new Response([
  { choices: [{ index: 0, delta: { role: 'assistant', content: 'LOCAL_OK' }, finish_reason: null }] },
  { choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] },
].map(chunk => 'data: ' + JSON.stringify(chunk) + '\n\n').join('') + 'data: [DONE]\n\n', {
  headers: { 'content-type': 'text/event-stream' },
})

async function fixture({ api = 'openai-completions', variant = 'patched', runtimeOptions, bridgeReply, providerReply } = {}, body) {
  const ctx = new Context(), wires = [], bridges = [], originalFetch = globalThis.fetch, fibers = []
  const runtime = createWorldRuntime({ bridgeUrl: () => 'http://127.0.0.1:1', bridgeToken: () => 'fake-boundary-token',
    transport: async (_url, init) => {
      const body = JSON.parse(init.body)
      bridges.push(body)
      return bridgeReply ? bridgeReply(body, init) : json(plan())
    }, ...runtimeOptions })
  globalThis.fetch = async (url, init) => {
    assert.ok(String(url).startsWith('http://127.0.0.1:1/'), 'test must not make an external network request')
    wires.push(JSON.parse(init.body))
    return providerReply ? providerReply(wires.at(-1), init) : completedResponse()
  }
  try {
    fibers.push(await ctx.plugin(llm.LlmRuntime))
    ctx.on('llm/stream', (options, next) => runtime.stream(options, next))
    const { PiAiAdapter, Config, resolveProfiles } = modules[variant]
    const profiles = resolveProfiles(Config({ providers: { local: { api, baseURL: 'http://127.0.0.1:1/v1',
      models: [{ id: 'world-test', maxTokens: 32 }], transport: 'sse', cacheRetention: 'none' } } }).providers.get())
    const adapter = new PiAiAdapter({ profiles: () => profiles, resolveApiKey: async () => 'fake-boundary-key',
      projectRequest: input => runtime.project(input) })
    ctx.llm.registerAdapter(['local'], adapter)
    await body({ ctx, runtime, wires, bridges, fibers })
  } finally {
    for (const fiber of fibers.reverse()) await fiber.dispose()
    globalThis.fetch = originalFetch
  }
}

for (const api of ['openai-completions', 'openai-responses', 'anthropic-messages']) {
  test(`HTTP-error provenance ${api}: only the unpatched adapter exposes provider message when a world plan is active`, async () => {
    const outcomes = []
    for (const variant of ['original', 'patched']) await fixture({ api, variant,
      providerReply: () => new Response(JSON.stringify({ error: { message: 'SYNTHETIC_PRIVATE_ERROR_BODY', type: 'invalid_request_error' } }), {
        status: 400, headers: { 'content-type': 'application/json' },
      }) }, async ({ ctx, wires, bridges }) => {
      const chunks = await collect(ctx.llm.stream(request()))
      const error = failure(chunks)
      assert.ok(error)
      assert.equal(contains(error, 'SYNTHETIC_PRIVATE_ERROR_BODY'), variant === 'original')
      assert.equal(wires.length, 1)
      assert.equal(bridges.length, variant === 'original' ? 0 : 1)
      outcomes.push(error)
    })
    assert.equal(outcomes[0].code, outcomes[1].code, 'redaction preserves provider-neutral routing')
    assert.equal(outcomes[1].message, outcomes[1].code)
  })
}

test('privacy contract: provider HTTP error body must not escape terminal failure metadata', async () => {
  await fixture({ providerReply: () => new Response(JSON.stringify({ error: { message: 'PRIVATE_HTTP_CONTRACT_SENTINEL' } }), {
    status: 400, headers: { 'content-type': 'application/json' },
  }) }, async ({ ctx }) => {
    const chunks = await collect(ctx.llm.stream(request()))
    assert.ok(failure(chunks), 'HTTP error must remain an explicit failure')
    assert.ok(!contains(chunks, 'PRIVATE_HTTP_CONTRACT_SENTINEL'), 'upstream baseline comparison does not satisfy the no-body-disclosure contract')
    assert.ok(!contains(chunks, 'WORLD_BOUNDARY'))
  })
})

test('safe cancellation: pre-aborted caller reaches neither bridge nor provider and does not expose caller reason', async () => {
  const controller = new AbortController()
  controller.abort(new Error('PRIVATE_PREABORT_SENTINEL'))
  await fixture({}, async ({ ctx, bridges, wires }) => {
    const chunks = await collect(ctx.llm.stream(request(undefined, { signal: controller.signal })))
    assert.equal(failure(chunks)?.code, 'ABORTED')
    assert.equal(bridges.length, 0)
    assert.equal(wires.length, 0)
    assert.ok(!contains(chunks, 'PRIVATE_PREABORT_SENTINEL'))
  })
})

test('safe cancellation: cancellation after bridge JSON parsing is sanitized and prevents provider I/O', async () => {
  const controller = new AbortController()
  await fixture({ bridgeReply: () => ({ ok: true, json: async () => {
    controller.abort(new Error('PRIVATE_POSTBRIDGE_SENTINEL'))
    return { ok: true, payload: JSON.stringify(plan()) }
  } }) }, async ({ ctx, bridges, wires }) => {
    const chunks = await collect(ctx.llm.stream(request(undefined, { signal: controller.signal })))
    assert.equal(failure(chunks)?.code, 'ABORTED')
    assert.equal(bridges.length, 1)
    assert.equal(wires.length, 0)
    assert.ok(!contains(chunks, 'PRIVATE_POSTBRIDGE_SENTINEL'))
  })
})

test('identityFor: stable lifecycle identity survives compacted history and LlmRuntime cloning', async () => {
  const identity = Object.freeze({ sessionId: 'session-1', trigger: trigger.id, turn: 7, requestId: 'stable-request-7' })
  const observed = []
  await fixture({ runtimeOptions: { identityFor: id => { observed.push(id); return identity } },
    bridgeReply: () => json(plan({ request_id: identity.requestId })) }, async ({ ctx, bridges, wires }) => {
    const options = request([checkpoint]), before = JSON.stringify(options)
    const chunks = await collect(ctx.llm.stream(options))
    assert.equal(failure(chunks), undefined)
    assert.ok(chunks.some(chunk => chunk.type === 'finish'))
    assert.equal(bridges.length, 1)
    assert.deepEqual([bridges[0].trigger_message_id, bridges[0].request_id, bridges[0].turn], [trigger.id, identity.requestId, 7])
    assert.equal(wires.length, 1)
    assert.ok(contains(wires[0], 'LOCAL_CHECKPOINT'))
    assert.ok(contains(wires[0], 'WORLD_BOUNDARY'))
    assert.equal(JSON.stringify(options), before)
    assert.deepEqual(observed, ['session-1'])
  })
})

test('identityFor: unmarked helper never consults lifecycle identity or reads world plan', async () => {
  await fixture({ runtimeOptions: { identityFor: () => assert.fail('auxiliary call must not access world lifecycle identity') } }, async ({ ctx, bridges, wires }) => {
    const chunks = await collect(ctx.llm.stream(request(undefined, {}, false)))
    assert.equal(failure(chunks), undefined)
    assert.equal(bridges.length, 0)
    assert.equal(wires.length, 1)
    assert.ok(!contains(wires[0], 'WORLD_BOUNDARY'))
  })
})

test('identityFor: mismatched lifecycle request ID is rejected before provider wire', async () => {
  await fixture({ runtimeOptions: { identityFor: () => ({ sessionId: 'session-1', trigger: trigger.id, turn: 3, requestId: 'expected-request' }) },
    bridgeReply: () => json(plan({ request_id: 'stale-request' })) }, async ({ ctx, wires }) => {
    const chunks = await collect(ctx.llm.stream(request()))
    assert.equal(failure(chunks)?.code, 'WORLD_REQUEST_IDENTITY_REQUIRED')
    assert.equal(wires.length, 0)
  })
})

test('identity boundary: valid-looking host plan for another session must be rejected before injection', async () => {
  await fixture({ bridgeReply: () => json(plan({ session_id: 'session-other' })) }, async ({ ctx, wires }) => {
    const chunks = await collect(ctx.llm.stream(request()))
    assert.equal(failure(chunks)?.code, 'WORLD_REQUEST_IDENTITY_REQUIRED', 'do not inject world content belonging to another session')
    assert.equal(wires.length, 0)
  })
})

test('AgentLoop retry: actual request-error recovery keeps world injection once and same trigger across both attempts', { timeout: 15000 }, async () => {
  let providerAttempts = 0
  await fixture({ providerReply: (_wire, _init) => {
    providerAttempts++
    if (providerAttempts === 1) return new Response(JSON.stringify({ error: { message: 'SYNTHETIC_RETRYABLE' } }), { status: 400, headers: { 'content-type': 'application/json' } })
    return completedResponse()
  } }, async ({ ctx, bridges, wires, fibers }) => {
    for (const name of ['dsh-session', 'dsh-session-projection', 'dsh-agent']) fibers.push(await ctx.plugin((await cached('@deepseek-ai/' + name)).default))
    fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-system-prompt')).default, { includeHarnessIdentity: false, includeRuntimeContext: false }))
    fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-tools')).default))
    fibers.push(await ctx.plugin((await cached('@deepseek-ai/dsh-agent-loop')).default, { agents: [], maxParallelToolCalls: 1 }))
    const errors = [], attempts = []
    ctx.on('agent/error', payload => errors.push(payload.error?.code ?? 'UNKNOWN'))
    ctx.on('agent/request-error', (payload, next) => {
      attempts.push([payload.turn, payload.step])
      return providerAttempts === 1 ? { kind: 'retry' } : next()
    })
    const agent = await ctx.agentLoop.create('session-1', { provider: 'local', model: 'world-test' })
    agent.followup(trigger)
    await agent.whenIdle()
    assert.deepEqual(errors, [])
    assert.equal(providerAttempts, 2)
    assert.deepEqual(attempts, [[1, 1]])
    assert.equal(wires.length, 2)
    assert.deepEqual(wires[0], wires[1])
    assert.deepEqual(bridges.map(body => body.trigger_message_id), [trigger.id, trigger.id])
    assert.ok(wires.every(body => body.messages.filter(message => contains(message, 'WORLD_BOUNDARY')).length === 1))
    assert.ok(!contains(agent.session.deriveMessages(), 'WORLD_BOUNDARY'))
  })
})

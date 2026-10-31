import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { createRequire } from 'node:module'
import { readFile, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { patchWorldLifecycle } from '../../world-lifecycle-patch.mjs'
import * as lifecyclePlugin from '../world-lifecycle.mjs'
const { createWorldLifecycle, bindWorldRequest } = lifecyclePlugin

// Vendor bundles remain READ ONLY. The actual SDK source is transformed in
// memory, with its bare imports resolved against the pinned runtime installation.
const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
const cached = name => import(pathToFileURL(require.resolve(name)).href)
const { Context } = await cached('@deepseek-ai/cordis')
const { LlmRuntime, LlmAdapter, createUserMessage, isAgentLoopRequest } = await cached('@deepseek-ai/dsh-llm')
const { AgentRegistry, agentEvents } = await cached('@deepseek-ai/dsh-agent')
const { SessionStore } = await cached('@deepseek-ai/dsh-session')
const { SessionProjectionRegistry } = await cached('@deepseek-ai/dsh-session-projection')
const { SessionQueryEngine } = await cached('@deepseek-ai/dsh-session-query')
const { SystemPrompt } = await cached('@deepseek-ai/dsh-system-prompt')
const { ToolRuntime } = await cached('@deepseek-ai/dsh-tools')
const { AgentLoop } = await cached('@deepseek-ai/dsh-agent-loop')
const fixtureModules = await mkdtemp(join(tmpdir(), 'zoneu-lifecycle-modules-'))
after(() => rm(fixtureModules, { recursive: true, force: true }))
const absoluteImports = source => source.replace(/from "([^".][^"]*)"/g, (_all, specifier) =>
  `from ${JSON.stringify(specifier.startsWith('node:') ? specifier : pathToFileURL(require.resolve(specifier)).href)}`)
const jsonlPath = require.resolve('@deepseek-ai/dsh-session-persistence-jsonl')
const jsonlSource = await readFile(jsonlPath, 'utf8')
// The Android-only cache has no Windows Koffi binary. In this disposable copy
// ONLY, substitute the native semaphore and write-through namespace rename.
// JSONL encoding, event validation, buffering, fsync, read and the loop's real
// persistence integration remain vendor code. This is NOT a Windows native
// lock/crash-durable-directory test, nor an Android device durability test.
const nativeFixture = `
// The pristine vendor tree imports no rename; the Android write-through patch adds it.
// Declare it here so these tests do not depend on whether the cache was bundled.
import { rename } from 'node:fs/promises';
const fixtureLocks = new Set();
acquireLockHandleWin32 = async (path) => {
  if (fixtureLocks.has(path)) throw Object.assign(new Error('TEST_BUSY'), { code: 'EBUSY' });
  fixtureLocks.add(path); return path;
};
releaseLockHandleWin32 = async (path) => { fixtureLocks.delete(path); };
publishNewFileWin32 = async (existing, replacement) => { await rename(existing, replacement); };
` 
await writeFile(join(fixtureModules, 'jsonl.mjs'), absoluteImports(jsonlSource) + nativeFixture)
const { default: JsonlPersistence } = await import(pathToFileURL(join(fixtureModules, 'jsonl.mjs')).href)
const serverPath = require.resolve('@deepseek-ai/dsh-sdk-jsonrpc-server')
const raw = await readFile(serverPath, 'utf8')
const patched = patchWorldLifecycle(raw)
const loadable = patched.replace(/from "([^".][^"]*)"/g, (_all, specifier) =>
  `from ${JSON.stringify(specifier.startsWith('node:') ? specifier : pathToFileURL(require.resolve(specifier)).href)}`)
await writeFile(join(fixtureModules, 'server.mjs'), loadable)
const { HarnessSdkJsonRpcServer } = await import(pathToFileURL(join(fixtureModules, 'server.mjs')).href)
const text = value => [{ type: 'text', text: value }]
const user = value => createUserMessage({ source: { kind: 'user' }, content: text(value) })
const control = (op, sessionId, requestId, trigger) => ({ op, sessionId, requestId, trigger })
const deferred = () => Promise.withResolvers()
async function* answer(value = 'LOCAL_ANSWER') {
  yield { type: 'block-start', index: 0, blockType: 'text' }
  yield { type: 'text-delta', index: 0, text: value }
  yield { type: 'block-end', index: 0, block: { type: 'text', text: value } }
  yield { type: 'finish', reason: { kind: 'stop' } }
}
async function* toolCall() {
  const block = { type: 'tool-call', id: 'local-call', name: 'local_probe', arguments: '{}' }
  yield { type: 'block-start', index: 0, blockType: 'tool-call' }
  yield { type: 'tool-call-delta', index: 0, id: block.id, name: block.name, argumentsDelta: '{}' }
  yield { type: 'block-end', index: 0, block }
  yield { type: 'finish', reason: { kind: 'tool-calls' } }
}
async function waitForSignal(signal) {
  if (signal.aborted) throw signal.reason
  await new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))
}

// Full real Cordis+registry+loop+session+query(+JSONL) stack. Only the provider
// and host authorization are local fixtures. No fake Agent or fake query API.
async function harness(options, body) {
  const ctx = new Context(), scratch = await mkdtemp(join(tmpdir(), 'zoneu-lifecycle-'))
  const calls = [], binds = [], notifications = [], handles = []
  let lifecycle, server
  const originalFetch = globalThis.fetch
  globalThis.fetch = async () => { throw new Error('NETWORK_PROHIBITED_IN_LIFECYCLE_TEST') }
  try {
    await ctx.plugin(SessionStore)
    await ctx.plugin(SessionProjectionRegistry)
    await ctx.plugin(AgentRegistry)
    await ctx.plugin(LlmRuntime)
    await ctx.plugin(SystemPrompt, { includeHarnessIdentity: false, includeRuntimeContext: false })
    await ctx.plugin(ToolRuntime, {})
    await ctx.plugin(SessionQueryEngine)
    if (options.persistence !== false) await ctx.plugin(JsonlPersistence, { root: scratch, compression: 'none' })
    await ctx.plugin(AgentLoop, { agents: [], maxParallelToolCalls: 1 })
    lifecycle = createWorldLifecycle(ctx, { bindRequest: async binding => {
      binds.push(binding)
      await options.bindRequest?.(binding, { ctx })
    } })
    const unprovide = ctx.provide('zoneuWorldLifecycle', lifecycle)
    class LocalAdapter extends LlmAdapter {
      async *stream(request) {
        const call = { request, identity: lifecycle.identityFor(request.sessionId), marked: isAgentLoopRequest(request) }
        calls.push(call)
        if (options.stream) yield* options.stream(request, calls.length, { ctx, lifecycle, calls })
        else yield* answer()
      }
    }
    ctx.llm.registerAdapter(['local'], new LocalAdapter())
    server = new HarnessSdkJsonRpcServer(ctx, { notify: (method, params) => notifications.push({ method, params }) })
    await server.handleRequest('initialize', { cwd: scratch, provider: 'local', model: 'fixture' })
    const prompt = (sessionId = 'session-1', requestId = 'request-1') => server.handleRequest('session/prompt', {
      sessionId, contentBlocks: text('SYNTHETIC_TRIGGER'),
      ...(requestId === undefined ? {} : { world_request_id: requestId }),
    })
    const makeAgent = async sessionId => {
      const handle = await ctx.agents.create({ sessionId, agentOptions: { provider: 'local', model: 'fixture' } })
      handles.push(handle)
      return handle.agent
    }
    await body({ ctx, server, lifecycle, calls, binds, notifications, prompt, makeAgent, scratch, unprovide })
  } finally {
    lifecycle?.dispose()
    // This class-level cleanup is not the shutdown RPC (no root exit hook).
    await server?.shutdown()
    for (const handle of handles.reverse()) await handle.dispose()
    await ctx.fiber.dispose()
    globalThis.fetch = originalFetch
    await rm(scratch, { recursive: true, force: true })
  }
}

test('patch: exact cached SDK rc.2 source is transformed once, never written', async () => {
  const metadata = JSON.parse(await readFile(require.resolve('@deepseek-ai/dsh-sdk-jsonrpc-server/package.json'), 'utf8'))
  assert.equal(metadata.version, '0.2.0-rc.2')
  assert.equal(patchWorldLifecycle(patched), patched)
  assert.equal(patched.match(/await zoneuLifecycle\?\.bindPrompt/g)?.length, 1)
  assert.equal(patched.match(/case "zoneu\/world-request"/g)?.length, 1)
  assert.equal(await readFile(serverPath, 'utf8'), raw)
})
const anchors = ['\t\trec.handle.agent.followup(message);', '\t\t\tcase "session/prompt": return this.prompt(params);']
for (const [index, anchor] of anchors.entries()) {
  test(`patch: missing anchor ${index + 1} fails closed`, () => assert.throws(() => patchWorldLifecycle(raw.replace(anchor, 'REMOVED')), /ANCHOR_MISMATCH/))
  test(`patch: duplicate anchor ${index + 1} fails closed`, () => assert.throws(() => patchWorldLifecycle(`${raw}\n${anchor}`), /ANCHOR_MISMATCH/))
}
test('patch: partial or marker-only effects are not mistaken for a complete patch', () => {
  const partial = patched.replace('\t\t\tcase "zoneu/world-request": {', '\t\t\tcase "partial": {')
  assert.throws(() => patchWorldLifecycle(partial), /ANCHOR_MISMATCH/)
  assert.throws(() => patchWorldLifecycle(`${patched}\n${anchors[0]}`), /ANCHOR_MISMATCH/)
  assert.equal(patchWorldLifecycle(`${raw}\n// zoneuWorldLifecycle`), `${patched}\n// zoneuWorldLifecycle`)
})

test('host binding: authenticated exact IDs, safe error codes, no guessed mapping', async () => {
  const binding = { sessionId: 'session-1', messageId: 'trigger-1', requestId: 'request-1' }
  const transport = async (url, init) => {
    assert.equal(url, 'http://127.0.0.1:1/action')
    assert.equal(init.headers['x-android-bridge-token'], 'fixture-token')
    assert.deepEqual(JSON.parse(init.body), { action: 'bind_world_request', request_id: binding.requestId,
      dsh_session_id: binding.sessionId, trigger_message_id: binding.messageId })
    return new Response(JSON.stringify({ ok: true, payload: JSON.stringify({ kind: 'zoneu.world.bind.v1',
      request_id: binding.requestId, dsh_session_id: binding.sessionId, trigger_message_id: binding.messageId }) }))
  }
  const config = { transport, bridgeUrl: () => 'http://127.0.0.1:1', bridgeToken: () => 'fixture-token' }
  await bindWorldRequest(binding, config)
  await assert.rejects(bindWorldRequest(binding, { ...config, transport: async () => new Response(JSON.stringify({ ok: false, message: 'PRIVATE_BODY' })) }), /WORLD_BIND_REJECTED/)
  await assert.rejects(bindWorldRequest(binding, { ...config, transport: async () => new Response(JSON.stringify({ ok: true, payload: { kind: 'zoneu.world.bind.v1', request_id: 'wrong' } })) }), /WORLD_BIND_MISMATCH/)
  await assert.rejects(bindWorldRequest(binding, { ...config, transport: async () => { throw new Error('PRIVATE_BODY') } }), error => error.message === 'WORLD_BIND_TRANSPORT_FAILED')
})

test('real SDK: host authorization and trigger binding happen before first provider call', async () => {
  const entered = deferred(), allow = deferred()
  await harness({ bindRequest: async () => { entered.resolve(); await allow.promise } }, async ({ prompt, calls, binds, ctx, lifecycle }) => {
    const prompting = prompt()
    await entered.promise
    assert.equal(calls.length, 0)
    assert.equal(ctx.agents.get('session-1').status, 'idle')
    assert.equal(lifecycle.identityFor('session-1'), undefined)
    allow.resolve()
    const { messageId } = await prompting
    await ctx.agents.get('session-1').whenIdle()
    assert.deepEqual(binds, [{ sessionId: 'session-1', messageId, requestId: 'request-1' }])
    assert.equal(calls.length, 1)
    assert.equal(calls[0].marked, true)
    assert.deepEqual(calls[0].identity, { sessionId: 'session-1', trigger: messageId, requestId: 'request-1', turn: 1 })
    assert.equal(lifecycle.identityFor('session-1'), undefined)
  })
})

test('real SDK: durable control returns only safe evidence and retry reads the same receipt', async () => {
  await harness({}, async ({ prompt, server, ctx }) => {
    const { messageId } = await prompt()
    const params = control('settle', 'session-1', 'request-1', messageId)
    const result = await server.handleRequest('zoneu/world-request', params)
    assert.equal(result.status, 'settled', JSON.stringify(result))
    assert.equal(result.durable, true)
    assert.deepEqual(Object.keys(result.evidence).sort(), ['assistantSeq', 'endSeq', 'reason', 'requestId', 'sessionId', 'trigger', 'turn'].sort())
    assert.equal(JSON.stringify(result).includes('LOCAL_ANSWER'), false)
    assert.deepEqual(await server.handleRequest('zoneu/world-request', { ...params, op: 'status' }), result)
    const cold = await ctx.sessionPersistence.open('session-1', 'read')
    try {
      const stored = await cold.read(0)
      assert.equal(stored.events[result.evidence.endSeq].type, 'turn/end')
      assert.equal(stored.events[result.evidence.assistantSeq].type, 'assistant/message')
    } finally { await cold.close() }
    assert.equal(ctx.agents.get('session-1').status, 'idle')
    await assert.rejects(server.handleRequest('zoneu/world-request/typo', params), /unknown DeepSeek/)
  })
})

test('real SDK: daily prompts have no binding; world request fails closed when plugin is absent', async () => {
  await harness({}, async ({ server, calls, binds, ctx, prompt, unprovide }) => {
    // Explicit params avoid this helper's default request id.
    await server.handleRequest('session/prompt', { sessionId: 'daily', contentBlocks: text('DAILY') })
    await ctx.agents.get('daily').whenIdle()
    assert.equal(calls[0].identity, undefined)
    assert.equal(binds.length, 0)
    await unprovide()
    await assert.rejects(prompt(), /WORLD_LIFECYCLE_UNAVAILABLE/)
    assert.equal(calls.length, 1)
  })
})

test('real SDK: concurrent or unsettled binding is rejected until explicit settlement', async () => {
  const gate = deferred(), entered = deferred()
  await harness({ bindRequest: async () => { entered.resolve(); await gate.promise } }, async ({ prompt, ctx, server, binds }) => {
    const first = prompt()
    await entered.promise
    await assert.rejects(prompt('session-1', 'request-2'), /WORLD_REQUEST_UNSETTLED/)
    gate.resolve()
    const { messageId } = await first
    await ctx.agents.get('session-1').whenIdle()
    await assert.rejects(prompt('session-1', 'request-2'), /WORLD_REQUEST_UNSETTLED/)
    await assert.rejects(server.handleRequest('session/prompt', { sessionId: 'session-1', contentBlocks: text('DAILY') }), /WORLD_REQUEST_UNSETTLED/)
    await server.handleRequest('zoneu/world-request', control('settle', 'session-1', 'request-1', messageId))
    const next = await prompt('session-1', 'request-2')
    const result = await server.handleRequest('zoneu/world-request', control('settle', 'session-1', 'request-2', next.messageId))
    assert.equal(result.evidence.turn, 2)
    assert.equal(binds.length, 2)
  })
})

test('real SDK: failed host bind never queues; lost response keeps an uncertain reservation', async () => {
  await harness({ bindRequest: async () => { throw new Error('WORLD_BIND_TRANSPORT_FAILED') } }, async ({ prompt, calls, binds, lifecycle, ctx }) => {
    await assert.rejects(prompt(), /WORLD_BIND_TRANSPORT_FAILED/)
    assert.equal(calls.length, 0)
    assert.equal(ctx.agents.get('session-1').inbox.hasPending, false)
    await assert.rejects(prompt('session-1', 'request-2'), /WORLD_REQUEST_UNSETTLED/)
    const cancelled = await lifecycle.control(control('cancel', 'session-1', 'request-1', binds[0].messageId))
    assert.equal(cancelled.status, 'cancelled')
    assert.equal(cancelled.durable, false)
  })
})

test('real SDK: an invalidated record can still be released by explicit cancellation', async () => {
  await harness({
    // Leave a queued message while the host bind is in flight: the post-bind
    // admission check invalidates the reservation instead of authorizing it.
    bindRequest: async (binding, { ctx }) => {
      if (binding.requestId === 'request-1') ctx.agents.get('session-1').inject(user('QUEUED_TURN'))
    },
  }, async ({ prompt, ctx, lifecycle, binds }) => {
    await assert.rejects(prompt(), /WORLD_AGENT_BUSY/)
    const agent = ctx.agents.get('session-1')
    assert.equal(agent.inbox.hasPending, true)
    const status = await lifecycle.control(control('status', 'session-1', 'request-1', binds[0].messageId))
    assert.equal(status.status, 'unknown')
    assert.equal(status.code, 'WORLD_AGENT_UNAVAILABLE')
    const cancelled = await lifecycle.control(control('cancel', 'session-1', 'request-1', binds[0].messageId))
    assert.equal(cancelled.status, 'cancelled')
    assert.equal(cancelled.durable, false)
    assert.equal(cancelled.advance, false)
    assert.equal(cancelled.code, 'WORLD_AGENT_UNAVAILABLE')
    // The reservation is released; the unrelated queued message is not ours to run.
    agent.cancel({ kind: 'user' })
    const next = await prompt('session-1', 'request-2')
    const settled = await lifecycle.control(control('settle', 'session-1', 'request-2', next.messageId))
    assert.equal(settled.advance, true)
  })
})

test('real agent: tool continuation and network retry keep one identity after source user is replaced', async () => {
  const identities = []
  await harness({ stream: async function* (_request, count, { lifecycle }) {
    identities.push(lifecycle.identityFor('session-1'))
    if (count === 1) yield { type: 'finish', reason: { kind: 'error', failure: { code: 'LOCAL_RETRY', message: 'LOCAL_RETRY' } } }
    else if (count === 2) yield* toolCall()
    else yield* answer()
  } }, async ({ ctx, prompt, server, calls }) => {
    ctx.on('agent/request-error', () => ({ kind: 'retry' }))
    ctx.tools.register({ name: 'local_probe', description: 'local fixture', parameters: { type: 'object', properties: {} },
      output: { schema: { type: 'string' }, render: (_args, value) => text(value) },
      execute: async () => 'LOCAL_TOOL_RESULT' })
    let replaced = false
    ctx.on('agent/pre-step', async ({ agent, step }, next) => {
      if (step === 2 && !replaced) {
        const log = await ctx.sessionQuery.readSession(agent.session.id)
        const trigger = log.events.find(event => event.type === 'user/message' && event.data.source.kind === 'user')
        agent.session.append('user/message', createUserMessage({ source: { kind: 'runtime-context' }, content: text('COMPACTED_CONTEXT') }), {
          surfaceOp: { op: 'replace', startSeq: trigger.seq, endSeq: trigger.seq }, sourceEventSeqs: [trigger.seq],
        })
        replaced = true
      }
      return next()
    })
    const { messageId } = await prompt()
    const settled = await server.handleRequest('zoneu/world-request', control('settle', 'session-1', 'request-1', messageId))
    assert.equal(settled.status, 'settled', JSON.stringify(settled))
    assert.equal(replaced, true)
    assert.equal(calls.length, 3)
    assert.deepEqual(identities, Array.from({ length: 3 }, () => ({ sessionId: 'session-1', requestId: 'request-1', trigger: messageId, turn: 1 })))
    assert.equal(calls.at(-1).request.messages.some(message => message.id === messageId), false)
    assert.ok(calls.at(-1).request.messages.some(message => message.content.some(block => block.text === 'COMPACTED_CONTEXT')))
  })
})

test('real agent: pending/provider finish/idle and session notification are not durability acknowledgements', async () => {
  const finish = deferred(), entered = deferred(), notifyGate = deferred()
  await harness({ stream: async function* () { entered.resolve(); yield* answer(); await finish.promise } }, async ({ ctx, prompt, lifecycle }) => {
    ctx.on('session/event', async (_session, event) => { if (event.type === 'turn/end') await notifyGate.promise })
    try {
      const { messageId } = await prompt()
      await entered.promise
      const params = control('status', 'session-1', 'request-1', messageId)
      assert.equal((await lifecycle.control(params)).status, 'pending')
      finish.resolve()
      // Postcommit fire-and-forget notifications cannot stall the explicit RPC.
      const result = await Promise.race([lifecycle.control({ ...params, op: 'settle' }), delay(5000).then(() => { throw new Error('CONTROL_DEADLOCK') })])
      assert.equal(result.durable, true)
    } finally { finish.resolve(); notifyGate.resolve() }
  })
})

test('real agent: absent or rejecting flush never reports durable success; a later retry can settle', async () => {
  await harness({ persistence: false }, async ({ prompt, lifecycle, ctx }) => {
    const { messageId } = await prompt()
    const result = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
    assert.equal(result.status, 'pending')
    assert.equal(result.code, 'WORLD_PERSISTENCE_UNAVAILABLE')
    assert.equal(result.durable, false)
    assert.equal(ctx.agents.get('session-1').status, 'idle')
  })
  await harness({}, async ({ prompt, lifecycle, ctx }) => {
    const { messageId } = await prompt()
    const rejectFlush = ctx.on('session/flush', () => { throw new Error('PRIVATE_STORAGE_DETAIL') })
    const params = control('settle', 'session-1', 'request-1', messageId)
    const result = await lifecycle.control(params)
    assert.equal(result.code, 'WORLD_FLUSH_FAILED')
    assert.equal(result.durable, false)
    assert.equal(JSON.stringify(result).includes('PRIVATE_STORAGE_DETAIL'), false)
    rejectFlush()
    assert.equal((await lifecycle.control(params)).durable, true)
  })
})

test('real SDK: targeted cancellation cannot cancel same-named request in another session or an unknown trigger', async () => {
  const enteredA = deferred(), enteredB = deferred()
  await harness({ stream: async function* (request) {
    (request.sessionId === 'session-a' ? enteredA : enteredB).resolve()
    await waitForSignal(request.signal)
  } }, async ({ prompt, server, ctx, lifecycle }) => {
    const a = await prompt('session-a', 'same-request')
    const b = await prompt('session-b', 'same-request')
    await Promise.all([enteredA.promise, enteredB.promise])
    const wrong = await server.handleRequest('zoneu/world-request', control('cancel', 'session-a', 'same-request', b.messageId))
    assert.equal(wrong.status, 'unknown')
    assert.equal(ctx.agents.get('session-a').status, 'running')
    const result = await server.handleRequest('zoneu/world-request', control('cancel', 'session-a', 'same-request', a.messageId))
    assert.equal(result.status, 'cancelled')
    assert.equal(result.durable, true)
    assert.equal(result.advance, false)
    assert.equal(ctx.agents.get('session-a').status, 'idle')
    assert.equal(ctx.agents.get('session-b').status, 'running')
    assert.equal(lifecycle.identityFor('session-a'), undefined)
    assert.equal(lifecycle.identityFor('session-b').requestId, 'same-request')
    await lifecycle.control(control('cancel', 'session-b', 'same-request', b.messageId))
    assert.equal((await lifecycle.control(control('settle', 'session-a', 'same-request', a.messageId))).advance, false)
  })
})

test('real agent: provider failure and partial cancellation never advance a completed receipt', async () => {
  await harness({ stream: async function* () { throw new Error('LOCAL_PROVIDER_FAILURE') } }, async ({ prompt, lifecycle }) => {
    const { messageId } = await prompt()
    const result = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
    assert.equal(result.status, 'failed', JSON.stringify(result))
    assert.equal(result.evidence.reason, 'error')
    assert.equal(result.durable, true)
    assert.equal(result.advance, false)
  })
  const entered = deferred()
  await harness({ stream: async function* (request) {
    yield { type: 'block-start', index: 0, blockType: 'text' }
    yield { type: 'text-delta', index: 0, text: 'INTERRUPTED_PREFIX' }
    entered.resolve()
    await waitForSignal(request.signal)
  } }, async ({ prompt, lifecycle }) => {
    const { messageId } = await prompt()
    await entered.promise
    const result = await lifecycle.control(control('cancel', 'session-1', 'request-1', messageId))
    assert.equal(result.status, 'cancelled')
    assert.equal(result.durable, true)
    assert.equal(result.advance, false)
    assert.equal(result.evidence.reason, 'aborted')
  })
})

test('real agent: control inside request waterfall rejects instead of awaiting its own idle', async () => {
  await harness({}, async ({ ctx, prompt, binds, lifecycle }) => {
    let guarded = false
    ctx.on('agent/request', async (_payload, next) => {
      await assert.rejects(lifecycle.control(control('cancel', 'session-1', 'request-1', binds[0].messageId)), /WORLD_CONTROL_REENTRANT/)
      guarded = true
      return next()
    }, { prepend: true })
    const { messageId } = await prompt()
    const result = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
    assert.equal(guarded, true)
    assert.equal(result.status, 'settled')
  })
})

test('real agent: idle another turn cannot borrow the previous identity; unbound session stays undefined', async () => {
  await harness({}, async ({ prompt, ctx, lifecycle, calls, makeAgent }) => {
    const { messageId } = await prompt()
    await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
    const agent = ctx.agents.get('session-1')
    agent.followup(user('NEXT_DAILY'))
    await agent.whenIdle()
    assert.equal(calls.at(-1).identity, undefined)
    const daily = await makeAgent('session-2')
    daily.followup(user('DAILY_OTHER'))
    await daily.whenIdle()
    assert.equal(calls.at(-1).identity, undefined)
    assert.equal(lifecycle.identityFor('session-2'), undefined)
    assert.equal((await lifecycle.control(control('status', 'session-2', 'request-1', messageId))).status, 'unknown')
  })
})

test('real plugin: provides service; dispose/reload clear uncertain bindings and stale actor identity', async () => {
  await harness({}, async ({ ctx, lifecycle, makeAgent, unprovide }) => {
    const agent = await makeAgent('session-reload')
    await lifecycle.bindPrompt({ sessionId: 'session-reload', requestId: 'request-reload', messageId: 'trigger-reload' })
    const stop = new AbortController()
    // Exercise the real fused dispatcher, not an invented payload without agent.
    // No official claim happened, so an arbitrary turn cannot establish identity.
    await assert.rejects(async () => agentEvents(ctx, agent).waterfall('agent/request', { turn: 1, step: 1, signal: stop.signal }, async () => ({})), /WORLD_TURN_MISMATCH/)
    lifecycle.dispose()
    await unprovide()
    assert.equal(lifecycle.identityFor('session-reload'), undefined)
    const fiber = await ctx.plugin(lifecyclePlugin)
    try {
      const replacement = ctx.get('zoneuWorldLifecycle')
      assert.ok(replacement)
      assert.equal(replacement.identityFor('session-reload'), undefined)
      assert.equal((await replacement.control(control('status', 'session-reload', 'request-reload', 'trigger-reload'))).status, 'unknown')
    } finally { await fiber.dispose() }
    assert.equal(ctx.get('zoneuWorldLifecycle'), undefined)
  })
})

test('host binding: every echo ID and kind is exact; missing credentials and malformed payload fail closed', async () => {
  const binding = { sessionId: 'session-bind', messageId: 'trigger-bind', requestId: 'request-bind' }
  const payload = { kind: 'zoneu.world.bind.v1', request_id: binding.requestId,
    dsh_session_id: binding.sessionId, trigger_message_id: binding.messageId }
  const config = { bridgeUrl: () => 'http://127.0.0.1:1', bridgeToken: () => 'fixture-token' }
  for (const key of Object.keys(payload)) {
    await assert.rejects(bindWorldRequest(binding, { ...config,
      transport: async () => new Response(JSON.stringify({ ok: true, payload: { ...payload, [key]: 'wrong' } })) }), /WORLD_BIND_MISMATCH/)
  }
  await assert.rejects(bindWorldRequest(binding, { ...config, bridgeToken: () => '' }), /WORLD_BIND_UNAVAILABLE/)
  await assert.rejects(bindWorldRequest(binding, { ...config,
    transport: async () => new Response(JSON.stringify({ ok: true, payload: '{PRIVATE_MALFORMED' })) }), error => error.message === 'WORLD_BIND_MISMATCH')
  await assert.rejects(bindWorldRequest({ ...binding, messageId: 'private text' }, config), /WORLD_INVALID_IDENTITY/)
})

test('actual SDK: cancelling in-flight authorization prevents late followup and permits a different request', async () => {
  const entered = deferred(), allow = deferred()
  await harness({ bindRequest: async binding => {
    if (binding.requestId === 'request-1') { entered.resolve(); await allow.promise }
  } }, async ({ prompt, lifecycle, binds, calls, server }) => {
    const prompting = prompt()
    const rejected = assert.rejects(prompting, /WORLD_BIND_CANCELLED/)
    await entered.promise
    const cancelled = await lifecycle.control(control('cancel', 'session-1', 'request-1', binds[0].messageId))
    assert.equal(cancelled.status, 'cancelled')
    assert.equal(cancelled.durable, false)
    assert.equal(cancelled.advance, false)
    const next = await prompt('session-1', 'request-2')
    assert.equal((await server.handleRequest('zoneu/world-request', control('settle', 'session-1', 'request-2', next.messageId))).advance, true)
    allow.resolve()
    await rejected
    assert.equal(calls.length, 1)
  })
})

test('actual SDK: disposing while host bind awaits cannot follow up a stale agent', async () => {
  const entered = deferred(), allow = deferred()
  await harness({ bindRequest: async () => { entered.resolve(); await allow.promise } }, async ({ prompt, lifecycle, calls, ctx }) => {
    const rejected = assert.rejects(prompt(), /WORLD_AGENT_UNAVAILABLE/)
    await entered.promise
    lifecycle.dispose()
    allow.resolve()
    await rejected
    assert.equal(calls.length, 0)
    assert.equal(ctx.agents.get('session-1').inbox.hasPending, false)
  })
})

test('actual SDK: busy ordinary agent rejects binding before creating a host mapping', async () => {
  const entered = deferred()
  await harness({ stream: async function* (request) { entered.resolve(); await waitForSignal(request.signal) } }, async ({ server, prompt, ctx, binds }) => {
    await server.handleRequest('session/prompt', { sessionId: 'session-1', contentBlocks: text('ORDINARY_RUNNING') })
    await entered.promise
    await assert.rejects(prompt(), /WORLD_AGENT_BUSY/)
    assert.equal(binds.length, 0)
    ctx.agents.get('session-1').cancel({ kind: 'user' })
    await ctx.agents.get('session-1').whenIdle()
  })
})

test('real loop: completed empty response stays unknown and reserved; max-tokens never advances', async () => {
  for (const kind of ['stop', 'max-tokens']) {
    await harness({ stream: async function* () {
      if (kind === 'max-tokens') {
        yield { type: 'block-start', index: 0, blockType: 'text' }
        yield { type: 'block-end', index: 0, block: { type: 'text', text: 'PARTIAL' } }
      }
      yield { type: 'finish', reason: { kind } }
    } }, async ({ prompt, lifecycle }) => {
      const { messageId } = await prompt()
      const result = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
      assert.equal(result.advance, false)
      if (kind === 'stop') {
        assert.equal(result.status, 'unknown')
        assert.equal(result.durable, false)
        await assert.rejects(prompt('session-1', 'request-2'), /WORLD_REQUEST_UNSETTLED/)
      } else {
        assert.equal(result.status, 'failed')
        assert.equal(result.durable, true)
        assert.equal(result.evidence.reason, 'max-tokens')
      }
    })
  }
})

test('real loop: flush is explicitly awaited and concurrent settlement RPCs share one barrier', async () => {
  await harness({}, async ({ ctx, prompt, lifecycle }) => {
    const { messageId } = await prompt()
    await ctx.agents.get('session-1').whenIdle()
    const entered = deferred(), gate = deferred()
    let flushes = 0, returned = false
    const remove = ctx.on('session/flush', async () => { flushes++; entered.resolve(); await gate.promise })
    try {
      const params = control('settle', 'session-1', 'request-1', messageId)
      const first = lifecycle.control(params).then(value => { returned = true; return value })
      const second = lifecycle.control(params)
      await entered.promise
      assert.equal(returned, false)
      assert.equal(flushes, 1)
      gate.resolve()
      const [a, b] = await Promise.all([first, second])
      assert.deepEqual(a, b)
      assert.equal(a.status, 'settled')
      assert.equal(a.advance, true)
    } finally { gate.resolve(); remove() }
  })
})

test('real loop and production middleware: nested unmarked same-session call clears projection scope', async () => {
  const { createWorldRuntime } = await import('../world-runtime.mjs')
  let world
  const bridges = []
  await harness({ stream: async function* (request, count, { ctx }) {
    if (count === 1) {
      for await (const _chunk of ctx.llm.stream({ provider: 'local', model: 'fixture', sessionId: request.sessionId,
        messages: [createUserMessage({ source: { kind: 'runtime-context' }, content: text('AUXILIARY_ONLY') })] })) { /* drain */ }
    }
    assert.equal(await world.project({ options: request, context: { messages: request.messages }, model: { api: 'openai-completions' } }), undefined)
    yield* answer()
  } }, async ({ ctx, lifecycle, prompt, calls }) => {
    world = createWorldRuntime({ identityFor: sessionId => lifecycle.identityFor(sessionId),
      bridgeUrl: () => 'http://127.0.0.1:1', bridgeToken: () => 'fixture-token',
      transport: async (_url, init) => {
        bridges.push(JSON.parse(init.body))
        return new Response(JSON.stringify({ ok: true, payload: { kind: 'zoneu.world.request.v1', active: false } }))
      } })
    ctx.on('llm/stream', (request, next) => world.stream(request, next))
    const { messageId } = await prompt()
    assert.equal((await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))).advance, true)
    assert.equal(calls.length, 2)
    assert.equal(calls[0].marked, true)
    assert.equal(calls[1].marked, false)
    assert.equal(bridges.length, 1)
    assert.equal(bridges[0].request_id, 'request-1')
    assert.equal(bridges[0].trigger_message_id, messageId)
    assert.ok(calls.every(call => !('requestId' in call.request) && !('trigger' in call.request)))
  })
})

// Fault injection decorates the REAL async query after its replay-validating
// read; no fake Agent, alternate snapshot API, or hand-constructed source log.
for (const defect of ['read-rejects', 'wrong-session', 'missing-end', 'missing-trigger', 'missing-answer',
  'answer-before-trigger', 'missing-step-start', 'missing-step-end', 'wrong-step', 'reordered-events', 'wrong-end-reason']) {
  test(`real query fault: ${defect} cannot release or advance a completed request; reread can recover`, async () => {
    await harness({}, async ({ ctx, prompt, lifecycle, calls }) => {
      const { messageId } = await prompt()
      await ctx.agents.get('session-1').whenIdle()
      const query = ctx.sessionQuery, originalRead = query.readSession
      query.readSession = async function(sessionId) {
        const snapshot = await originalRead.call(this, sessionId)
        if (defect === 'read-rejects') throw new Error('PRIVATE_READ_DETAIL')
        const events = snapshot.events
        const find = type => events.find(event => event.type === type)
        const index = type => events.findIndex(event => event.type === type)
        if (defect === 'wrong-session') snapshot.session.id = 'other-session'
        if (defect === 'missing-end') events.splice(index('turn/end'), 1)
        if (defect === 'missing-trigger') events.splice(index('user/message'), 1)
        if (defect === 'missing-answer') events.splice(index('assistant/message'), 1)
        if (defect === 'answer-before-trigger') {
          const a = index('assistant/message'), u = index('user/message')
          const aSeq = events[a].seq, uSeq = events[u].seq
          ;[events[a], events[u]] = [events[u], events[a]]
          events[a].seq = aSeq; events[u].seq = uSeq
        }
        if (defect === 'missing-step-start') events.splice(index('step/start'), 1)
        if (defect === 'missing-step-end') events.splice(index('step/end'), 1)
        if (defect === 'wrong-step') find('assistant/message').data.step += 1
        if (defect === 'reordered-events') events.reverse()
        if (defect === 'wrong-end-reason') find('turn/end').data.reason = { kind: 'max-tokens' }
        return snapshot
      }
      try {
        const result = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
        assert.equal(result.status, 'unknown', JSON.stringify(result))
        assert.equal(result.advance, false)
        assert.equal(result.durable, false)
        assert.ok(!JSON.stringify(result).includes('PRIVATE_READ_DETAIL'))
        await assert.rejects(prompt('session-1', 'request-2'), /WORLD_REQUEST_UNSETTLED/)
      } finally { query.readSession = originalRead }
      const recovered = await lifecycle.control(control('settle', 'session-1', 'request-1', messageId))
      assert.equal(recovered.status, 'settled')
      assert.equal(recovered.advance, true)
      assert.equal(calls.length, 1)
    })
  })
}

test('SDK reload race: replacing lifecycle after bind resolves prevents followup', async () => {
  await harness({}, async ({ ctx, lifecycle, prompt, calls, unprovide }) => {
    await unprovide()
    let stopOld, stopReplacement
    stopOld = ctx.provide('zoneuWorldLifecycle', {
      async bindPrompt(binding) {
        await lifecycle.bindPrompt(binding)
        // Change the service after the inner lifecycle's final live check, but
        // before the SDK continuation following its await. Existence alone is
        // insufficient: this is a fresh service without the old reservation.
        await stopOld()
        stopReplacement = ctx.provide('zoneuWorldLifecycle', { bindPrompt: async () => {}, control: async () => ({}) })
      },
    })
    try {
      await assert.rejects(prompt(), /WORLD_LIFECYCLE_CHANGED/)
      assert.equal(calls.length, 0)
      assert.equal(ctx.agents.get('session-1').inbox.hasPending, false)
    } finally { await stopReplacement?.(); await stopOld() }
  })
})

test('SDK await race: disposal of the bound agent prevents followup', async () => {
  await harness({}, async ({ ctx, server, lifecycle, prompt, calls, unprovide }) => {
    await unprovide()
    const stop = ctx.provide('zoneuWorldLifecycle', {
      async bindPrompt(binding) {
        await lifecycle.bindPrompt(binding)
        await server.sessions.get(binding.sessionId).handle.dispose()
      },
    })
    try {
      await assert.rejects(prompt(), /disposed outside the server/)
      assert.equal(calls.length, 0)
      assert.equal(ctx.agents.get('session-1'), undefined)
    } finally { await stop() }
  })
})

// These fault-injection fixtures wrap the real async query rather than
// inventing a Session shape. They assert that missing/corrupt observations
// cannot turn a nominal finish into host acknowledgement or release the slot.
for (const defect of ['wrong-trigger', 'wrong-turn', 'interrupted']) {
  test(`evidence: ${defect} remains unknown; a clean official reread can settle`, async () => {
    await harness({}, async ({ ctx, prompt, lifecycle, server }) => {
      const { messageId } = await prompt()
      await ctx.agents.get('session-1').whenIdle()
      const query = ctx.sessionQuery
      const original = query.readSession
      const realRead = original.bind(query)
      query.readSession = async sessionId => {
        const value = await realRead(sessionId)
        const result = structuredClone(value)
        const assistant = result.events.find(event => event.type === 'assistant/message')
        if (defect === 'wrong-trigger') result.events.find(event => event.type === 'user/message' && event.data.id === messageId).data.id = 'other-trigger'
        if (defect === 'wrong-turn') assistant.data.turn++
        if (defect === 'interrupted') assistant.data.interrupted = true
        return result
      }
      const params = control('settle', 'session-1', 'request-1', messageId)
      try {
        const result = await lifecycle.control(params)
        assert.equal(result.status, 'unknown', JSON.stringify(result))
        assert.equal(result.durable, false)
        assert.equal(result.advance, false)
        assert.equal(result.evidence, undefined)
        await assert.rejects(server.handleRequest('session/prompt', {
          sessionId: 'session-1', world_request_id: 'request-2', contentBlocks: text('NEXT'),
        }), /WORLD_REQUEST_UNSETTLED/)
      } finally { query.readSession = original }
      assert.equal((await lifecycle.control(params)).advance, true)
    })
  })
}


test('patch: stray partial bind or control fragments reject even when all original anchors remain', () => {
  const fragments = [
    '		const zoneuLifecycle = this.ctx.get("zoneuWorldLifecycle");',
    '		await zoneuLifecycle?.bindPrompt({',
    '		await this.ctx.get("zoneuWorldLifecycle")?.bindPrompt({',
    '			case "zoneu/world-request": {',
    '				return lifecycle.control(params);',
  ]
  for (const fragment of fragments) {
    assert.throws(() => patchWorldLifecycle(`${raw}
${fragment}`), /ANCHOR_MISMATCH/)
    assert.throws(() => patchWorldLifecycle(`${patched}
${fragment}`), /ANCHOR_MISMATCH/)
  }
})

test('SDK daily passthrough: lifecycle replacement during optional bind does not require world identity', async () => {
  await harness({}, async ({ ctx, server, calls, unprovide }) => {
    await unprovide()
    let stopOld, stopReplacement
    stopOld = ctx.provide('zoneuWorldLifecycle', {
      async bindPrompt(binding) {
        assert.equal(binding.requestId, undefined)
        await stopOld()
        stopReplacement = ctx.provide('zoneuWorldLifecycle', { bindPrompt: async () => {} })
      },
    })
    try {
      await server.handleRequest('session/prompt', { sessionId: 'daily-reload', contentBlocks: text('ORDINARY_DAILY') })
      await ctx.agents.get('daily-reload').whenIdle()
      assert.equal(calls.length, 1)
      assert.equal(calls[0].identity, undefined)
    } finally { await stopReplacement?.(); await stopOld() }
  })
})

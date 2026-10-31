import { AsyncLocalStorage } from 'node:async_hooks'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

let llm
try { llm = await import('@deepseek-ai/dsh-llm') } catch (error) {
  if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error
  const require = createRequire(new URL('../../../android/.dsh-cache/runtime/package.json', import.meta.url))
  llm = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href)
}
const { isAgentLoopRequest, LlmError } = llm
export const name = 'zoneu-world-runtime'
export const inject = ['zoneuWorldLifecycle']
export const provides = ['zoneuWorldRuntime']
const fail = (code) => { throw new LlmError(code, code) }
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const id = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,240}$/.test(value)

export function validateWorldPlan(plan) {
  if (!object(plan) || plan.kind !== 'zoneu.world.request.v1' || typeof plan.active !== 'boolean') fail('WORLD_INVALID_PLAN')
  if (!plan.active) return plan
  if (![plan.request_id, plan.session_id, plan.binding_revision].every(id) || !Array.isArray(plan.injections) || plan.injections.length > 128) fail('WORLD_INVALID_PLAN')
  const seen = new Set()
  let bytes = 0
  for (const entry of plan.injections) {
    if (!object(entry) || !id(entry.entry_id) || seen.has(entry.entry_id) || !['before', 'after', 'depth'].includes(entry.position) ||
      !['system', 'user', 'assistant'].includes(entry.role) || typeof entry.content !== 'string' || !entry.content.trim() ||
      !Number.isSafeInteger(entry.depth) || entry.depth < 0 || entry.depth > 12 ||
      (entry.position !== 'depth' && entry.role !== 'system')) fail('WORLD_INVALID_PLAN')
    seen.add(entry.entry_id)
    bytes += Buffer.byteLength(entry.content, 'utf8')
  }
  if (bytes > 1024 * 1024) fail('WORLD_PLAN_TOO_LARGE')
  return plan
}

// A call/result exchange is one indivisible depth unit. Never insert between its parts.
function groups(items, api) {
  const out = []
  let pending = new Set(), start = 0, cluster = false
  const finish = end => { if (end > start) out.push([start, end]); start = end; cluster = false }
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    let calls = [], results = [], special = false
    if (api === 'openai-completions') {
      calls = (item.tool_calls ?? []).map(call => call.id)
      if (item.role === 'tool') results = [item.tool_call_id]
    } else if (api === 'openai-responses') {
      special = !item.role
      if (item.type === 'function_call' || item.type === 'tool_search_call') calls = [item.call_id]
      if (item.type === 'function_call_output' || item.type === 'tool_search_output') results = [item.call_id]
      if (special && !['reasoning', 'function_call', 'function_call_output', 'tool_search_call', 'tool_search_output'].includes(item.type)) fail('WORLD_PROTOCOL_UNSUPPORTED')
    } else {
      const content = Array.isArray(item.content) ? item.content : []
      calls = content.filter(block => block.type === 'tool_use').map(block => block.id)
      results = content.filter(block => block.type === 'tool_result').map(block => block.tool_use_id)
    }
    // Responses reasoning and the assistant text produced with it are one unit,
    // but an assistant answer that follows a tool result is a later unit, as on
    // openai-completions; otherwise depth is measured over fewer units.
    const joinsReplay = api === 'openai-responses' && cluster &&
      (special || (item.role === 'assistant' && items[i - 1]?.type === 'reasoning'))
    if (!pending.size && !results.length && !joinsReplay) finish(i)
    for (const result of results) {
      if (!pending.delete(result)) fail('WORLD_TOOL_BOUNDARY')
    }
    for (const call of calls) {
      if (typeof call !== 'string' || pending.has(call)) fail('WORLD_TOOL_BOUNDARY')
      pending.add(call)
    }
    cluster ||= special || calls.length > 0
  }
  if (pending.size) fail('WORLD_TOOL_BOUNDARY')
  finish(items.length)
  return out
}

function wireMessage(entry, api) {
  if (api === 'openai-responses') return { type: 'message', role: entry.role,
    content: [{ type: entry.role === 'assistant' ? 'output_text' : 'input_text', text: entry.content }] }
  return { role: entry.role, content: entry.content }
}

export function projectWorldPayload(payload, model, entries) {
  if (!entries.length) return undefined
  const api = model.api
  if (!['openai-completions', 'openai-responses', 'anthropic-messages'].includes(api)) fail('WORLD_PROTOCOL_UNSUPPORTED')
  if (api === 'anthropic-messages' && entries.some(entry => entry.role === 'system')) fail('WORLD_PROTOCOL_UNSUPPORTED')
  const key = api === 'openai-responses' ? 'input' : 'messages'
  if (!object(payload) || !Array.isArray(payload[key])) fail('WORLD_INVALID_PAYLOAD')
  const items = payload[key]
  let prefix = 0
  while (prefix < items.length && ['system', 'developer'].includes(items[prefix].role)) prefix++
  const history = items.slice(prefix), units = groups(history, api), inserts = new Map()
  for (const entry of entries) {
    const unit = Math.max(0, units.length - entry.depth)
    const at = prefix + (unit === units.length ? history.length : units[unit][0])
    if (!inserts.has(at)) inserts.set(at, [])
    inserts.get(at).push(wireMessage(entry, api))
  }
  const projected = []
  for (let i = 0; i <= items.length; i++) {
    projected.push(...(inserts.get(i) ?? []))
    if (i < items.length) projected.push(items[i])
  }
  return { ...payload, [key]: projected }
}

export function projectionForPlan(input, rawPlan) {
  const plan = validateWorldPlan(rawPlan)
  if (!plan.active || !plan.injections.length) return undefined
  if (!['openai-completions', 'openai-responses', 'anthropic-messages'].includes(input.model.api)) fail('WORLD_PROTOCOL_UNSUPPORTED')
  let context = input.context
  const surrounding = plan.injections.filter(entry => entry.position !== 'depth')
  if (surrounding.length) {
    const anchor = plan.character_anchor
    const system = context.systemPrompt
    if (typeof anchor !== 'string' || !anchor || typeof system !== 'string' ||
      system.indexOf(anchor) < 0 || system.indexOf(anchor) !== system.lastIndexOf(anchor)) fail('WORLD_CHARACTER_ANCHOR_REQUIRED')
    const before = surrounding.filter(entry => entry.position === 'before').map(entry => entry.content)
    const after = surrounding.filter(entry => entry.position === 'after').map(entry => entry.content)
    const replacement = [...before, anchor, ...after].join('\n\n')
    context = { ...context, systemPrompt: system.replace(anchor, () => replacement) }
  }
  const depth = plan.injections.filter(entry => entry.position === 'depth')
  if (depth.length && (!['openai-completions', 'openai-responses', 'anthropic-messages'].includes(input.model.api) ||
    (input.model.api === 'anthropic-messages' && depth.some(entry => entry.role === 'system')))) fail('WORLD_PROTOCOL_UNSUPPORTED')
  return { context, ...(depth.length ? { onPayload: (payload, model) => projectWorldPayload(payload, model, depth) } : {}) }
}

const failureCodes = new Set(['ABORTED', 'AUTH', 'INVALID_CREDENTIAL', 'QUOTA_EXCEEDED', 'ACCOUNT_QUOTA_EXCEEDED',
  'RATE_LIMIT', 'SERVER', 'TIMEOUT', 'TRANSPORT', 'PI_AI_ERROR', 'INVALID_REQUEST', 'INVALID_CONFIG',
  'CONTEXT_WINDOW_EXCEEDED', 'EMPTY_RESPONSE', 'IMAGE_OFFLOAD_REQUIRED', 'STREAM_CLOSED', 'UNSUPPORTED_CONTENT',
  'WORLD_PROTOCOL_UNSUPPORTED', 'WORLD_TOOL_BOUNDARY', 'WORLD_CHARACTER_ANCHOR_REQUIRED', 'WORLD_INVALID_PAYLOAD',
  'WORLD_INVALID_PLAN', 'WORLD_PLAN_TOO_LARGE', 'WORLD_REQUEST_IDENTITY_REQUIRED'])

export function redactWorldFailure(chunk) {
  if (chunk?.type !== 'finish' || !['error', 'aborted'].includes(chunk.reason?.kind) || !chunk.reason.failure) return chunk
  const original = chunk.reason.failure
  const code = failureCodes.has(original.code) ? original.code : 'WORLD_PROVIDER_FAILED'
  const failure = { code, message: code }
  if (Number.isSafeInteger(original.status) && original.status >= 100 && original.status <= 599) failure.status = original.status
  if (Number.isFinite(original.providerRetryAfterMs) && original.providerRetryAfterMs > 0) failure.providerRetryAfterMs = original.providerRetryAfterMs
  if (code === 'IMAGE_OFFLOAD_REQUIRED' && Number.isSafeInteger(original.offloadImages) && original.offloadImages > 0) failure.offloadImages = original.offloadImages
  return { ...chunk, reason: { kind: chunk.reason.kind, failure } }
}

export function createWorldRuntime({ transport = globalThis.fetch, bridgeUrl = () => process.env.ANDROID_BRIDGE_URL,
  bridgeToken = () => process.env.ANDROID_BRIDGE_TOKEN, timeoutMs = 15000, identityFor } = {}) {
  const scope = new AsyncLocalStorage()
  const requests = new Map()
  function enter(identity, next) {
    const iterator = scope.run(identity, () => next()[Symbol.asyncIterator]())
    return (async function* () {
      let exhausted = false
      try {
        while (true) {
          const result = await scope.run(identity, () => iterator.next())
          if (result.done) { exhausted = true; return }
          yield identity?.worldActive ? redactWorldFailure(result.value) : result.value
        }
      } finally { if (!exhausted) await scope.run(identity, () => iterator.return?.()) }
    })()
  }
  return {
    captureRequest({ agent, turn, signal }) {
      if (!agent?.session?.id || !signal) fail('WORLD_REQUEST_IDENTITY_REQUIRED')
      const previous = requests.get(agent.session.id)
      const current = identityFor ? identityFor(agent.session.id) : undefined
      const trigger = current?.trigger ?? (!identityFor ?
        (previous?.agent === agent && previous.turn === turn ? previous.trigger : undefined) ??
        [...agent.session.deriveMessages()].reverse().find(message => message.role === 'user' && message.source?.kind === 'user')?.id : undefined)
      requests.set(agent.session.id, { agent, turn, signal, trigger })
    },
    // Read the marker before LlmRuntime clones options for modality/tool/replay projection.
    stream(options, next) {
      if (!isAgentLoopRequest(options)) return enter(undefined, next)
      let identity
      if (identityFor) identity = identityFor(options.sessionId)
      else {
        const captured = requests.get(options.sessionId)
        const trigger = captured?.trigger ?? [...options.messages].reverse().find(message => message.role === 'user' && message.source?.kind === 'user')?.id
        if (captured && !captured.trigger && trigger) requests.set(options.sessionId, { ...captured, trigger })
        identity = { sessionId: options.sessionId, trigger, turn: captured?.turn }
      }
      if (identity?.sessionId !== options.sessionId) {
        if (identity) fail('WORLD_REQUEST_IDENTITY_REQUIRED')
        return enter(undefined, next)
      }
      return enter({ ...identity }, next)
    },
    async project(input) {
      const identity = scope.getStore()
      if (!identity) return undefined
      if (!identity.sessionId || identity.sessionId !== input.options.sessionId || !identity.trigger) fail('WORLD_REQUEST_IDENTITY_REQUIRED')
      const base = bridgeUrl(), token = bridgeToken()
      if (!base || !token) fail('WORLD_BRIDGE_UNAVAILABLE')
      const signal = input.options.signal ? AbortSignal.any([input.options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs)
      signal.throwIfAborted()
      let plan
      try {
        const response = await transport(`${base}/action`, { method: 'POST', signal,
          headers: { 'content-type': 'application/json', 'x-android-bridge-token': token },
          body: JSON.stringify({ action: 'resolve_world_request', dsh_session_id: identity.sessionId, trigger_message_id: identity.trigger,
            ...(identity.requestId ? { request_id: identity.requestId } : {}), ...(identity.turn === undefined ? {} : { turn: identity.turn }) }) })
        const result = await response.json()
        if (!response.ok || result.ok !== true) fail('WORLD_HOST_FAILED')
        plan = typeof result.payload === 'string' ? JSON.parse(result.payload) : result.payload
      } catch (error) {
        if (signal.aborted) fail('WORLD_CANCELLED_OR_TIMEOUT')
        if (error instanceof LlmError) throw error
        fail('WORLD_TRANSPORT_FAILED')
      }
      signal.throwIfAborted()
      validateWorldPlan(plan)
      if (plan.active && (plan.session_id !== identity.sessionId ||
        (identity.requestId && plan.request_id !== identity.requestId))) fail('WORLD_REQUEST_IDENTITY_REQUIRED')
      identity.worldActive = plan.active
      return projectionForPlan(input, plan)
    },
  }
}

export function apply(ctx) {
  const runtime = createWorldRuntime({ identityFor: sessionId => ctx.get('zoneuWorldLifecycle')?.identityFor(sessionId) })
  ctx.provide('zoneuWorldRuntime', runtime)
  ctx.on('llm/stream', (options, next) => runtime.stream(options, next))
  ctx.on('agent/request', (event, next) => { runtime.captureRequest(event); return next() })
}

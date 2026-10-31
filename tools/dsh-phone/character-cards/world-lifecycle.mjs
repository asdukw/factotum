import { AsyncLocalStorage } from 'node:async_hooks'

export const name = 'zoneu-world-lifecycle'
export const inject = ['agents', 'sessions', 'sessionQuery']
export const provides = ['zoneuWorldLifecycle']
const validId = value => typeof value === 'string' && /^[A-Za-z0-9._:-]{1,512}$/.test(value)
const fail = code => { throw new Error(code) }
const reasonKinds = new Set(['completed', 'max-tokens', 'blocked', 'aborted', 'interrupted', 'error', 'forked'])
const reasonOf = event => reasonKinds.has(event?.data?.reason?.kind) ? event.data.reason.kind : 'unknown'
const pending = code => ({ status: 'pending', durable: false, advance: false, ...(code ? { code } : {}) })
const unknown = code => ({ status: 'unknown', durable: false, advance: false, code })

// No message inference: bind the SDK-created id, then learn its turn from the
// real loop's claimed notification. A compaction may delete that user from the
// model surface without changing this process-local logical request identity.
export async function bindWorldRequest(binding, { transport = globalThis.fetch,
  bridgeUrl = () => process.env.ANDROID_BRIDGE_URL,
  bridgeToken = () => process.env.ANDROID_BRIDGE_TOKEN, timeoutMs = 15000 } = {}) {
  if (![binding?.sessionId, binding?.messageId, binding?.requestId].every(validId)) fail('WORLD_INVALID_IDENTITY')
  const base = bridgeUrl(), token = bridgeToken()
  if (!base || !token || typeof transport !== 'function') fail('WORLD_BIND_UNAVAILABLE')
  const signal = AbortSignal.timeout(timeoutMs)
  let response, result
  try {
    response = await transport(`${base}/action`, { method: 'POST', signal,
      headers: { 'content-type': 'application/json', 'x-android-bridge-token': token },
      body: JSON.stringify({ action: 'bind_world_request', request_id: binding.requestId,
        dsh_session_id: binding.sessionId, trigger_message_id: binding.messageId }) })
    result = await response.json()
  } catch { fail(signal.aborted ? 'WORLD_BIND_TIMEOUT' : 'WORLD_BIND_TRANSPORT_FAILED') }
  if (signal.aborted) fail('WORLD_BIND_TIMEOUT')
  if (!response.ok || result?.ok !== true) fail('WORLD_BIND_REJECTED')
  let payload
  try { payload = typeof result.payload === 'string' ? JSON.parse(result.payload) : result.payload }
  catch { fail('WORLD_BIND_MISMATCH') }
  if (payload?.kind !== 'zoneu.world.bind.v1' || payload.request_id !== binding.requestId ||
    payload.dsh_session_id !== binding.sessionId || payload.trigger_message_id !== binding.messageId) fail('WORLD_BIND_MISMATCH')
}

export function createWorldLifecycle(ctx, { bindRequest = bindWorldRequest } = {}) {
  const requests = new Map(), active = new Map(), boundaries = new Map(), openTurns = new Map()
  const requestDispatch = new AsyncLocalStorage()
  let disposed = false
  const live = record => !disposed && ctx.agents.get(record.sessionId) === record.agent &&
    ctx.sessions.get(record.sessionId) === record.agent.session
  const release = record => {
    if (active.get(record.sessionId) === record) active.delete(record.sessionId)
    if (boundaries.get(record.sessionId)?.agent === record.agent) boundaries.delete(record.sessionId)
  }
  const exact = params => requests.get(params.sessionId)?.get(params.requestId)
  const response = record => record.result ?? pending(record.cancelRequested ? 'WORLD_CANCEL_REQUESTED' : undefined)
  const isOwnTurn = record => record.turn !== undefined && openTurns.get(record.sessionId)?.turn === record.turn &&
    openTurns.get(record.sessionId)?.session === record.agent.session

  const bindingError = error => {
    const codes = ['WORLD_BIND_UNAVAILABLE', 'WORLD_BIND_TIMEOUT', 'WORLD_BIND_TRANSPORT_FAILED', 'WORLD_BIND_REJECTED', 'WORLD_BIND_MISMATCH']
    fail(codes.includes(error?.message) ? error.message : 'WORLD_BIND_TRANSPORT_FAILED')
  }

  async function bindPrompt({ sessionId, messageId, requestId } = {}) {
    if (disposed) fail('WORLD_LIFECYCLE_DISPOSED')
    // Unbound ordinary conversations retain the SDK's normal queue semantics.
    if (requestId === undefined) {
      if (active.has(sessionId)) fail('WORLD_REQUEST_UNSETTLED')
      return
    }
    if (![sessionId, messageId, requestId].every(validId)) fail('WORLD_INVALID_IDENTITY')
    const previous = requests.get(sessionId)?.get(requestId)
    if (previous) {
      if (previous.trigger === messageId && live(previous) && !previous.result) {
        try { await previous.binding } catch (error) { bindingError(error) }
        if (!previous.authorized || previous.cancelRequested) fail('WORLD_BIND_CANCELLED')
        return
      }
      fail('WORLD_REQUEST_ALREADY_BOUND')
    }
    if (active.has(sessionId)) fail('WORLD_REQUEST_UNSETTLED')
    const agent = ctx.agents.get(sessionId)
    if (!agent || agent.session.id !== sessionId || ctx.sessions.get(sessionId) !== agent.session) fail('WORLD_AGENT_UNAVAILABLE')
    if (agent.status !== 'idle' || agent.inbox.hasPending || openTurns.has(sessionId)) fail('WORLD_AGENT_BUSY')
    const record = { sessionId, requestId, trigger: messageId, agent, fromSeq: agent.session.seq }
    if (!requests.has(sessionId)) requests.set(sessionId, new Map())
    requests.get(sessionId).set(requestId, record)
    active.set(sessionId, record)
    // Reservation and idle admission above execute synchronously before the
    // host await. A failed/lost bind stays reserved until explicit cancellation
    // or reload: it must not be guessed to be an uncommitted host transaction.
    record.binding = Promise.resolve().then(() => bindRequest({ sessionId, messageId, requestId }))
    try { await record.binding }
    catch (error) {
      bindingError(error)
    }
    if (!live(record)) fail('WORLD_AGENT_UNAVAILABLE')
    if (record.cancelRequested) fail('WORLD_BIND_CANCELLED')
    if (agent.status !== 'idle' || agent.inbox.hasPending || openTurns.has(sessionId)) {
      record.invalid = true
      fail('WORLD_AGENT_BUSY')
    }
    record.authorized = true
  }

  const disposers = [
    ctx.on('session/event', (session, event) => {
      const sessionId = String(session.id)
      if (event.type === 'turn/start') openTurns.set(sessionId, { session, turn: event.data.turn })
      if (event.type !== 'turn/end') return
      const open = openTurns.get(sessionId)
      if (open?.session === session && open.turn === event.data.turn) openTurns.delete(sessionId)
      const record = active.get(sessionId)
      if (record?.agent.session === session && record.turn === event.data.turn) {
        record.end = { seq: event.seq, reason: reasonOf(event) }
        boundaries.delete(sessionId)
      }
    }),
    ctx.on('agent/inbox/claimed', ({ agent, message, turn }) => {
      const record = active.get(String(agent.session.id))
      if (!record || record.agent !== agent || message.id !== record.trigger) return
      if (!record.authorized || !live(record) || message.source?.kind !== 'user' || record.turn !== undefined) {
        record.invalid = true
        agent.cancel({ kind: 'user' })
        return
      }
      record.turn = turn
      if (!isOwnTurn(record)) record.invalid = true
      if (record.cancelRequested || record.invalid) agent.cancel({ kind: 'user' })
    }),
    ctx.on('agent/request', ({ agent, turn, step, signal }, next) => {
      const sessionId = String(agent.session.id), record = active.get(sessionId)
      if (record) {
        if (!live(record) || record.agent !== agent || record.turn !== turn || !isOwnTurn(record) || record.invalid) {
          fail('WORLD_TURN_MISMATCH')
        }
        if (record.cancelRequested || signal.aborted) fail('WORLD_REQUEST_CANCELLED')
        boundaries.set(sessionId, { agent, turn, step, signal })
      } else boundaries.delete(sessionId)
      // A control RPC called reentrantly by a waterfall listener must not await
      // the same driver's idle. External RPCs are outside this async domain.
      return requestDispatch.run(agent, next)
    }),
    ctx.on('agent/disposed', ({ agent }) => {
      const record = active.get(String(agent.session.id))
      if (record?.agent === agent) { record.invalid = true; release(record) }
      if (openTurns.get(String(agent.session.id))?.session === agent.session) openTurns.delete(String(agent.session.id))
    }),
  ]

  function identityFor(sessionId) {
    const record = active.get(sessionId), boundary = boundaries.get(sessionId)
    if (!record || !live(record) || record.invalid || record.cancelRequested || record.result ||
      !boundary || boundary.agent !== record.agent || boundary.turn !== record.turn || boundary.signal.aborted ||
      record.agent.status !== 'running' || !isOwnTurn(record)) return undefined
    return Object.freeze({ sessionId: record.sessionId, trigger: record.trigger, requestId: record.requestId, turn: record.turn })
  }

  async function settle(record) {
    if (!live(record) || record.invalid) return unknown('WORLD_AGENT_UNAVAILABLE')
    await record.agent.whenIdle()
    if (!live(record) || record.invalid) return unknown('WORLD_AGENT_UNAVAILABLE')
    if (!record.end || record.turn === undefined) return pending('WORLD_END_NOT_OBSERVED')
    // readSession is live-preferred, not a disk-read receipt. Only an awaited,
    // participating flush establishes durability. Exclude anything appended
    // after that barrier's captured prefix from the evidence read below.
    const through = record.agent.session.seq
    try {
      if (await ctx.sessions.flush(record.agent.session) !== true) return pending('WORLD_PERSISTENCE_UNAVAILABLE')
    } catch { return pending('WORLD_FLUSH_FAILED') }
    if (!live(record) || record.invalid) return unknown('WORLD_AGENT_UNAVAILABLE')
    let snapshot
    try { snapshot = await ctx.sessionQuery.readSession(record.sessionId) }
    catch { return unknown('WORLD_SESSION_READ_FAILED') }
    if (!live(record) || record.invalid) return unknown('WORLD_AGENT_UNAVAILABLE')
    if (snapshot.session?.id !== record.sessionId || !Array.isArray(snapshot.events)) return unknown('WORLD_EVIDENCE_MISMATCH')
    const events = snapshot.events.filter(event => event.seq >= record.fromSeq && event.seq < through)
    if (events.some((event, index) => event.seq !== record.fromSeq + index) || events.length !== through - record.fromSeq) {
      return unknown('WORLD_EVIDENCE_MISMATCH')
    }
    const starts = events.filter(event => event.type === 'turn/start' && event.data.turn === record.turn)
    const ends = events.filter(event => event.type === 'turn/end' && event.data.turn === record.turn)
    if (starts.length !== 1 || ends.length !== 1 || ends[0].seq !== record.end.seq ||
      reasonOf(ends[0]) !== record.end.reason || record.end.reason === 'unknown' || starts[0].seq >= ends[0].seq) {
      return unknown('WORLD_EVIDENCE_MISMATCH')
    }
    const start = starts[0], end = ends[0]
    const turnEvents = events.filter(event => event.seq > start.seq && event.seq < end.seq)
    // The durable raw user event must corroborate the claimed exact SDK id;
    // shadowing it by compaction is fine. No current-surface user guessing.
    const triggers = turnEvents.filter(event => event.type === 'user/message' && event.data.id === record.trigger &&
      event.data.source?.kind === 'user')
    const trigger = triggers.length === 1 ? triggers[0] : undefined
    let openStep, lastStep = 0, answer, answerStepStart, answerStepEnd
    for (const event of turnEvents) {
      if (event.type === 'turn/start' || event.type === 'turn/end') return unknown('WORLD_EVIDENCE_MISMATCH')
      if (event.type === 'step/start') {
        if (openStep || event.data.turn !== record.turn || event.data.step !== lastStep + 1) return unknown('WORLD_EVIDENCE_MISMATCH')
        openStep = event
      } else if (event.type === 'step/end') {
        if (!openStep || event.data.turn !== record.turn || event.data.step !== openStep.data.step) return unknown('WORLD_EVIDENCE_MISMATCH')
        lastStep = event.data.step
        if (answer?.data.step === lastStep) answerStepEnd = event
        openStep = undefined
      } else if (event.type === 'assistant/message') {
        if (!openStep || event.data.turn !== record.turn || event.data.step !== openStep.data.step) return unknown('WORLD_EVIDENCE_MISMATCH')
        answer = event
        answerStepStart = openStep
        answerStepEnd = undefined
      }
    }
    if (openStep) return unknown('WORLD_EVIDENCE_MISMATCH')
    const finish = answer?.data.stream?.findLast(item => item.type === 'chunk' && item.chunk?.type === 'finish')?.chunk
    const hasAnswer = trigger && answer && answerStepStart && answerStepEnd && answer.seq > trigger.seq &&
      answer.data.step === lastStep && answerStepStart.seq < answer.seq && answer.seq < answerStepEnd.seq &&
      answerStepEnd.seq < end.seq && !answer.data.interrupted && answer.data.message?.role === 'assistant' &&
      finish?.reason?.kind === 'stop' && Array.isArray(answer.data.message.content) &&
      answer.data.message.content.some(block => block.type === 'text' && typeof block.text === 'string' && block.text.trim()) &&
      !answer.data.message.content.some(block => block.type === 'tool-call')
    // A nominally completed turn with missing/misaligned output is uncertain,
    // not a terminal failure. Keep its reservation so a later reread can prove
    // the answer; never let the next prompt erase an unsettled request.
    if (record.end.reason === 'completed' && !hasAnswer) return unknown('WORLD_COMPLETION_EVIDENCE_MISSING')
    const completed = record.end.reason === 'completed' && !record.cancelRequested && hasAnswer
    const evidence = Object.freeze({ sessionId: record.sessionId, requestId: record.requestId, trigger: record.trigger,
      turn: record.turn, endSeq: end.seq, reason: record.end.reason,
      ...(completed ? { assistantSeq: answer.seq } : {}) })
    record.result = Object.freeze({ status: completed ? 'settled' : record.cancelRequested || record.end.reason === 'aborted' ? 'cancelled' : 'failed',
      durable: true, advance: Boolean(completed), evidence })
    release(record)
    return record.result
  }

  async function control(params) {
    if (disposed) return unknown('WORLD_LIFECYCLE_DISPOSED')
    if (!params || !['status', 'settle', 'cancel'].includes(params.op) ||
      ![params.sessionId, params.requestId, params.trigger].every(validId)) fail('WORLD_INVALID_CONTROL')
    const record = exact(params)
    if (!record || record.trigger !== params.trigger) return unknown('WORLD_REQUEST_UNKNOWN')
    if (record.result) return record.result
    if (!live(record) || record.invalid) {
      // An unobservable record must still be releasable: keeping the reservation
      // would fail every later prompt on the session until a runtime reload.
      if (params.op !== 'cancel') return unknown('WORLD_AGENT_UNAVAILABLE')
      record.cancelRequested = true
      record.result = Object.freeze({ status: 'cancelled', durable: false, advance: false, code: 'WORLD_AGENT_UNAVAILABLE' })
      release(record)
      return record.result
    }
    if (params.op === 'status') return response(record)
    if (requestDispatch.getStore() === record.agent || ctx.agents.currentInitiator() === record.agent) fail('WORLD_CONTROL_REENTRANT')
    if (params.op === 'cancel') {
      record.cancelRequested = true
      if (!record.authorized && record.turn === undefined) {
        record.result = Object.freeze({ status: 'cancelled', durable: false, advance: false, code: 'WORLD_PROMPT_NOT_QUEUED' })
        release(record)
        return record.result
      }
      // Never cancel a different current turn or a later ordinary task. A bind
      // not yet followed up is latched; claim performs its cancellation too.
      if (!record.end && (isOwnTurn(record) || record.turn === undefined && record.agent.status === 'idle')) {
        record.agent.cancel({ kind: 'user' })
      }
    }
    record.settling ??= settle(record).finally(() => { record.settling = undefined })
    return record.settling
  }

  function dispose() {
    disposed = true
    for (const disposeListener of disposers.splice(0)) disposeListener()
    requests.clear(); active.clear(); boundaries.clear(); openTurns.clear()
  }
  return { bindPrompt, identityFor, control, dispose }
}

export function apply(ctx) {
  const lifecycle = createWorldLifecycle(ctx)
  ctx.provide('zoneuWorldLifecycle', lifecycle)
  ctx.effect(() => () => lifecycle.dispose(), 'world-lifecycle.clear')
}

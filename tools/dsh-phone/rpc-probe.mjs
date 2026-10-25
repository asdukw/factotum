// PC 侧验证探针：以 SDK client 身份跟 vendored dsh 的 stdio JSON-RPC 运行时对话。
//
// 用法（PC，需 android/local.properties 里有 factotum.agent.apiKey）：
//   node tools/dsh-phone/rpc-probe.mjs
//
// 只做验证：initialize → session/prompt("Reply with exactly: ping")，把
// session.event / session.status 打到控制台，idle 后退出。不打印任何密钥。
//
// 注：Windows 上完整 turn 可能因 jsonl 持久化的 win32 Koffi 原生模块缺失而失败
// （vendored 运行时按 android/arm64 安装，Android 不触发该路径）；真机验证走
// NodeDebugReceiver 广播。initialize 与事件通道在 PC 上可正常验证。
import { spawn } from 'node:child_process'
import { mkdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..')
const runtime = join(repoRoot, 'android', '.dsh-cache', 'runtime')
const work = join(process.env.TEMP ?? '/tmp', 'factotum-rpc-probe')

function apiKey() {
  const text = readFileSync(join(repoRoot, 'android', 'local.properties'), 'utf8')
  const line = text.split(/\r?\n/).find((entry) => entry.startsWith('factotum.agent.apiKey='))
  if (!line) throw new Error('factotum.agent.apiKey not found in android/local.properties')
  return line.slice('factotum.agent.apiKey='.length).trim()
}

mkdirSync(work, { recursive: true })

const child = spawn(process.execPath, [join(runtime, 'launcher.mjs')], {
  cwd: work,
  env: {
    ...process.env,
    DSH_HOME: work,
    FACTOTUM_WORKDIR: work,
    FACTOTUM_LLM_KEY: apiKey(),
    FACTOTUM_LLM_SESSION: `probe-${Date.now()}`,
    DSH_PERMISSION_MODE: 'danger-full-access',
  },
  stdio: ['pipe', 'pipe', 'pipe'],
})

let nextId = 0
const pending = new Map()
let prompted = false
let sawRunning = false
let finished = false
const assistantText = []

function finish(code) {
  if (finished) return
  finished = true
  console.log('\n== assistant text ==\n' + assistantText.join('\n'))
  child.kill()
  setTimeout(() => process.exit(code), 200)
}

function request(method, params) {
  const id = ++nextId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`)
  })
}

function handleFrame(frame) {
  if (frame.id !== undefined && frame.method === undefined) {
    const waiter = pending.get(frame.id)
    pending.delete(frame.id)
    if (frame.error) waiter?.reject(new Error(JSON.stringify(frame.error)))
    else waiter?.resolve(frame.result)
    return
  }
  if (frame.method === 'session.event') {
    const { type, data = {} } = frame.params?.event ?? {}
    if (type === 'assistant/message') {
      const text = (data.message?.content ?? [])
        .filter((block) => block.type === 'text')
        .map((block) => block.text)
        .join('')
      assistantText.push(text)
      console.log('[assistant]', text.slice(0, 300))
    } else if (type === 'tool/call') {
      console.log('[tool/call]', data.name, String(data.arguments).slice(0, 160))
    } else if (type === 'tool/result') {
      console.log('[tool/result]', data.message?.isError ? 'error' : 'ok')
    } else if (type === 'turn/end') {
      console.log('[turn/end]', JSON.stringify(data.reason))
    }
    return
  }
  if (frame.method === 'session.status') {
    const status = frame.params?.status
    console.log('[session.status]', status)
    if (status === 'running') sawRunning = true
    if (status === 'idle' && prompted && sawRunning) finish(0)
  }
}

let buffer = ''
child.stdout.setEncoding('utf8')
child.stdout.on('data', (chunk) => {
  buffer += chunk
  let index
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim()
    buffer = buffer.slice(index + 1)
    if (!line) continue
    try {
      handleFrame(JSON.parse(line))
    } catch {
      console.log('[stdout-ignored]', line.slice(0, 160))
    }
  }
})
child.stderr.setEncoding('utf8')
child.stderr.on('data', (chunk) => process.stderr.write(`[node] ${chunk}`))

const timer = setTimeout(() => {
  console.error('probe timeout')
  finish(1)
}, 240_000)

const init = await request('initialize', {
  cwd: work,
  provider: 'factotum',
  model: 'deepseek-v4.1-flash',
})
console.log('[initialize]', JSON.stringify(init))

const receipt = await request('session/prompt', {
  sessionId: `probe-${Date.now()}`,
  contentBlocks: [{ type: 'text', text: process.env.FACTOTUM_PROBE_PROMPT ?? 'Reply with exactly: ping' }],
})
prompted = true
console.log('[prompt receipt]', JSON.stringify(receipt))

process.on('exit', () => clearTimeout(timer))

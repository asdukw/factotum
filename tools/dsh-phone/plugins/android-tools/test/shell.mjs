// run_command 的契约测试：对着假桥跑，验「退出码 / 超时 / 空输出 / 环境没起来 / 桥不在」
// 都能如实传到模型面前 —— 这些正是模型最容易误判的地方（有输出不等于成功）。
//
// 传输是设备桥的 `POST /action`（action=run_command）；**输出排版在 App 侧**，插件只负责
// 把 summary 原样交给模型、把「环境没起来」判成工具错误。所以这里断言的是这两件事。
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { register } from 'node:module'

register('./stub-loader.mjs', import.meta.url)

const TOKEN = 'test-token'
const seen = []

/** 按 cmd 分派假桥：把每种结果形状都造出来。 */
const bridge = createServer((request, response) => {
  let raw = ''
  request.on('data', (chunk) => {
    raw += chunk
  })
  request.on('end', () => {
    if (request.headers['x-android-bridge-token'] !== TOKEN) {
      response.writeHead(401, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ ok: false, summary: 'bad token' }))
      return
    }
    const body = JSON.parse(raw)
    seen.push({ ...body, url: request.url })
    const replies = {
      'echo hi': { ok: true, summary: '$ echo hi\nexit=0 | 210ms\nhi' },
      // 退出码非 0：**不是**工具错误，模型要看得见 exit=1。
      'grep nope /dev/null': { ok: false, summary: '$ grep nope /dev/null\nexit=1 | 205ms\n(no output)' },
      'sleep 999': { ok: false, summary: '$ sleep 999\nexit=-1 (killed on timeout) | 2004ms' },
      // 环境没起来：重试同一条命令不会成功，必须是工具错误。
      'no-runtime': {
        ok: false,
        code: 'SHELL_UNAVAILABLE',
        summary: '手机上的 Linux 环境没起来：缺少 libproot.so。这不是命令本身的问题，重试同一条命令不会成功；把情况告诉用户。',
      },
    }
    const reply = replies[body.cmd] ?? { ok: false, summary: `$ ${body.cmd}\nexit=127 | 200ms\ncommand not found` }
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify(reply))
  })
})
await new Promise((resolve) => bridge.listen(0, '127.0.0.1', resolve))

process.env.ANDROID_BRIDGE_URL = `http://127.0.0.1:${bridge.address().port}`
process.env.ANDROID_BRIDGE_TOKEN = TOKEN

const plugin = await import('../index.mjs')

function applyTools() {
  const tools = new Map()
  plugin.apply({ on: () => {}, tools: { register: (tool) => tools.set(tool.name, tool) } })
  return tools
}

// 没注入 FACTOTUM_PROOT 时不注册：没有运行时的宿主不该每轮白占 schema。
delete process.env.FACTOTUM_PROOT
assert.ok(!applyTools().has('run_command'), 'run_command must not be registered without FACTOTUM_PROOT')

process.env.FACTOTUM_PROOT = '1'
const run = applyTools().get('run_command')
assert.ok(run, 'run_command must be registered when FACTOTUM_PROOT=1')

// 成功：App 排好的 summary 原样返回。
assert.match(await run.execute({ cmd: 'echo hi' }), /exit=0/)
// 请求形状：走 /action，带 action=run_command 与显式超时（不让桥去猜默认值）。
assert.equal(seen[0].url, '/action')
assert.equal(seen[0].action, 'run_command')
assert.equal(seen[0].timeout_ms, 60000)

// **有输出 ≠ 成功**：grep 无匹配退出码是 1，模型必须看得见这个 1，而不是抛异常。
assert.match(await run.execute({ cmd: 'grep nope /dev/null' }), /exit=1/)

// 超时要显式标注，否则模型会把空输出当成「命令没输出」而不是「被杀掉了」。
const killed = await run.execute({ cmd: 'sleep 999', timeout_ms: 2000 })
assert.match(killed, /killed on timeout/)
assert.equal(seen.at(-1).timeout_ms, 2000)

// 环境没起来 = 工具错误，不是命令输出。
await assert.rejects(() => run.execute({ cmd: 'no-runtime' }), /Linux 环境没起来/)

// 桥不在：错误信息要指向可执行的下一步，而不是一句 fetch failed。
await new Promise((resolve) => bridge.close(resolve))
await assert.rejects(() => run.execute({ cmd: 'echo hi' }), /Cannot reach the device bridge/)

delete process.env.FACTOTUM_PROOT

console.log('ok: run_command 契约（注册门槛 / 退出码 / 超时 / 环境没起来 / 桥不在）')

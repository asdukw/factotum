import assert from 'node:assert/strict'
import { register } from 'node:module'
import { startMockHost } from '../examples/mock-host.mjs'

register('./stub-loader.mjs', import.meta.url)

const host = await startMockHost()

process.env.ANDROID_BRIDGE_URL = `http://127.0.0.1:${host.port}`
process.env.ANDROID_BRIDGE_TOKEN = host.token

const plugin = await import('../index.mjs')
const tools = new Map()
plugin.apply({ on: () => {}, tools: { register: (tool) => tools.set(tool.name, tool) } })

// 宿主回了个不是契约形状的 payload 时，capture_screenshot 要退回纯文字而不是抛错。
{
  const { createServer } = await import('node:http')
  const bad = createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ ok: true, summary: '截图 ok', payload: 'not json' }))
  })
  await new Promise((resolve) => bad.listen(0, '127.0.0.1', resolve))
  const saved = process.env.ANDROID_BRIDGE_URL
  process.env.ANDROID_BRIDGE_URL = `http://127.0.0.1:${bad.address().port}`
  try {
    const degraded = await tools.get('capture_screenshot').execute({})
    assert.equal(degraded.attachment, undefined)
    assert.match(degraded.summary, /did not return frame data/)
  } finally {
    process.env.ANDROID_BRIDGE_URL = saved
    await new Promise((resolve) => bad.close(resolve))
  }
}

const screen = await tools.get('read_ui_tree').execute({})
assert.match(screen, /mock host/, 'read_ui_tree must return the host payload')

// 没有附件服务时 capture_screenshot 退回纯文字（图交给谁由宿主运行时决定），不能抛。
const shot = await tools.get('capture_screenshot').execute({})
assert.match(shot.summary, /screenshot f1/, 'capture_screenshot must surface the screenshot id')
assert.match(shot.summary, /image 2x2 px/, 'capture_screenshot must tell the model the image size')
assert.match(shot.summary, /screen 4x4 px/, 'capture_screenshot must tell the model the screen size')
assert.equal(shot.attachment, undefined, 'without ctx.attachments there is no image block')
const shotBlocks = tools.get('capture_screenshot').output.render({}, shot)
assert.equal(shotBlocks.length, 1, 'text-only fallback renders one block')

// 点了但界面没变：宿主回 ok=true + NO_VISIBLE_CHANGE —— 不是失败，绝不能抛。
const tapped = await tools.get('tap_target').execute({ screenshot_id: 'f1', x1: 0, y1: 0, x2: 2, y2: 2 })
assert.match(tapped, /界面似乎没有变化/, 'the verification verdict must reach the model')

assert.equal(await tools.get('tap_node').execute({ ref: 2 }), 'mock host acknowledged tap_node')
assert.equal(
  await tools.get('long_press').execute({ ref: 2, duration_ms: 700 }),
  'mock host acknowledged long_press',
)
assert.equal(await tools.get('type_text').execute({ text: 'hi' }), 'mock host acknowledged type_text')

process.env.ANDROID_BRIDGE_TOKEN = 'wrong'
await assert.rejects(() => tools.get('press').execute({ key: 'back' }), /unauthorized/)

await host.close()
delete process.env.ANDROID_BRIDGE_URL
delete process.env.ANDROID_BRIDGE_TOKEN
await assert.rejects(() => tools.get('read_ui_tree').execute({}), /not set/)

console.log('ok: bridge contract round-trip')

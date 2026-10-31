// Minimal bridge host mock: speaks the documented contract so you can try
// dsh-plugin-android-tools without a real device. It is NOT an automation host —
// read_ui_tree returns a canned screen, capture_screenshot returns a real 2x2 PNG plus the
// frame fields, tap_target answers with a verification verdict, and every other action
// is acknowledged.
//
// Run:  node examples/mock-host.mjs
// Then: ANDROID_BRIDGE_URL=http://127.0.0.1:37812 ANDROID_BRIDGE_TOKEN=dev-token
import { createServer } from 'node:http'
import { pathToFileURL } from 'node:url'

export const MOCK_SCREEN = [
  'pkg=com.example.mock nodes=3',
  '[1] TextView "Hello from the mock host" @(540,300)',
  '[2] Button "OK" clickable @(540,900)',
].join('\n')

/**
 * The `capture_screenshot` action's documented payload: a JSON string carrying the encoded
 * image **and the frame fields** (screenshotId + image/screen sizes). This mock answers with
 * a real 2x2 PNG so the image path is exercisable without a device.
 */
export const MOCK_FRAME = JSON.stringify({
  mediaType: 'image/png',
  width: 2,
  height: 2,
  screenshotId: 'f1',
  screenWidth: 4,
  screenHeight: 4,
  offsetX: 0,
  offsetY: 0,
  base64:
    'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4nGP8z8DAwMDAxMDAwMAAAA0BAQDJ/pLvAAAAAElFTkSuQmCC',
})

export function startMockHost({ port = 0, token = 'dev-token' } = {}) {
  const server = createServer((request, response) => {
    const reply = (body) => {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
      response.end(JSON.stringify(body))
    }
    if (request.method !== 'POST' || !request.url.startsWith('/action')) {
      reply({ ok: false, summary: 'unsupported request' })
      return
    }
    let raw = ''
    request.on('data', (chunk) => (raw += chunk))
    request.on('end', () => {
      if (request.headers['x-android-bridge-token'] !== token) {
        reply({ ok: false, summary: 'unauthorized' })
        return
      }
      let action
      try {
        action = JSON.parse(raw).action
      } catch {
        reply({ ok: false, summary: 'invalid JSON' })
        return
      }
      if (action === 'read_ui_tree') reply({ ok: true, payload: MOCK_SCREEN })
      else if (action === 'capture_screenshot')
        reply({ ok: true, summary: '帧 f1｜图像 2×2 px｜屏幕 4×4 px', payload: MOCK_FRAME })
      // 点了但界面没变：ok 仍是 true，只有 code/changed 说明情况 —— 插件不能因此抛错。
      else if (action === 'tap_target')
        reply({
          ok: true,
          summary: '已点击 @(1,1)；图像 (1,1) → 屏幕 (2,2)；界面似乎没有变化（可能没点中）',
          code: 'NO_VISIBLE_CHANGE',
          changed: false,
        })
      else if (action) reply({ ok: true, payload: `mock host acknowledged ${action}` })
      else reply({ ok: false, summary: 'missing action' })
    })
  })
  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => {
      resolve({
        port: server.address().port,
        token,
        close: () => new Promise((done) => server.close(done)),
      })
    })
  })
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { port, token } = await startMockHost({
    port: Number(process.env.MOCK_BRIDGE_PORT ?? 37812),
    token: process.env.MOCK_BRIDGE_TOKEN ?? 'dev-token',
  })
  console.log(`mock host on http://127.0.0.1:${port} (token: ${token})`)
}

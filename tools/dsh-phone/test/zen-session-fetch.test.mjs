// zen 会话头补丁的行为测试。
//
// 这一层是**唯一**替 dsh 自带搜索 provider 补 zen 私有要求的地方（provider 没有自定义
// header 的配置项）。它作用在所有出网请求上，所以边界必须钉死：
//   - 只有 zen 的主机名加头，别的请求一个字都不动；
//   - 已经带了头的请求不覆盖；
//   - 重复安装只装一层（launcher 在同一进程里会 reload 多次）。
import assert from 'node:assert/strict'
import { installZenSessionFetch } from '../zen-session-fetch.mjs'

/** 装一层补丁并返回「被包住的那个 fetch 收到了什么」。 */
function capture(options = {}) {
  const seen = []
  const target = async (input, init) => {
    seen.push({ input, headers: new Headers(init?.headers ?? input?.headers) })
    return { ok: true }
  }
  let installed
  const wrapped = installZenSessionFetch({
    target,
    install: (fetchImpl) => {
      installed = fetchImpl
    },
    ...options,
  })
  return { seen, wrapped, installed }
}

// --- 1) zen 主机：补头 ---
{
  const { seen, wrapped } = capture({ session: 'sess-1' })
  await wrapped('https://opencode.ai/zen/go/v1/messages', { method: 'POST', headers: { 'content-type': 'application/json' } })
  assert.equal(seen[0].headers.get('x-opencode-session'), 'sess-1')
  assert.equal(seen[0].headers.get('content-type'), 'application/json', '原有的头不能被吃掉')
}

// --- 2) 别的主机：一个字都不动 ---
{
  const { seen, wrapped } = capture({ session: 'sess-1' })
  await wrapped('https://api.deepseek.com/anthropic/v1/messages', { headers: {} })
  await wrapped('https://gmail.googleapis.com/gmail/v1/users/me/messages', { headers: {} })
  for (const call of seen) {
    assert.equal(call.headers.get('x-opencode-session'), null, '非 zen 主机不该被加头')
  }
}

// --- 3) 已经带了头：不覆盖（调用方显式给的值优先） ---
{
  const { seen, wrapped } = capture({ session: 'sess-default' })
  await wrapped('https://opencode.ai/zen/go/v1/messages', { headers: { 'x-opencode-session': 'caller' } })
  assert.equal(seen[0].headers.get('x-opencode-session'), 'caller')
}

// --- 4) URL 对象与 Request 形状的 input ---
{
  const { seen, wrapped } = capture({ session: 'sess-2' })
  await wrapped(new URL('https://opencode.ai/zen/go/v1/messages'), { headers: {} })
  await wrapped({ url: 'https://opencode.ai/zen/go/v1/responses', headers: new Headers() })
  assert.equal(seen[0].headers.get('x-opencode-session'), 'sess-2')
  assert.equal(seen[1].headers.get('x-opencode-session'), 'sess-2')
}

// --- 5) 不是 URL 的 input：原样透传，不炸 ---
{
  const { seen, wrapped } = capture({ session: 'sess-3' })
  await wrapped('not-a-url', { headers: {} })
  assert.equal(seen.length, 1)
  assert.equal(seen[0].headers.get('x-opencode-session'), null)
}

// --- 6) 幂等：重复安装只装一层 ---
{
  const target = async () => ({ ok: true })
  let installedCount = 0
  const install = (fetchImpl) => {
    installedCount += 1
    globalThis.__probeFetch = fetchImpl
  }
  installZenSessionFetch({ target, install })
  const again = installZenSessionFetch({ target: globalThis.__probeFetch, install })
  assert.equal(installedCount, 1, '重复安装不该再包一层')
  assert.equal(again, globalThis.__probeFetch)
  delete globalThis.__probeFetch
}

// --- 7) 会话 id 的取值顺序：options > env > 常量 ---
{
  const previous = process.env.ZONEU_LLM_SESSION
  process.env.ZONEU_LLM_SESSION = 'from-env'
  const fromEnv = capture()
  await fromEnv.wrapped('https://opencode.ai/zen/go/v1/messages', { headers: {} })
  assert.equal(fromEnv.seen[0].headers.get('x-opencode-session'), 'from-env')

  delete process.env.ZONEU_LLM_SESSION
  const fallback = capture()
  await fallback.wrapped('https://opencode.ai/zen/go/v1/messages', { headers: {} })
  assert.equal(fallback.seen[0].headers.get('x-opencode-session'), 'zoneu-mobile')

  if (previous === undefined) delete process.env.ZONEU_LLM_SESSION
  else process.env.ZONEU_LLM_SESSION = previous
}

console.log('ok: zen 会话头只加在 zen 主机上，且不覆盖调用方给的值')

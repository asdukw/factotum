import assert from 'node:assert/strict'

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-plugin-approval')
assert.deepEqual(plugin.inject, [])

const listeners = []
plugin.apply({ on: (event, handler, options) => listeners.push({ event, handler, options }) })

assert.equal(listeners.length, 1, '只注册一个 approval/request 监听器')
assert.equal(listeners[0].event, 'approval/request')
assert.equal(typeof listeners[0].handler, 'function')
// prepend 不是风格问题：api-remotes 也挂在这个 waterfall 上，排在它后面就会等浏览器回答。
assert.equal(listeners[0].options?.prepend, true, '必须排在 api-remotes 前面')

console.log('ok: approval answerer registered (prepend)')

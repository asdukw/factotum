import assert from 'node:assert/strict'

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-plugin-user-questions')
assert.deepEqual(plugin.inject, [])

const listeners = []
plugin.apply({ on: (event, listener, options) => listeners.push({ event, listener, options }) })

assert.equal(listeners.length, 1)
assert.equal(listeners[0].event, 'user-questions/request')
// **必须 prepend**：dsh-api-remotes 也在同一个 waterfall 上，排在它后面就永远轮不到我们
// （它会把请求推进没人消费的 remote 队列，请求永远不落地）。这条是这个插件唯一的注册约束。
assert.equal(listeners[0].options?.prepend, true, '必须 prepend:true，否则被 api-remotes 吃掉')

console.log('ok: 1 listener on user-questions/request (prepend)')

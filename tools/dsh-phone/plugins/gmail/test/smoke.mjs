import assert from 'node:assert/strict'
import { register } from 'node:module'

register('./stub-loader.mjs', import.meta.url)

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-plugin-gmail')
assert.deepEqual(plugin.inject, ['tools'])

const registered = []
const listeners = []
plugin.apply({
  tools: { register: (tool) => registered.push(tool) },
  on: (event, handler) => listeners.push({ event, handler }),
})

const names = registered.map((tool) => tool.name).sort()
assert.deepEqual(names, [
  'mail_draft',
  'mail_labels',
  'mail_list',
  'mail_modify',
  'mail_read',
  'mail_save_attachment',
  'mail_search',
  'mail_send',
  'mail_thread',
])

// 八个工具都回字符串。
for (const tool of registered) {
  assert.equal(typeof tool.execute, 'function', `${tool.name} must have execute`)
  assert.equal(tool.output.schema.type, 'string', `${tool.name} must render a string`)
  assert.equal(typeof tool.output.render, 'function', `${tool.name} must have render`)
}

// **写操作的闸门**：三个写工具必须在 tools/pre-execute 里被判成 ask；
// 读工具必须 next() 放行，不能顺手打扰用户。
assert.equal(listeners.length, 1, '只注册一个 tools/pre-execute 监听器')
assert.equal(listeners[0].event, 'tools/pre-execute')
const preExecute = listeners[0].handler

const WRITE_ARGS = {
  mail_modify: { message_id: 'm-1', remove_labels: 'UNREAD' },
  mail_draft: { to: 'a@example.com', subject: 'hi', body: 'x' },
  mail_send: { to: 'a@example.com', subject: 'hi', body: 'x' },
  mail_save_attachment: { message_id: 'm-1', index: 1 },
}

for (const tool of registered) {
  if (!(tool.name in WRITE_ARGS)) continue
  const decision = preExecute({ name: tool.name, arguments: WRITE_ARGS[tool.name] }, async () => 'allow')
  assert.equal(decision.kind, 'ask', `${tool.name} 必须走审批`)
  assert.ok(decision.reason, `${tool.name} 要有审计用 reason`)
  assert.ok(decision.displayReason?.zh, `${tool.name} 要有给面板的 displayReason`)
}

let nextCalls = 0
const next = async () => {
  nextCalls += 1
  return 'allow'
}
assert.equal(await preExecute({ name: 'mail_read', arguments: { message_id: 'm-1' } }, next), 'allow')
assert.equal(nextCalls, 1, '读工具必须放行')

console.log(`ok: ${registered.length} tools registered（4 个写工具全部走审批）`)

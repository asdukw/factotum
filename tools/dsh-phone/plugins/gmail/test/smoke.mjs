import assert from 'node:assert/strict'
import { register } from 'node:module'

register('./stub-loader.mjs', import.meta.url)

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-plugin-gmail')
assert.deepEqual(plugin.inject, ['tools'])

const registered = []
plugin.apply({ tools: { register: (tool) => registered.push(tool) } })

const names = registered.map((tool) => tool.name).sort()
assert.deepEqual(names, ['mail_labels', 'mail_list', 'mail_read', 'mail_search', 'mail_thread'])

// 五个工具都回字符串（正文/列表都是文本，没有附件通道）。
for (const tool of registered) {
  assert.equal(typeof tool.execute, 'function', `${tool.name} must have execute`)
  assert.equal(tool.output.schema.type, 'string', `${tool.name} must render a string`)
  assert.equal(typeof tool.output.render, 'function', `${tool.name} must have render`)
}

// **这是「本轮能力面」的断言，不是永久边界。** 写操作会加，但必须经 dsh 官方审批 seam：
// 工具在 `tools/pre-execute` 里被判成 `ask`，由 `@deepseek-ai/dsh-user-approval` 交给用户，
// 只有 `allowed-once` 才执行。加写工具时这条测试要同时换成「每个写工具都挂了审批」。
for (const tool of registered) {
  assert.ok(!/send|delete|move|mark|reply|forward|trash|archive/i.test(tool.name), `${tool.name} 超出了本轮只读能力面`)
}

console.log(`ok: ${registered.length} tools registered (${names.join(', ')})`)

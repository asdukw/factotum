import assert from 'node:assert/strict'
import { register } from 'node:module'

register('./stub-loader.mjs', import.meta.url)

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-plugin-gmail')
assert.deepEqual(plugin.inject, ['tools'])

const registered = []
plugin.apply({ tools: { register: (tool) => registered.push(tool) } })

const names = registered.map((tool) => tool.name).sort()
assert.deepEqual(names, ['mail_list', 'mail_read', 'mail_search'])

// 三个工具都回字符串（正文/列表都是文本，没有附件通道）。
for (const tool of registered) {
  assert.equal(typeof tool.execute, 'function', `${tool.name} must have execute`)
  assert.equal(tool.output.schema.type, 'string', `${tool.name} must render a string`)
  assert.equal(typeof tool.output.render, 'function', `${tool.name} must have render`)
}

// 只读是**能力面**的一部分，不是实现细节：出现 send / delete / mark 这类工具名就是越界。
for (const tool of registered) {
  assert.ok(!/send|delete|move|mark|reply|forward|trash|archive/i.test(tool.name), `${tool.name} 超出了只读边界`)
}

console.log(`ok: ${registered.length} tools registered (${names.join(', ')})`)

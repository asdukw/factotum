import assert from 'node:assert/strict'
import { register } from 'node:module'
register('./stub-loader.mjs', import.meta.url)

process.env.ANDROID_BRIDGE_URL = 'http://127.0.0.1:1'
process.env.ANDROID_BRIDGE_TOKEN = 'synthetic'
const calls = []
globalThis.fetch = async (_url, init) => {
  calls.push(JSON.parse(init.body))
  return { ok: true, json: async () => ({ ok: true, payload: 'scheduled' }) }
}
const tools = new Map()
let approve
const plugin = await import('../index.mjs')
plugin.apply({
  tools: { register: tool => tools.set(tool.name, tool) },
  on: (name, fn) => { if (name === 'tools/pre-execute') approve = fn },
})
const rules = [
  {},
  { schedule_type: 'weekly', weekday: 3 },
  { schedule_type: 'monthly', day_of_month: 31 },
  { schedule_type: 'yearly', month: 2, day_of_month: 29 },
  { schedule_type: 'once', date: '2027-01-05' },
]
for (const rule of rules) {
  const args = { title: 'PRIVATE-TITLE', prompt: 'PRIVATE-PROMPT', hour: 9, minute: 5, ...rule }
  assert.equal(await tools.get('schedule_task').execute(args), 'scheduled')
  assert.deepEqual(calls.at(-1), { action: 'schedule_task', ...args })
  const decision = approve({ name: 'schedule_task', arguments: args }, () => {})
  assert.equal(decision.kind, 'ask')
  assert.ok(!decision.reason.includes(args.title))
  assert.ok(!decision.reason.includes(args.prompt))
  assert.ok(decision.displayReason.zh.includes(args.title))
  const update = { id: 'id', ...rule, minute: 10 }
  await tools.get('update_scheduled_task').execute(update)
  assert.deepEqual(calls.at(-1), { action: 'update_scheduled_task', ...update })
  assert.equal(approve({ name: 'update_scheduled_task', arguments: update }, () => {}).kind, 'ask')
}
const create = tools.get('schedule_task')
assert.deepEqual(create.parameters.schedule_type.enum, ['daily', 'weekly', 'monthly', 'yearly', 'once'])
for (const field of ['weekday', 'day_of_month', 'month', 'date']) assert.ok(create.parameters[field])
assert.match(create.description, /每周三/)
assert.match(create.description, /每月15号/)
assert.match(create.description, /每年6月/)
assert.match(create.description, /2027年1月5日/)
assert.match(create.description, /ask when/)
console.log('ok: daily/weekly/monthly/yearly/once schemas, forwarding and approval privacy')

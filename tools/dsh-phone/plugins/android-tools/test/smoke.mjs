import assert from 'node:assert/strict'
import { register } from 'node:module'

register('./stub-loader.mjs', import.meta.url)

const plugin = await import('../index.mjs')

assert.equal(plugin.name, 'dsh-android-tools')
assert.deepEqual(plugin.inject, ['tools', 'systemPrompt'])

const registered = []
// 单测里不挂 systemPrompt：插件要能在这个缺口下照常注册工具（只少一行时间）。
plugin.apply({ on: () => {}, tools: { register: (tool) => registered.push(tool) } })

const names = registered.map((tool) => tool.name).sort()
assert.deepEqual(names, [
  'add_calendar_event',
  'capture_screenshot',
  'delete_calendar_event',
  'delete_scheduled_task',
  'launch_app',
  'list_calendar_events',
  'list_scheduled_tasks',
  'long_press',
  'long_press_target',
  'press',
  'read_ui_tree',
  'schedule_task',
  'set_alarm',
  'swipe',
  'tap_node',
  'tap_target',
  'type_text',
  'update_calendar_event',
  'update_scheduled_task',
])

// 文本工具回字符串；capture_screenshot 回对象（图例 + 附件引用），render 里再出图。
const OBJECT_OUTPUT = new Set(['capture_screenshot'])
for (const tool of registered) {
  assert.equal(typeof tool.execute, 'function', `${tool.name} must have execute`)
  const expected = OBJECT_OUTPUT.has(tool.name) ? 'object' : 'string'
  assert.equal(tool.output.schema.type, expected, `${tool.name} must render a ${expected}`)
}

console.log(`ok: ${registered.length} tools registered (${names.join(', ')})`)

// 宿主注入 FACTOTUM_PROOT=1 时才多出 run_command。没有这个运行时的 App 版本不该在工具表里
// 看到一个必然失败的工具 —— 它每轮都占 schema 预算，还会把模型引向一条死路。
{
  const saved = process.env.FACTOTUM_PROOT
  process.env.FACTOTUM_PROOT = '1'
  const withShell = []
  plugin.apply({ on: () => {}, tools: { register: (tool) => withShell.push(tool) } })
  assert.equal(withShell.length, 20, 'with the bridge env set there must be one more tool')
  const shell = withShell.find((tool) => tool.name === 'run_command')
  assert.ok(shell, 'run_command must be registered when the bridge env is present')
  assert.equal(shell.output.schema.type, 'string')
  assert.equal(shell.parameters.cmd.required, true)
  // 没注入 env 的第一次 apply 里不能有它（上面那条 names 断言就是这件事）。
  assert.ok(!names.includes('run_command'))
  if (saved === undefined) delete process.env.FACTOTUM_PROOT
  else process.env.FACTOTUM_PROOT = saved
  console.log('ok: run_command 仅在注入桥 env 时注册')
}

// 系统提示里的设备时间：模型没有时钟，「明天下午三点」得先知道今天几号。
// 实测缺了它，模型会去开时钟、点进世界时钟读日期，四次工具调用全浪费。
{
  const vars = new Map()
  plugin.apply({
    on: () => {},
    tools: { register: () => {} },
    systemPrompt: { variable: (name, provider) => vars.set(name, provider) },
  })
  assert.ok(vars.has('device_now'), 'device_now variable must be registered')

  const text = vars.get('device_now')({})
  assert.match(
    text,
    /^\d{4}-\d{2}-\d{2} \d{2}:\d{2} 周[日一二三四五六]（UTC[+-]\d{2}:\d{2}）$/,
    `device_now must render as a readable local timestamp, got ${text}`,
  )

  // 固定时刻验算一遍偏移与星期：时区来自运行环境，所以只钉住「同一天同一分钟的换算」。
  const fixed = new Date(2026, 9, 25, 2, 17) // 本地时间 2026-10-25 02:17
  const rendered = plugin.formatDeviceNow(fixed)
  assert.ok(rendered.startsWith('2026-10-25 02:17 '), rendered)
  assert.ok(rendered.includes('周日'), `2026-10-25 is a Sunday, got ${rendered}`)
  console.log(`ok: 系统提示变量 device_now = ${rendered}`)
}

// 写操作的闸门：日历与闹钟现在是**静默**的（系统界面不再替你确认），所以必须在插件层
// 判成 `ask` —— 手机确认面板是唯一的闸门。读工具与界面动作必须原样放行。
{
  let handler
  plugin.apply({
    on: (event, fn) => {
      if (event === 'tools/pre-execute') handler = fn
    },
    tools: { register: () => {} },
  })
  assert.equal(typeof handler, 'function', '插件必须注册 tools/pre-execute 闸门')

  let passed = 0
  const next = () => {
    passed += 1
  }
  for (const write of [
    'add_calendar_event',
    'update_calendar_event',
    'delete_calendar_event',
    'set_alarm',
    'schedule_task',
    'update_scheduled_task',
    'delete_scheduled_task',
  ]) {
    const decision = handler({ name: write, arguments: { id: 't1', title: '理发', hour: 7, minute: 30 } }, next)
    assert.equal(decision?.kind, 'ask', `${write} 必须经审批`)
    assert.ok(decision.reason.length > 0, `${write} 需要给审计日志的 reason`)
    assert.ok(decision.displayReason?.zh, `${write} 需要给确认面板的 displayReason`)
  }
  // run_command 也走同一条缝：审计里只有动作类别，面板上必须看得见命令原文。
  const shell = handler({ name: 'run_command', arguments: { cmd: 'apt-get install -y curl' } }, next)
  assert.equal(shell?.kind, 'ask', 'run_command 必须经审批')
  assert.equal(shell.reason, '执行 shell 命令', '审计日志里不能出现命令原文')
  assert.match(shell.displayReason.zh, /apt-get install -y curl/, '确认面板要看得见命令')

  const readDecision = handler({ name: 'read_ui_tree', arguments: {} }, next)
  assert.equal(readDecision, undefined, '读工具必须走 next() 放行')
  assert.equal(passed, 1, '读工具应当恰好放行一次')
  console.log('ok: 写工具（日历 / 闹钟 / 定时任务 / shell）判成 ask，读工具放行')
}

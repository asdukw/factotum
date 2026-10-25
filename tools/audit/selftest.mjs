// 离线自检：审计回路里最容易写错、又最不该等插上设备才发现的两处 ——
//  1) zstd 逐帧切分（会话是「一帧一条记录」的追加文件，Node 的 zstd 不跨帧）
//  2) 归一化与指标（尤其图像：它**不在** session 里，只有 sha256 引用）
//
//   node tools/audit/selftest.mjs
import assert from 'node:assert/strict'
import zlib from 'node:zlib'
import {
  ACTION_TOOLS,
  EMPTY_TREE_MARKER,
  OBSERVE_TOOLS,
  decodeSessionFrames,
  frameLength,
  mergeMarkers,
  normalize,
  parseAuditLog,
} from './lib.mjs'

// ===========================================================================
// 一、zstd 逐帧切分
// ===========================================================================
const records = [
  { type: 'session', seq: 1, time: 1000, data: { agentPreset: 'phone' } },
  { type: 'agent/inbox/spliced', seq: 2, time: 1100, data: { inserted: [{ content: '打开设置' }] } },
  {
    type: 'assistant/message',
    seq: 3,
    time: 1200,
    data: { message: { content: [{ type: 'tool-call', id: 'c1', name: 'read_ui_tree', arguments: '{}' }] } },
  },
  {
    type: 'tool/result',
    seq: 4,
    time: 1650,
    data: { message: { toolCallId: 'c1', isError: false, content: [{ type: 'text', text: 'pkg=com.android.settings nodes=42' }] } },
  },
  { type: 'turn/end', seq: 5, time: 1700, data: { reason: { kind: 'completed' } } },
]

const frames = records.map((r) => zlib.zstdCompressSync(Buffer.from(JSON.stringify(r) + '\n', 'utf8')))
const raw = Buffer.concat(frames)

let off = 0
for (let i = 0; i < frames.length; i++) {
  const len = frameLength(raw, off)
  assert.equal(len, frames[i].length, `第 ${i} 帧长度不符`)
  off += len
}
assert.equal(off, raw.length, '切完所有帧后应正好等于总长度')

const decoded = decodeSessionFrames(raw)
assert.equal(decoded.frames, records.length, '帧数不符')
assert.deepEqual(
  decoded.text.split('\n').filter(Boolean).map((l) => JSON.parse(l)),
  records,
  '解出来的记录与原始不符',
)

assert.equal(decodeSessionFrames(zlib.zstdCompressSync(Buffer.from('{"a":1}\n'))).frames, 1)
assert.throws(() => decodeSessionFrames(Buffer.from('not zstd at all')), /不是 zstd 帧/)

// ===========================================================================
// 二、归一化与指标
// ===========================================================================
const ATT = 'a'.repeat(64)
const trace = [
  { type: 'session', seq: 1, time: 1000, data: { agentPreset: 'phone' } },
  { type: 'agent/inbox/spliced', seq: 2, time: 1000, data: { inserted: [{ content: '打开微信看看' }] } },

  // 连续两次读屏（第二次空树）→ 测空转连击与空树识别
  { type: 'assistant/message', seq: 3, time: 2000, data: { message: { content: [
    { type: 'reasoning', text: '先看看屏幕' },
    { type: 'tool-call', id: 'c1', name: 'read_ui_tree', arguments: '{}' },
  ] } } },
  { type: 'tool/result', seq: 4, time: 2400, data: { message: { toolCallId: 'c1', isError: false,
    content: [{ type: 'text', text: 'pkg=com.tencent.mm nodes=1' }] } } },
  { type: 'assistant/message', seq: 5, time: 3000, data: { message: { content: [
    { type: 'tool-call', id: 'c2', name: 'read_ui_tree', arguments: '{}' },
  ] } } },
  { type: 'tool/result', seq: 6, time: 3300, data: { message: { toolCallId: 'c2', isError: false,
    content: [{ type: 'text', text: `（当前界面${EMPTY_TREE_MARKER}……）` }] } } },

  // 截图：文本块是 summary，图像块只有 sha256 引用 —— 体积必须从 summary 里读
  { type: 'assistant/message', seq: 7, time: 4000, data: { message: { content: [
    { type: 'tool-call', id: 'c3', name: 'screenshot', arguments: '{}' },
  ] } } },
  { type: 'tool/result', seq: 8, time: 4900, data: { message: { toolCallId: 'c3', isError: false, content: [
    { type: 'text', text: '截图 1080x2400 png 123KB' },
    { type: 'image', attachment: ATT },
  ] } } },

  // 一个动作 + 一个失败的工具
  { type: 'assistant/message', seq: 9, time: 5000, data: { message: { content: [
    { type: 'tool-call', id: 'c4', name: 'tap_point', arguments: '{"x":10,"y":20}' },
  ] } } },
  { type: 'tool/result', seq: 10, time: 5400, data: { message: { toolCallId: 'c4', isError: true,
    content: [{ type: 'text', text: 'dispatchGesture 返回 false' }] } } },

  { type: 'turn/end', seq: 11, time: 6000, data: { reason: { kind: 'completed' } } },
]

const { events, metrics } = normalize(trace)

assert.equal(events.length, 11, '事件数不符')
assert.equal(events[0].seq, 1, 'seq 应从 1 开始')
assert.equal(events[1].type, 'task')
assert.equal(events[1].text, '打开微信看看')

assert.equal(metrics.records, 11)
assert.equal(metrics.steps, 4, '助手回合数')
assert.equal(metrics.toolCalls.total, 4)
assert.deepEqual(metrics.toolCalls.byTool, { read_ui_tree: 2, screenshot: 1, tap_point: 1 })
assert.equal(metrics.toolErrors.total, 1)
assert.deepEqual(metrics.toolErrors.byTool, { tap_point: 1 })

assert.equal(metrics.observeCalls, 3, 'read_ui_tree x2 + screenshot')
assert.equal(metrics.actionCalls, 1)
assert.deepEqual(metrics.unknownTools, [])

// 视觉路径的新工具名必须被分类认出来 —— 漏了会让 observeCalls / actionCalls 静默偏低，
// 而这两个数正是「模型在盲点还是在读屏」的判据。
assert.ok(OBSERVE_TOOLS.has('capture_screenshot'), 'capture_screenshot 算观察')
assert.ok(ACTION_TOOLS.has('tap_target'), 'tap_target 算动作')
assert.ok(ACTION_TOOLS.has('long_press_target'), 'long_press_target 算动作')

assert.equal(metrics.emptyTreeReads, 1, '应识别出一次空树')
assert.equal(metrics.maxReadScreenStreak, 2, '连续两次读屏')

// 这一条是回归测试：早期版本把 tool_result 的文本字符数当成截图体积，
// 量到的是 "截图 1080x2400 png 123KB" 这 25 个字符，与图像本身无关。
assert.equal(metrics.images.count, 1)
assert.equal(metrics.images.declaredBytes, 123 * 1024, '图像体积必须从 summary 解析，不是文本长度')
assert.equal(metrics.images.fetchedBytes, null, '未开 --with-attachments 时不应有拉取体积')
assert.ok(
  !JSON.stringify(metrics).includes('"screenshotBytes"'),
  '旧的 screenshotBytes 指标已废弃，不该再出现',
)

// 文本载荷只算文本块，图像不计入
assert.equal(metrics.payloadChars, 'pkg=com.tencent.mm nodes=1'.length + `（当前界面${EMPTY_TREE_MARKER}……）`.length + '截图 1080x2400 png 123KB'.length + 'dispatchGesture 返回 false'.length)

assert.equal(metrics.wallClockMs, 6000 - 1000)
assert.equal(metrics.endedCleanly, true)
assert.equal(metrics.endOk, true)

// 缺 turn/end 时必须如实标未收尾，而不是当成功
const partial = normalize(trace.slice(0, 8))
assert.equal(partial.metrics.endedCleanly, false)
assert.equal(partial.metrics.endOk, null)

// 出错收尾
const failed = normalize([...trace.slice(0, 10), { type: 'turn/end', seq: 11, time: 6000, data: { reason: { kind: 'error' } } }])
assert.equal(failed.metrics.endedCleanly, true)
assert.equal(failed.metrics.endOk, false)

// ===========================================================================
// 三、设备审计标记（logcat → 事件流）
// ===========================================================================
const LOGCAT = [
  '--------- beginning of main',
  '1750000000.123  1234  1234 I FactotumAudit: ev=exploration_request want=true changed=true',
  '1750000000.400  1234  1234 I FactotumProbe: 这是无关的行，不该被收进来',
  '1750000000.500  1234  1234 D FactotumAudit: 没有 ev 字段，跳过',
  '1750000600.000  1234  1234 I FactotumAudit: ev=exploration_observed active=false',
  '',
].join('\n')

const markers = parseAuditLog(LOGCAT)
assert.equal(markers.length, 2, '只应收下带 ev 的 FactotumAudit 行')
assert.equal(markers[0].t, 1750000000123, 'epoch.msec 要换算成毫秒')
assert.equal(markers[0].ev, 'exploration_request')
assert.equal(markers[0].fields.want, 'true')
assert.equal(markers[0].fields.changed, 'true')
assert.equal(markers[1].t, 1750000600000)

// 按时间插进事件流，并丢掉窗口外的（logcat 缓冲区常有上一次任务的残留）
const baseEvents = [
  { seq: 1, t: 1750000000500, type: 'assistant' },
  { seq: 2, t: 1750000000900, type: 'tool_result', tool: 'tap_point' },
]
const m = mergeMarkers(baseEvents, markers, 2000)
assert.equal(m.merged, 1, '近的那条应被并入')
assert.equal(m.dropped, 1, '远的那条应被丢掉，且如实计数')
assert.deepEqual(m.events.map((e) => e.type), ['device_marker', 'assistant', 'tool_result'])
assert.deepEqual(m.events.map((e) => e.seq), [1, 2, 3], 'seq 要重编号')
assert.equal(m.events[0].event, 'exploration_request')

// 没有任何时间戳时不能崩，也不能假装并进去了
const noTs = mergeMarkers([{ seq: 1, t: null, type: 'task' }], markers)
assert.equal(noTs.merged, 0)
assert.equal(noTs.dropped, 2)

console.log('✓ selftest 通过：zstd 逐帧切分 / 归一化 / 指标 / 图像计量 / 设备标记合并')

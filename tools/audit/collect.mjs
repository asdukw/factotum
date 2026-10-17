// 把一次 dsh 会话的原始记录，归一化成**机器可读**的轨迹与指标。
//
// 存在的理由：审计要回答的是「这次执行对不对、路径能不能更省」，而这是**对比题**。
// 早期的手工脚本把轨迹打印成给人看的散文（思考裁 700 字、结果裁 260 字），
// 读完只能说印象，跨两次运行无法比较。这里产出两样东西：
//   events.jsonl —— 每步一行（谁、用什么工具、什么参数、成没成、多久）
//   metrics.json —— 可累积、可对比的指标
//
// 用法：
//   node tools/audit/collect.mjs --label wechat-send-01
//   node tools/audit/collect.mjs --label baseline --session <会话目录名>
//
// 产物落在 tools/audit/runs/（gitignore）：**里面含屏幕正文，是敏感数据**。
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import {
  RUNS_DIR,
  decodeSessionFrames,
  dumpAuditLog,
  findSessionsContainer,
  listSessions,
  mergeMarkers,
  normalize,
  parseAuditLog,
  pullAttachment,
  pullSessionFile,
  requireDevice,
  resolvePackage,
} from './lib.mjs'

const argv = process.argv.slice(2)
const arg = (name, dflt = null) => {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt
}
const flag = (name) => argv.includes(`--${name}`)

const LABEL = arg('label') ?? `run-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}`
const MAX_PAYLOAD = Number(arg('max-payload', '600'))
const KEEP_RAW = !flag('no-raw')
// 附图默认不拉：那是**全量屏幕内容**，比 trace 文本更敏感，体积也大。
// 需要判断「截图兜底到底管不管用」时再开。
const WITH_ATTACHMENTS = flag('with-attachments')
// 默认现 dump 一次 logcat 的审计标记缓冲区；给了文件就用文件（例如 run-suite 期间流的）
const LOGCAT_FILE = arg('logcat')

function main() {
  requireDevice()
  const pkg = resolvePackage()
  const container = findSessionsContainer(pkg)
  const sessions = listSessions(pkg, container)
  if (!sessions.length) throw new Error('没有会话目录')
  const session = arg('session') ?? sessions[0]
  if (!sessions.includes(session)) {
    throw new Error(`找不到会话 ${session}；现有：${sessions.slice(0, 5).join(', ')}`)
  }

  const raw = pullSessionFile(pkg, container, session)
  const { text, frames } = decodeSessionFrames(raw)
  const records = text
    .split(/\r?\n/)
    .filter(Boolean)
    .map((l) => {
      try {
        return JSON.parse(l)
      } catch {
        return null
      }
    })
    .filter(Boolean)

  // 归一化与指标计算在 lib.mjs 的 normalize() 里（纯函数，selftest 覆盖）
  const { events: rawEvents, metrics: base } = normalize(records, MAX_PAYLOAD)

  // ---- 设备审计标记：并进事件流 ----
  // 触摸探索的实际状态不在 dsh 会话里，只在 logcat。没有它，审计看到手势类动作失败时
  // 分不清是「预期行为」（用户自开 TalkBack、手势本来就该被吞）还是真故障。
  const logcatText = LOGCAT_FILE
    ? existsSync(LOGCAT_FILE)
      ? readFileSync(LOGCAT_FILE, 'utf8')
      : ''
    : dumpAuditLog()
  if (LOGCAT_FILE && !logcatText) console.error(`  ⚠ --logcat 指定的文件不存在或为空：${LOGCAT_FILE}`)

  const markers = parseAuditLog(logcatText)
  const { events, merged, dropped } = mergeMarkers(rawEvents, markers)
  const markersByEvent = {}
  for (const e of events) {
    if (e.type === 'device_marker') markersByEvent[e.event] = (markersByEvent[e.event] ?? 0) + 1
  }
  if (!markers.length) {
    console.error('  ⚠ 一条审计标记都没抓到：logcat 被冲掉了，或 App 侧 AUDIT_TAG 改了没同步')
  }

  const metrics = {
    label: LABEL,
    session,
    package: pkg,
    collectedAt: new Date().toISOString(),
    frames,
    ...base,
    deviceMarkers: { total: merged, dropped, byEvent: markersByEvent },
  }

  // ---- 落盘 ----
  const dir = join(RUNS_DIR, LABEL)
  mkdirSync(dir, { recursive: true })

  // 附图按需拉：没有它，审计只能看到「截了一张 1080x2400 的图」，
  // 判断不了模型当时到底看见了什么 —— 而「截图兜底管不管用」正是要审的点。
  if (WITH_ATTACHMENTS && metrics.images.count) {
    const ids = [...new Set(events.flatMap((e) => e.attachments ?? []))]
    const adir = join(dir, 'attachments')
    mkdirSync(adir, { recursive: true })
    let bytes = 0
    for (const id of ids) {
      try {
        const buf = pullAttachment(pkg, id)
        writeFileSync(join(adir, id), buf)
        bytes += buf.length
      } catch (e) {
        console.error(`  ⚠ 附件 ${id.slice(0, 12)}… 拉取失败：${e.message}`)
      }
    }
    metrics.images.fetchedBytes = bytes
    console.log(`  附图：${ids.length} 张 / ${(bytes / 1024).toFixed(0)}KB → runs/${LABEL}/attachments/`)
  }

  if (logcatText) writeFileSync(join(dir, 'device-log.txt'), logcatText)
  writeFileSync(join(dir, 'events.jsonl'), events.map((e) => JSON.stringify(e)).join('\n') + '\n')
  writeFileSync(join(dir, 'metrics.json'), JSON.stringify(metrics, null, 2) + '\n')
  if (KEEP_RAW) writeFileSync(join(dir, 'raw.zstd'), raw)

  // 累积索引：跨运行的同一模式只能在这里看出来
  const indexPath = join(RUNS_DIR, 'index.json')
  const index = existsSync(indexPath) ? JSON.parse(readFileSync(indexPath, 'utf8')) : { runs: [] }
  index.runs = index.runs.filter((r) => r.label !== LABEL)
  index.runs.push({
    label: LABEL,
    session,
    at: metrics.collectedAt,
    steps: metrics.steps,
    toolCalls: metrics.toolCalls.total,
    errors: metrics.toolErrors.total,
    emptyTreeReads: metrics.emptyTreeReads,
    maxReadScreenStreak: metrics.maxReadScreenStreak,
    images: metrics.images.count,
    imageBytes: metrics.images.declaredBytes,
    payloadChars: metrics.payloadChars,
    wallClockMs: metrics.wallClockMs,
    endedCleanly: metrics.endedCleanly,
  })
  writeFileSync(indexPath, JSON.stringify(index, null, 2) + '\n')

  console.log(`[audit] ${LABEL}  session=${session}`)
  console.log(
    `  步数=${metrics.steps} 工具调用=${metrics.toolCalls.total} 错误=${metrics.toolErrors.total}` +
      ` 观察/动作=${metrics.observeCalls}/${metrics.actionCalls}` +
      ` 空树读屏=${metrics.emptyTreeReads} 最长连读=${metrics.maxReadScreenStreak}` +
      ` 设备标记=${metrics.deviceMarkers.total}`,
  )
  console.log(
    `  载荷=${metrics.payloadChars} 字符` +
      (metrics.images.count
        ? ` + 附图 ${metrics.images.count} 张/${(metrics.images.declaredBytes / 1024).toFixed(0)}KB`
        : '') +
      ` 墙钟=${metrics.wallClockMs ?? '?'} ms 收尾=${metrics.endedCleanly}`,
  )
  console.log(`  产物：tools/audit/runs/${LABEL}/`)
}

try {
  main()
} catch (e) {
  console.error(`[audit] ${e.message}`)
  process.exit(1)
}

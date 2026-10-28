// Zoneu 任务审计：公共部分（设备取数、zstd 逐帧解码、包名解析）。
//
// 设计原则：**应用身份只有一个来源** —— `android/app/build.gradle.kts` 的 applicationId。
// 上一轮改名时有一批手工脚本因为硬编码 `com.memex.app` 一起失效，其中就包括拉 session 的
// 那几个 —— 而且是静默失效的。这里不重复那个错：需要包名就现读，读不到就报错，不做默认值。
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import zlib from 'node:zlib'

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
export const RUNS_DIR = join(ROOT, 'tools/audit/runs')

/** adb 包装。二进制传输必须用 exec-out（见 pullSessionFile 的注释）。 */
export function adb(args, opts = {}) {
  return execFileSync('adb', args, { maxBuffer: 1 << 28, encoding: 'buffer', ...opts })
}

export function adbText(args) {
  return adb(args).toString('utf8')
}

/**
 * 设备必须在线。离线时给人话，而不是把 adb 的堆栈甩出来。返回序列号。
 *
 * 多设备时不能靠 adb 自己挑：裸 `adb` 会直接报「more than one device」。
 * 这里选定一台并写进 `ANDROID_SERIAL`，后续所有裸 adb 调用自动跟随。
 * 优先真实设备（模拟器上跑出来的审计数据不能代表真机），可用 `ANDROID_SERIAL` 覆盖。
 */
export function requireDevice() {
  let out = ''
  try {
    out = execFileSync('adb', ['devices'], { encoding: 'utf8' })
  } catch {
    throw new Error('调不到 adb：确认 Android SDK 的 platform-tools 在 PATH 里。')
  }
  const ready = out
    .split(/\r?\n/)
    .slice(1)
    .map((l) => l.trim())
    .filter((l) => l && !l.includes('offline') && /\bdevice\b/.test(l))
  if (!ready.length) {
    throw new Error(
      '没有在线的 adb 设备。审计要读真机上的会话文件：先连上设备、`adb devices` 确认，再重跑。',
    )
  }
  const serials = ready.map((l) => l.split(/\s+/)[0])
  const serial =
    process.env.ANDROID_SERIAL && serials.includes(process.env.ANDROID_SERIAL)
      ? process.env.ANDROID_SERIAL
      : (serials.find((s) => !s.startsWith('emulator-')) ?? serials[0])
  // 子进程（execFileSync('adb', …)）继承这个变量，后面不用每条都加 -s。
  process.env.ANDROID_SERIAL = serial
  return serial
}

/**
 * 设备上本 App 的包名。ZONEU_PACKAGE 可覆盖（例如临时装了个改名的调试包）。
 *
 * debug 包可能带 `applicationIdSuffix`（`android/local.properties` 的 `zoneu.debugSuffix`，
 * 用于并行 worktree 共存）。后缀是构建时拼上去的，这里必须一起读，否则审计会去找一个
 * 根本没装的身份，表现为「会话目录为空」——又是一次静默失效。
 */
export function resolvePackage() {
  if (process.env.ZONEU_PACKAGE || process.env.FACTOTUM_PACKAGE) return process.env.ZONEU_PACKAGE || process.env.FACTOTUM_PACKAGE
  const gradle = readFileSync(join(ROOT, 'android/app/build.gradle.kts'), 'utf8')
  const m = gradle.match(/applicationId\s*=\s*"([^"]+)"/)
  if (!m) throw new Error('在 android/app/build.gradle.kts 里找不到 applicationId')
  return m[1] + debugSuffix()
}

function debugSuffix() {
  const file = join(ROOT, 'android/local.properties')
  if (!existsSync(file)) return ''
  const text = readFileSync(file, 'utf8')
  const m = text.match(/^\s*zoneu\.debugSuffix\s*=\s*(\S+)\s*$/m)
    ?? text.match(/^\s*factotum\.debugSuffix\s*=\s*(\S+)\s*$/m)
  return m ? m[1] : ''
}

/** 返回 buf 中第 i 个字节处那个 zstd 帧的总长度（RFC 8878）。 */
export function frameLength(buf, start) {
  if (buf.readUInt32LE(start) !== 0xfd2fb528) throw new Error(`不是 zstd 帧 @${start}`)
  let p = start + 4
  const fhd = buf[p++]
  const fcsFlag = fhd >> 6
  const singleSegment = (fhd >> 5) & 1
  const checksum = (fhd >> 2) & 1
  const dictFlag = fhd & 3
  if (!singleSegment) p += 1 // Window_Descriptor
  p += [0, 1, 2, 4][dictFlag]
  let fcsSize = [0, 2, 4, 8][fcsFlag]
  if (fcsFlag === 0 && singleSegment) fcsSize = 1
  p += fcsSize
  for (;;) {
    const h = buf.readUIntLE(p, 3)
    p += 3
    const last = h & 1
    const type = (h >> 1) & 3
    const size = h >> 3
    if (type === 0 || type === 2) p += size
    else if (type === 1) p += 1
    else throw new Error(`保留块类型 @${p}`)
    if (last) break
  }
  if (checksum) p += 4
  return p - start
}

/** 会话文件是「一帧一条记录」的追加文件，Node 的 zstd 不跨帧 → 手工切帧后逐帧解。 */
export function decodeSessionFrames(raw) {
  const parts = []
  let off = 0
  let frames = 0
  while (off < raw.length) {
    const len = frameLength(raw, off)
    parts.push(zlib.zstdDecompressSync(raw.subarray(off, off + len)).toString('utf8'))
    off += len
    frames++
  }
  return { text: parts.join(''), frames }
}

/** 同 [findSessionsContainer]，但目录还不存在时返回 null —— 全新安装后第一次跑任务前就是这种状态。 */
export function findSessionsContainerOrNull(pkg) {
  const base = `files/dsh-home/sessions`
  const out = adbText(['shell', `run-as ${pkg} ls ${base} 2>/dev/null || true`])
  const names = out
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith('total'))
  if (!names.length) return null
  // 正常情况下只有一个容器目录；多个就取最新的
  if (names.length === 1) return `${base}/${names[0]}`
  const listed = adbText(['shell', `run-as ${pkg} ls -t ${base}`])
  const newest = listed.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0]
  return `${base}/${newest}`
}

/** sessions/ 下的容器目录名内嵌包名，不硬编码，直接列出来找。 */
export function findSessionsContainer(pkg) {
  const container = findSessionsContainerOrNull(pkg)
  if (!container) throw new Error(`files/dsh-home/sessions 为空：App 还没跑过任务？`)
  return container
}

/** 列出容器下的会话目录，最新的在前。 */
export function listSessions(pkg, container) {
  const out = adbText(['shell', `run-as ${pkg} ls -t ${container}`])
  return out
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith('total'))
}

/** 拉一个会话的原始字节。 */
export function pullSessionFile(pkg, container, session) {
  const rel = `${container}/${session}/session.v4.jsonl.zstd`
  // 必须 exec-out：`adb shell cat` 会把二进制里的 \n 翻成 \r\n，zstd 流就废了。
  return adb(['exec-out', `run-as ${pkg} cat ${rel}`])
}

/** 会话文件当前字节数，用于等待任务跑完（看它不再增长）。 */
export function sessionSize(pkg, container, session) {
  const rel = `${container}/${session}/session.v4.jsonl.zstd`
  const out = adbText(['shell', `run-as ${pkg} stat -c %s ${rel} 2>/dev/null || echo -1`])
  return Number.parseInt(out.trim(), 10)
}

/**
 * 按 id 拉一张附图。附件按 sha256 内容寻址，路径可直接推导
 * （见 tools/dsh-phone/android-attachments：root = <dsh-home>/attachments/<STORE_VERSION>）。
 * 图像**不在** session jsonl 里，只在里面留一个 attachmentId；要判断「截图兜底到底管不管用」
 * 就得把图拉下来看。注意这是全量屏幕内容，比 trace 文本更敏感。
 */
export function pullAttachment(pkg, id, storeVersion = 'android-v1') {
  if (!/^[0-9a-f]{64}$/.test(id)) throw new Error(`附件 id 不像 sha256：${id.slice(0, 16)}…`)
  return adb(['exec-out', `run-as ${pkg} cat files/dsh-home/attachments/${storeVersion}/${id}`])
}

// ---------------------------------------------------------------------------
// 归一化：dsh 原始记录 → 轨迹事件 + 指标。
// 做成纯函数是为了能离线自检 —— 这段逻辑最容易写错，又不该等到插上设备才发现。
// ---------------------------------------------------------------------------

/** 控件树为空时无障碍层会回这段提示（DeviceAction.EMPTY_TREE_HINT）。 */
export const EMPTY_TREE_MARKER = '几乎没有可读节点'

// 旧名字（screenshot / tap_point）**留着**：runs/index.json 是累积的，历史 run 里就是这些名字，
// 删掉会让跨运行对比冒出假的 unknownTools 跳变。新名字见插件 v0.4.0（capture_screenshot / tap_target）。
export const OBSERVE_TOOLS = new Set(['read_ui_tree', 'screenshot', 'capture_screenshot'])
export const ACTION_TOOLS = new Set([
  'tap_node',
  'tap_point',
  'tap_target',
  'long_press_target',
  'long_press',
  'type_text',
  'press',
  'swipe',
  'launch_app',
])

const textOf = (c) =>
  Array.isArray(c)
    ? c.map((b) => b.text ?? b.content ?? '').filter((x) => typeof x === 'string').join('')
    : String(c ?? '')

/**
 * @param {object[]} records dsh 会话文件逐行解析出的记录
 * @param {number} maxPayload 事件里载荷片段的最大字符数（仅为可读性，不是脱敏）
 * @returns {{events: object[], metrics: object}} metrics 不含 label/session/package/时间戳
 */export function normalize(records, maxPayload = 600) {
  const clip = (s, n = maxPayload) => {
    const t = String(s ?? '').replace(/\s+/g, ' ').trim()
    return t.length > n ? t.slice(0, n) + `…(+${t.length - n})` : t
  }

  const events = []
  const callNames = new Map()
  let seq = 0
  const push = (e) => events.push({ seq: ++seq, ...e })

  for (const rec of records) {
    const d = rec.data ?? {}
    const t = rec.time ?? null
    switch (rec.type) {
      case 'session':
        push({ t, type: 'session', preset: d.agentPreset ?? null })
        break
      case 'agent/inbox/spliced':
        for (const m of d.inserted ?? []) push({ t, type: 'task', text: clip(textOf(m.content), 400) })
        break
      case 'assistant/message': {
        const calls = []
        let textOut = ''
        let reasoning = ''
        for (const b of d.message?.content ?? []) {
          if (b.type === 'reasoning') reasoning += b.text ?? ''
          else if (b.type === 'text') textOut += b.text ?? ''
          else if (b.type === 'tool-call') {
            calls.push({ id: b.id, name: b.name, args: clip(b.arguments, 300) })
            callNames.set(b.id, b.name)
          }
        }
        push({ t, type: 'assistant', text: clip(textOut, 400), reasoning: clip(reasoning, 400), calls })
        break
      }
      case 'tool/result': {
        const m = d.message ?? {}
        const body = textOf(m.content)
        // 图像**不在** session 里：附件按 sha256 内容寻址单独存，session 只留引用。
        // 所以图像体积只能从 summary（"截图 1080x2400 png 123KB"）里读。
        const blocks = Array.isArray(m.content) ? m.content : []
        const atts = blocks
          .filter((b) => b && b.type === 'image' && b.attachment)
          .map((b) => b.attachment)
        const declaredKB = atts.length
          ? [...body.matchAll(/(\d+)KB/g)].reduce((a, x) => a + Number(x[1]), 0)
          : 0
        push({
          t,
          type: 'tool_result',
          callId: m.toolCallId ?? null,
          tool: callNames.get(m.toolCallId) ?? '?',
          error: m.isError === true,
          chars: body.length,
          emptyTree: body.includes(EMPTY_TREE_MARKER),
          attachments: atts,
          imageBytes: declaredKB * 1024,
          excerpt: clip(body),
        })
        break
      }
      case 'turn/end':
        push({
          t,
          type: 'turn_end',
          ok: d.reason?.kind !== 'error',
          reason: clip(JSON.stringify(d.reason ?? {}), 200),
        })
        break
      default:
        break
    }
  }

  const results = events.filter((e) => e.type === 'tool_result')
  const byTool = {}
  const errByTool = {}
  for (const r of results) {
    byTool[r.tool] = (byTool[r.tool] ?? 0) + 1
    if (r.error) errByTool[r.tool] = (errByTool[r.tool] ?? 0) + 1
  }

  // 空转：连续 read_ui_tree 而不做任何动作的最长连击
  let streak = 0
  let maxStreak = 0
  for (const r of results) {
    if (r.tool === 'read_ui_tree') maxStreak = Math.max(maxStreak, ++streak)
    else if (ACTION_TOOLS.has(r.tool)) streak = 0
  }

  const times = events.map((e) => e.t).filter((x) => typeof x === 'number')
  const endRec = [...events].reverse().find((e) => e.type === 'turn_end')

  const metrics = {
    records: records.length,
    steps: events.filter((e) => e.type === 'assistant').length,
    toolCalls: { total: results.length, byTool },
    toolErrors: { total: Object.values(errByTool).reduce((a, b) => a + b, 0), byTool: errByTool },
    observeCalls: results.filter((r) => OBSERVE_TOOLS.has(r.tool)).length,
    actionCalls: results.filter((r) => ACTION_TOOLS.has(r.tool)).length,
    unknownTools: [
      ...new Set(results.filter((r) => !OBSERVE_TOOLS.has(r.tool) && !ACTION_TOOLS.has(r.tool)).map((r) => r.tool)),
    ],
    emptyTreeReads: results.filter((r) => r.emptyTree).length,
    maxReadScreenStreak: maxStreak,
    images: {
      count: results.reduce((a, r) => a + (r.attachments?.length ?? 0), 0),
      declaredBytes: results.reduce((a, r) => a + (r.imageBytes ?? 0), 0),
      fetchedBytes: null,
    },
    // 文本载荷与图像字节分开看：payloadChars 只算工具结果的**文本**，
    // 图像走附件、既不在 session 里也不计入这里。
    payloadChars: results.reduce((a, r) => a + r.chars, 0),
    wallClockMs: times.length >= 2 ? Math.max(...times) - Math.min(...times) : null,
    endedCleanly: Boolean(endRec),
    endOk: endRec ? endRec.ok : null,
    endReason: endRec ? endRec.reason : null,
  }
  return { events, metrics }
}

// ---------------------------------------------------------------------------
// 审计标记：App 侧往 logcat 打的 `ZoneuAudit` 行
// （见 ZoneuAccessibilityService.audit / AUDIT_TAG）。
//
// 为什么需要这条通道：触摸探索开着时 dispatchGesture 会被系统吞掉，点击/滑动失效是
// **预期行为**；关着却失效才是故障。这个状态不在 dsh 会话记录里，
// 不并进事件流，审计就分不清这两类，也就无法归因。
// ---------------------------------------------------------------------------

/** 必须与 ZoneuAccessibilityService.AUDIT_TAG 一致；两边要一起改。 */
export const AUDIT_TAG = 'ZoneuAudit'

/** dump 审计标记的 logcat 缓冲区。`-s` 是设备侧过滤，很快。 */
export function dumpAuditLog() {
  return adbText(['logcat', '-d', '-v', 'epoch', '-s', `${AUDIT_TAG}:I`])
}

/** 清空 logcat。跑任务集前调，让下一次采集只看到本次任务的标记。 */
export function clearLogcat() {
  try {
    adb(['logcat', '-c'])
  } catch {
    /* 清不掉不影响主流程，只是可能混进上一次的标记 */
  }
}

/**
 * 解析 `adb logcat -v epoch -s ZoneuAudit:I` 的输出。
 * 行形如：`1750000000.123  1234  1234 I ZoneuAudit: ev=exploration_request want=true changed=true`
 * 时间戳是 epoch 毫秒 —— 与 dsh 记录的 time（Date.now()）同一时基，可直接排序合并。
 */
export function parseAuditLog(text) {
  const out = []
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const m = line.match(/^\s*(\d{10})\.(\d{1,3})\s+.*?\s+[VDIWEF]\s+(\S+)\s*:\s*(.+)$/)
    if (!m || m[3] !== AUDIT_TAG) continue
    const fields = {}
    for (const kv of m[4].trim().split(/\s+/)) {
      const i = kv.indexOf('=')
      if (i > 0) fields[kv.slice(0, i)] = kv.slice(i + 1)
    }
    if (!fields.ev) continue
    out.push({ t: Number(m[1]) * 1000 + Number(m[2].padEnd(3, '0')), ev: fields.ev, fields })
  }
  return out
}

/**
 * 把设备标记按时间戳并进事件流，并重新编号 seq。
 *
 * 会按事件的时间窗口过滤：logcat 缓冲区里常留着上一次任务的标记，
 * 不过滤就会把别处的状态混进这次审计。丢掉多少条如实返回，不静默吞掉。
 */
export function mergeMarkers(events, markers, slackMs = 2000) {
  const ts = events.map((e) => e.t).filter((x) => typeof x === 'number')
  if (!ts.length) return { events: [...events], merged: 0, dropped: markers.length }
  const lo = Math.min(...ts) - slackMs
  const hi = Math.max(...ts) + slackMs
  const kept = markers.filter((m) => m.t >= lo && m.t <= hi)

  const merged = [...events]
  for (const m of kept) {
    const at = { seq: 0, t: m.t, type: 'device_marker', event: m.ev, fields: m.fields }
    let i = merged.length
    for (let k = 0; k < merged.length; k++) {
      if (typeof merged[k].t === 'number' && merged[k].t > m.t) {
        i = k
        break
      }
    }
    merged.splice(i, 0, at)
  }
  merged.forEach((e, i) => {
    e.seq = i + 1
  })
  return { events: merged, merged: kept.length, dropped: markers.length - kept.length }
}

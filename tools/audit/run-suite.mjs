// 跑一套固定任务，每跑完一个就采一次证。
//
// 任务集固定是刻意的：**没有固定任务集，「快了没有 / 步数少了没有」就无从比较。**
// 这套脚本只负责「跑 + 等 + 采」，判断留给审计 agent。
//
// 用法：
//   node tools/audit/run-suite.mjs                # 跑全部
//   node tools/audit/run-suite.mjs --suite daily   # 跑 suite-daily.json（日常场景）
//   node tools/audit/run-suite.mjs --only calc    # 只跑 id 匹配的
//   node tools/audit/run-suite.mjs --timeout 240  # 单个任务等待上限（秒）
import { execFileSync, spawn } from 'node:child_process'
import { closeSync, openSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  adbText,
  clearLogcat,
  findSessionsContainerOrNull,
  listSessions,
  requireDevice,
  resolvePackage,
  sessionSize,
} from './lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const argv = process.argv.slice(2)
const arg = (n, d = null) => {
  const i = argv.indexOf(`--${n}`)
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : d
}

const TIMEOUT_MS = Number(arg('timeout', '240')) * 1000
const POLL_MS = 3000
const SETTLE_HITS = 2
const ONLY = arg('only')
// 任务集文件：默认 suite.json；--suite daily 读 suite-daily.json，--suite 路径 也认。
const SUITE = arg('suite', null)
const suitePath = !SUITE
  ? join(HERE, 'suite.json')
  : SUITE.endsWith('.json')
    ? SUITE
    : join(HERE, `suite-${SUITE}.json`)

const shq = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`

/** ColorOS 会 defer 后台广播：先把 App 拉到前台再广播。 */
function foreground(pkg) {
  try {
    execFileSync('adb', ['shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1'], {
      stdio: 'ignore',
    })
  } catch {
    /* monkey 偶尔非零退出，不影响后续广播 */
  }
}

function broadcast(pkg, args) {
  // 带空格的参数必须把整条远程命令再包一层引号，否则会被设备 shell 拆词。
  const remote = `am broadcast -n ${pkg}/.node.NodeDebugReceiver -a ${pkg}.debug.NODE ${args}`
  return adbText(['shell', remote]).trim()
}

/** 回桌面：每个任务从同一个起点开始，别继承上一个任务停在的页面。 */
function goHome() {
  try {
    adbText(['shell', 'input', 'keyevent', 'KEYCODE_HOME'])
  } catch {
    /* 按键失败不该让整条审计停摆 */
  }
}

/**
 * 任务级的数据重置（`resetPackages`）。
 *
 * 有些 App 会把上次的输入留在界面上（计算器实测：上次的 37×48 和结果还在），
 * 模型直接读结果就交卷，任务根本没被执行。这类 App 在做基线时必须先清数据。
 */
function resetPackages(task) {
  for (const pkg of task.resetPackages ?? []) {
    try {
      console.log(`  reset ${pkg}: ${adbText(['shell', `pm clear ${pkg}`]).trim()}`)
    } catch {
      console.log(`  reset ${pkg}: 失败（忽略）`)
    }
  }
}

/** 任务是否已在设备侧结束。logcat 里 receiver 收尾会打 `task done` / `task failed:`。 */
function taskFinished() {
  try {
    return /\btask done\b|\btask failed:/.test(adbText(['logcat', '-d', '-s', 'TappetNode:I']))
  } catch {
    return false
  }
}

/** 把 logcat 环形缓冲区调大，作为流式采集之外的兜底。失败就忽略。 */
function growLogBuffer(size = '16M') {
  try {
    adbText(['logcat', '-G', size])
    console.log(`  logcat buffer -> ${size}`)
  } catch {
    console.log('  logcat buffer: 调大失败（忽略）')
  }
}

/**
 * 流式抓审计标记。
 *
 * 不能等任务结束再 `logcat -d`：逐帧日志会冲掉环形缓冲区，标记就没了（实测五条任务全部
 * 报「一条标记都没抓到」）。流式进程边产生边读、直接写文件，缓冲区大小就不再决定成败。
 * 格式必须是 `-v epoch`：collect.mjs 按 epoch 毫秒把标记并进 dsh 事件流。
 */
function startAuditStream(sessionId) {
  const file = join(tmpdir(), `tappet-audit-${sessionId}.log`)
  const fd = openSync(file, 'w')
  const child = spawn('adb', ['logcat', '-v', 'epoch', '-s', 'TappetAudit:I'], {
    stdio: ['ignore', fd, 'ignore'],
    env: process.env,
  })
  child.on('error', () => {})
  return { file, child, fd }
}

async function stopAuditStream(stream) {
  if (!stream) return
  try {
    stream.child.kill()
  } catch {
    /* 已经退出 */
  }
  // 给 adb 一点时间把最后几行落到文件，再关 fd。
  await sleep(300)
  try {
    closeSync(stream.fd)
  } catch {
    /* 已经关了 */
  }
}

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

async function main() {
  requireDevice()
  const pkg = resolvePackage()
  const suite = JSON.parse(readFileSync(suitePath, 'utf8'))
  const tasks = suite.tasks.filter((t) => !ONLY || t.id.includes(ONLY))
  if (!tasks.length) throw new Error('没有匹配的任务')

  console.log(`[suite] 包名=${pkg} 任务数=${tasks.length}`)
  growLogBuffer()

  // 常驻运行时 + SDK initialize，只跑一次
  foreground(pkg)
  await sleep(2000)
  console.log('  ' + broadcast(pkg, '--es op START'))
  await sleep(3000)

  // 全新安装后 sessions/ 还不存在：此时 container 为 null，等第一个任务建出会话后再发现。
  let container = findSessionsContainerOrNull(pkg)
  const before = new Set(container ? listSessions(pkg, container) : [])
  const collected = []

  for (const task of tasks) {
    console.log(`\n[task:${task.id}] ${task.task}`)
    goHome()
    foreground(pkg)
    resetPackages(task)
    await sleep(800)
    // 清空 logcat，让采集到的审计标记只属于这次任务（否则会混进上一次的）
    clearLogcat()
    // 每个任务强制一个新会话：不传 session 时 debug 接收器会复用常驻会话，
    // 所有任务写进同一个文件，既分不清归属，这里的「新会话」判据也永远不成立。
    const sessionId = `audit-${suite.suite}-${task.id}-${Date.now()}`
    // 标记必须边产生边收：任务结束后再 logcat -d，环形缓冲区里早没了。
    const audit = startAuditStream(sessionId)
    await sleep(400)
    broadcast(pkg, `--es op TASK --es task ${shq(task.task)} --es session ${shq(sessionId)}`)

    // 等这轮跑完：新会话出现 + 文件大小不再增长
    const started = Date.now()
    let session = null
    let lastSize = -1
    let stable = 0
    while (Date.now() - started < TIMEOUT_MS) {
      await sleep(POLL_MS)
      if (!container) container = findSessionsContainerOrNull(pkg)
      if (!container) continue
      const now = listSessions(pkg, container)
      // dsh 会在会话 id 后追加一段后缀，所以按前缀认，不做等值比较。
      const fresh =
        now.find((s) => s === sessionId || s.startsWith(`${sessionId}-`)) ??
        now.find((s) => !before.has(s))
      if (!fresh) continue
      session = fresh
      const size = sessionSize(pkg, container, session)
      if (size > 0 && size === lastSize) {
        stable++
        // 文件不再增长还不够：必须等设备侧真的收尾，否则下一条会与未结束的任务重叠。
        if (stable >= SETTLE_HITS && taskFinished()) break
      } else {
        stable = 0
      }
      lastSize = size
    }

    if (!session) {
      console.log(`  ⚠ 超时：没等到新会话（${TIMEOUT_MS / 1000}s）`)
      await stopAuditStream(audit)
      collected.push({ id: task.id, ok: false, reason: 'no-session' })
      continue
    }
    if (stable < SETTLE_HITS) console.log('  ⚠ 等待超时，按当前内容采集（可能未跑完）')
    if (!taskFinished()) console.log('  ⚠ 任务未在超时内结束；下一条任务可能与之重叠')
    await stopAuditStream(audit)

    const label = `${suite.suite}-${task.id}`
    try {
      execFileSync(
        process.execPath,
        [join(HERE, 'collect.mjs'), '--label', label, '--session', session, '--logcat', audit.file],
        { stdio: 'inherit' },
      )
      before.add(session)
      collected.push({ id: task.id, ok: true, label, session })
    } catch (e) {
      console.log(`  ✗ 采集失败：${e.message}`)
      collected.push({ id: task.id, ok: false, reason: 'collect-failed' })
    }
  }

  console.log('\n[suite] 汇总')
  for (const c of collected) console.log(`  ${c.ok ? '✓' : '✗'} ${c.id}${c.label ? ' → ' + c.label : ''} ${c.reason ?? ''}`)
  console.log('\n下一步：把 tools/audit/runs/ 交给 tappet-auditor 审计。')
}

main().catch((e) => {
  console.error(`[suite] ${e.message}`)
  process.exit(1)
})

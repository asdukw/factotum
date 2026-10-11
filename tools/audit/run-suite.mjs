// 跑一套固定任务，每跑完一个就采一次证。
//
// 任务集固定是刻意的：**没有固定任务集，「快了没有 / 步数少了没有」就无从比较。**
// 这套脚本只负责「跑 + 等 + 采」，判断留给审计 agent。
//
// 用法：
//   node tools/audit/run-suite.mjs                # 跑全部
//   node tools/audit/run-suite.mjs --only calc    # 只跑 id 匹配的
//   node tools/audit/run-suite.mjs --timeout 240  # 单个任务等待上限（秒）
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  adbText,
  clearLogcat,
  findSessionsContainer,
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

async function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

async function main() {
  requireDevice()
  const pkg = resolvePackage()
  const suite = JSON.parse(readFileSync(join(HERE, 'suite.json'), 'utf8'))
  const tasks = suite.tasks.filter((t) => !ONLY || t.id.includes(ONLY))
  if (!tasks.length) throw new Error('没有匹配的任务')

  console.log(`[suite] 包名=${pkg} 任务数=${tasks.length}`)

  // 常驻运行时 + SDK initialize，只跑一次
  foreground(pkg)
  await sleep(2000)
  console.log('  ' + broadcast(pkg, '--es op START'))
  await sleep(3000)

  const container = findSessionsContainer(pkg)
  const before = new Set(listSessions(pkg, container))
  const collected = []

  for (const task of tasks) {
    console.log(`\n[task:${task.id}] ${task.task}`)
    foreground(pkg)
    await sleep(800)
    // 清空 logcat，让采集到的审计标记只属于这次任务（否则会混进上一次的）
    clearLogcat()
    broadcast(pkg, `--es op TASK --es task ${shq(task.task)}`)

    // 等这轮跑完：新会话出现 + 文件大小不再增长
    const started = Date.now()
    let session = null
    let lastSize = -1
    let stable = 0
    while (Date.now() - started < TIMEOUT_MS) {
      await sleep(POLL_MS)
      const now = listSessions(pkg, container)
      const fresh = now.find((s) => !before.has(s))
      if (!fresh) continue
      session = fresh
      const size = sessionSize(pkg, container, session)
      if (size > 0 && size === lastSize) {
        if (++stable >= SETTLE_HITS) break
      } else {
        stable = 0
      }
      lastSize = size
    }

    if (!session) {
      console.log(`  ⚠ 超时：没等到新会话（${TIMEOUT_MS / 1000}s）`)
      collected.push({ id: task.id, ok: false, reason: 'no-session' })
      continue
    }
    if (stable < SETTLE_HITS) console.log('  ⚠ 等待超时，按当前内容采集（可能未跑完）')

    const label = `${suite.suite}-${task.id}`
    try {
      execFileSync(
        process.execPath,
        [join(HERE, 'collect.mjs'), '--label', label, '--session', session],
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

// Factotum phone launcher：在 Android 内嵌 Node 上常驻 dsh 运行时（SDK stdio JSON-RPC + Web 表层）。
//
// 不走 dsh 的 boot()/CLI（它硬依赖没有 Android 产物的 node-addon-require-builtin 做包解析），
// 但**用 dsh 自己的 cordis-plugin-loader**：组合 profile rows 后塞进 loader.root，
// 由 loader 负责惰性求值（!!js）、依赖注入与条目管理——dsh-client-modules 等需要
// loader.entries() 的插件才能在手机路径上正常工作（Web 前端的 __ModuleLoader__ / __DSH_BOOT__）。
// 原生 addon 缺失时 loader 会自动降级（lib/index.js 里 requireInternal 是 try/catch）。
//
// 通道：
//   1) SDK stdio JSON-RPC：stdin 收请求（initialize / session/prompt），stdout 发帧。
//      stdout 是协议独占——本文件用 stdout guard 把非 JSON-RPC 行改道 stderr。
//   2) dsh Web 表层：host-webserver 绑 127.0.0.1 随机端口（--port 0），带 token URL。
//      我们把 URL 落到 $FACTOTUM_WORKDIR/web-url.txt 给 Kotlin 读，App 用 WebView 打开。
//
// 目录约定（bundle 根 = 本文件所在目录）：
//   launcher.mjs
//   profiles/phone/{package.json,cordis.patch.yml}
//   node_modules/...（vendored dsh 运行时）
import { Context } from '@deepseek-ai/cordis'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import { loadProfileDirectory, readProfilePatches, composeEntries } from '@deepseek-ai/dsh-app-boot'
import { resolveDshHome } from '@deepseek-ai/dsh-home-paths'
import { existsSync, readFileSync, rmSync, watch, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { installZenSessionFetch } from './zen-session-fetch.mjs'

const runtimeDir = fileURLToPath(new URL('.', import.meta.url))
const profileDir = join(runtimeDir, 'profiles', 'phone')
const installAnchor = join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')
const home = resolveDshHome()
const workDir = process.env.FACTOTUM_WORKDIR ?? home
process.chdir(workDir)

const webUrlFile = join(workDir, 'web-url.txt')
rmSync(webUrlFile, { force: true })

// ── stdout guard ────────────────────────────────────────────────────────────
// stdout 只能走 JSON-RPC 帧；其它插件的 console.log（例如 "dsh web: <token URL>"）
// 一律改道 stderr。Web URL 同时落盘给 Kotlin（文件在 App 私有目录，不进日志）。
const rawWrite = process.stdout.write.bind(process.stdout)
let stdoutBuffer = ''
process.stdout.write = (chunk, encoding, callback) => {
  stdoutBuffer += typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8')
  let newline
  while ((newline = stdoutBuffer.indexOf('\n')) >= 0) {
    const line = stdoutBuffer.slice(0, newline + 1)
    stdoutBuffer = stdoutBuffer.slice(newline + 1)
    if (line.trimStart().startsWith('{"jsonrpc"')) {
      rawWrite(line)
      continue
    }
    const match = /dsh web: (http:\/\/127\.0\.0\.1:(\d+)\/\?token=\S+)/.exec(line)
    if (match !== null) {
      writeFileSync(webUrlFile, match[1])
      process.stderr.write(`[launcher] web ui ready on port ${match[2]}\n`)
      continue
    }
    process.stderr.write(line)
  }
  const done = typeof encoding === 'function' ? encoding : callback
  if (typeof done === 'function') done()
  return true
}

// ── zen 会话头 ──────────────────────────────────────────────────────────────
// opencode zen 硬要求 `x-opencode-session`。LLM 那条路发得出去（pi-ai 适配器有 headers
// 配置），但 dsh 自带的搜索 provider 没有自定义 header 的配置项 —— 而 zen 恰好有
// Anthropic 兼容端点，接上它等于零新增厂商拿到联网搜索。补在这里、只对 zen 的主机名生效，
// 理由与边界写在 zen-session-fetch.mjs 里。
installZenSessionFetch()

// ── profile 组合 ────────────────────────────────────────────────────────────
// 每次 mount 都重新读 profile：重载时可以换掉 provider / models 等 !!js 结果。
// （env 由宿主在 Node 进程启动前设好；同一个进程内重载不会改 env —— 模型清单这类
//  非密配置由宿主写进 runtime-config.json，launcher 在 mount 前读出来覆盖 env。）
function buildRows() {
  const profile = loadProfileDirectory('dsh', profileDir, installAnchor, { userLayer: true })
  const profileContext = {
    name: 'phone',
    dir: profileDir,
    patchPath: profile.patchPath,
    installAnchor,
    startedBundles: [],
    cwd: process.cwd(),
    home,
    overlays: [],
    telemetryDisabledEnv: '1',
  }
  return {
    rows: composeEntries([readProfilePatches('dsh', profileContext, profile)]),
    profileContext,
  }
}

const reloadFile = join(workDir, 'runtime.reload')
const readyFile = join(workDir, 'runtime.ready')
const configFile = join(workDir, 'runtime-config.json')
rmSync(reloadFile, { force: true })
rmSync(readyFile, { force: true })

/**
 * 重载前宿主把**非密配置**写进 runtime-config.json（env 名 → 值）。
 * 同一个 Node 进程里改不了 process.env，所以每次 mount 前在这里覆盖一遍。
 * 密钥不走这个文件：宿主启动和每次 reload 都把每个提供商的 key 预置成 FACTOTUM_LLM_KEY_<ID>，
 * 配置里的 FACTOTUM_LLM_KEY_FROM 指向其中一个 env 名，这里只做一次拷贝。
 */
function applyRuntimeConfig() {
  if (!existsSync(configFile)) return
  try {
    const config = JSON.parse(readFileSync(configFile, 'utf8'))
    const keyFrom = config.FACTOTUM_LLM_KEY_FROM
    if (typeof keyFrom === 'string') {
      process.env.FACTOTUM_LLM_KEY = process.env[keyFrom] ?? ''
    }
    for (const [name, value] of Object.entries(config)) {
      if (name === 'FACTOTUM_LLM_KEY_FROM') continue
      if (typeof value === 'string') process.env[name] = value
    }
    console.error('[launcher] runtime config applied')
  } catch (error) {
    console.error('[launcher] runtime config invalid:', error?.message ?? error)
  }
}

/**
 * 挂载一份完整的 dsh 运行时。返回的 ctx 可以被 root.fiber.dispose() 卸载，
 * 卸载后 SDK stdio transport 会 detach stdin，下一次 mount 重新接管。
 */
async function mountRuntime() {
  applyRuntimeConfig()
  const { rows, profileContext } = buildRows()
  const ctx = new Context()
  ctx.baseUrl = pathToFileURL(runtimeDir + '/').href
  let exitResolve
  const exited = new Promise((resolve) => (exitResolve = resolve))
  ctx.provide('appExit', (code) => {
    console.error(`[launcher] appExit code=${code}`)
    exitResolve()
  })
  // dsh web 表层的命令行：不开浏览器（Android 没有可交棒的桌面浏览器），端口交给 OS 选。
  ctx.provide('cmdlineArgs', { get: () => ['--no-open', '--port', '0'] })
  ctx.provide('profileContext', profileContext)
  ctx.provide('dshHomePath', (path) => join(home, path))

  // Loader 自己提供 loader 服务（异步），再把组合好的 rows 挂到根组。
  await ctx.plugin(Loader)
  await new Promise((resolve) => ctx.inject(['loader'], () => resolve()))
  await ctx.get('loader').root.update(rows)
  await ctx.get('loader').await()
  return { ctx, exited }
}

let current = await mountRuntime()
writeFileSync(readyFile, String(Date.now()))
console.error('[launcher] runtime mounted; stdio JSON-RPC ready')

// ── 按需重载 ────────────────────────────────────────────────────────────────
// 宿主写 runtime.reload（内容随意）→ 卸载整棵 cordis 根 → 重新 mount。
// 不用 process.exit：内嵌 Node 里退出会带走宿主 App 进程。
let reloading = false
watch(workDir, (event, filename) => {
  if (filename !== 'runtime.reload' || reloading) return
  if (!existsSync(reloadFile)) return
  reloading = true
  rmSync(reloadFile, { force: true })
  void (async () => {
    try {
      console.error('[launcher] reload requested; disposing runtime')
      await current.ctx.root.fiber.dispose()
      current = await mountRuntime()
      writeFileSync(readyFile, String(Date.now()))
      console.error('[launcher] reload complete; stdio JSON-RPC ready')
    } catch (error) {
      console.error('[launcher] reload failed:', error?.stack ?? error)
    } finally {
      reloading = false
    }
  })()
})

// 常驻：加载/重载之间的空隙不能退出进程；退出只由宿主进程决定。
await new Promise(() => {})

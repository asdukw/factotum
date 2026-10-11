// Tappet phone launcher：在 Android 内嵌 Node 上常驻 dsh 运行时（SDK stdio JSON-RPC + Web 表层）。
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
//      我们把 URL 落到 $TAPPET_WORKDIR/web-url.txt 给 Kotlin 读，App 用 WebView 打开。
//
// 目录约定（bundle 根 = 本文件所在目录）：
//   launcher.mjs
//   profiles/phone/{package.json,cordis.patch.yml}
//   node_modules/...（vendored dsh 运行时）
import { Context } from '@deepseek-ai/cordis'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import { loadProfileDirectory, readProfilePatches, composeEntries } from '@deepseek-ai/dsh-app-boot'
import { resolveDshHome } from '@deepseek-ai/dsh-home-paths'
import { rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const runtimeDir = fileURLToPath(new URL('.', import.meta.url))
const profileDir = join(runtimeDir, 'profiles', 'phone')
const installAnchor = join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')
const home = resolveDshHome()
const workDir = process.env.TAPPET_WORKDIR ?? home
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

// ── profile 组合 ────────────────────────────────────────────────────────────
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
const rows = composeEntries([readProfilePatches('dsh', profileContext, profile)])

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

console.error('[launcher] runtime mounted; stdio JSON-RPC ready')
// 常驻：jsonrpc server 监听 stdin 保持事件循环；shutdown/退出由宿主进程决定。
// 内嵌 Node 里调用 process.exit() 会 abort 宿主 App 进程（pthread mutex 已销毁），
// 所以这里只等 appExit（正常不会被触发）。
await exited
console.error('[launcher] runtime exited')
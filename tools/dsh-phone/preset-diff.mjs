// 组合对比：把 phone profile 实际组合出来的行，与 dsh 官方预置（preset-standard）比一遍。
//
// **为什么需要它**：我们的 `preset-phone` 是空的（只有 persona + android-tools），而 shipped 的
// `preset-standard` 有 19 条。想知道「dsh 官方有什么、我们缺什么」，靠读 YAML 是读不出来的 ——
// overlay 是好几层叠起来的（dsh-base → dsh-web-app → 我们自己的 patch），
// 而且 `dsh-web-app` 会把宿主级的行禁掉、改成按 preset 组合。
//
// 这个脚本走的就是 launcher 自己那套 API（`loadProfileDirectory` + `readProfilePatches` +
// `composeEntries`），所以它打出来的就是运行时真正 mount 的东西，不是「按 YAML 猜的」。
//
// 用法（PC，需要先跑过 tools/bundle-dsh.ps1 拿到运行时缓存）：
//   node tools/dsh-phone/preset-diff.mjs
//   node tools/dsh-phone/preset-diff.mjs --all          # 连每一行都列出来
//   node tools/dsh-phone/preset-diff.mjs --preset phone # 只看某一个预置
import { dirname, join } from 'node:path'
import { existsSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, '..', '..')
const runtimeDir = join(repoRoot, 'android', '.dsh-cache', 'runtime')
const profileDir = join(runtimeDir, 'profiles', 'phone')

if (!existsSync(join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json'))) {
  console.error('找不到运行时缓存。先跑 tools/bundle-dsh.ps1（注意用 pwsh 7）。')
  process.exit(1)
}

// 动态 import：这些包只在运行时缓存里，仓库本身零依赖。
const { loadProfileDirectory, readProfilePatches, composeEntries } = await import(
  pathToFileURL(join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh-app-boot', 'lib', 'index.js')).href
)
const { resolveDshHome } = await import(
  pathToFileURL(join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh-home-paths', 'lib', 'index.js')).href
)

const installAnchor = join(runtimeDir, 'node_modules', '@deepseek-ai', 'dsh', 'package.json')
const home = resolveDshHome()
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

// 走一遍整棵树，收集：宿主级行、每个 agent-preset 的插件列表。
const hostRows = []
const presets = new Map()
function walk(list, insidePreset) {
  for (const row of list ?? []) {
    if (typeof row?.name === 'string' && row.name === '@deepseek-ai/dsh-agent-preset') {
      const plugins = Array.isArray(row?.config?.plugins) ? row.config.plugins : []
      presets.set(row.id, plugins.map((p) => `${p.id ?? '?'}${p.disabled ? ' (off)' : ''}`))
      // 预置内部的插件行不再算作宿主级行 —— 它们只在选中该预置时生效。
      walk(plugins, true)
      continue
    }
    if (!insidePreset) hostRows.push(row)
    if (Array.isArray(row?.config?.plugins)) walk(row.config.plugins, insidePreset)
    if (Array.isArray(row?.insert)) walk(row.insert, insidePreset)
  }
}
walk(rows, false)

const argv = process.argv.slice(2)
const only = argv.includes('--preset') ? argv[argv.indexOf('--preset') + 1] : undefined

if (argv.includes('--all')) {
  console.log('== 宿主级行 ==')
  for (const row of hostRows) {
    console.log(`  ${row.disabled ? 'OFF' : 'on '} ${String(row.id).padEnd(34)} ${row.name ?? ''}`)
  }
}

console.log('\n== agent preset 的插件列表 ==')
for (const [id, list] of presets) {
  if (only !== undefined && id !== `preset-${only}`) continue
  console.log(`\n${id}  (${list.length} 条)`)
  console.log('  ' + (list.join('  ') || '（空）'))
}

const reference = presets.get('preset-standard') ?? []
const phone = presets.get('preset-phone') ?? []
const referenceSet = new Set(reference.map((x) => x.replace(' (off)', '')))
const phoneSet = new Set(phone.map((x) => x.replace(' (off)', '')))
const missing = [...referenceSet].filter((x) => !phoneSet.has(x))
const extra = [...phoneSet].filter((x) => !referenceSet.has(x))

console.log('\n== 与 preset-standard 的差集 ==')
console.log(`standard 有、phone 没有（${missing.length}）：`)
for (const id of missing) console.log(`  - ${id}`)
console.log(`phone 有、standard 没有（${extra.length}）：`)
for (const id of extra) console.log(`  + ${id}`)

// 宿主级被禁的行单列一段：这些是「dsh-base 有、但这个 profile 关掉了」的，移植时最值得逐条问一句
// 「这条为什么关」—— tool-web 与 agent-instructions 当年就是被 dsh-web-app 关掉的。
console.log('\n== 宿主级被禁用的行 ==')
for (const row of hostRows.filter((r) => r.disabled)) {
  console.log(`  OFF ${String(row.id).padEnd(34)} ${row.name ?? ''}`)
}

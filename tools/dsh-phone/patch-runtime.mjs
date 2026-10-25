// 对 vendored dsh 运行时做 Android 适配补丁（幂等）。
// 用法：node patch-runtime.mjs <node_modules 目录>
//
// 三处补丁：
// 1. @deepseek-ai/node-addon-system/flock：jsonl 会话持久化的写租约在非 win32 平台走
//    POSIX flock 原生 addon；该 addon 没有 Android 预编译。内嵌 Node 是单进程，
//    没有跨进程争用，Android 上直接放行。PC 上若缺 koffi/win32 binding 也会报同族的错，
//    但那是测试环境的安装平台问题（用 --os=android 安装在 PC 上跑不了写租约）。
// 2. dsh-session-persistence-jsonl：见下方注释。
// 3. dsh-client-ui-settings-models：见下方注释。
//
// **幂等判定一律看「补丁效果」，不看标记串。** 踩过的坑：标记串里带过项目名，
// 项目改名（Memex → Factotum）后旧标记对不上，脚本误判成「还没打过补丁」，
// 于是 flock 被重复插入、jsonl / welcome 因为锚点已被替换而直接抛错。
// 标记串现在只用于人工 grep，不参与任何判断。
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const modulesDir = process.argv[2]
if (!modulesDir) {
  console.error('usage: node patch-runtime.mjs <node_modules>')
  process.exit(2)
}

const flockMarker = 'Factotum patch: Android 单进程无需跨进程 flock'
const flockPath = join(modulesDir, '@deepseek-ai/node-addon-system/lib/flock.js')
const flockSource = readFileSync(flockPath, 'utf8')
const flockAnchor = 'export async function tryLockExclusive(fd) {'
// 先把历史上插入过的任意版本补丁剥掉（标记串换过名，可能已累积多份），再统一插一次
const FLOCK_PATCH_RE =
  /\n[ \t]*\/\/[^\n]*Android 单进程无需跨进程 flock\n[ \t]*if \(process\.platform === 'android'\)\n[ \t]*return;/g
const flockBase = flockSource.replace(FLOCK_PATCH_RE, '')
if (!flockBase.includes(flockAnchor)) throw new Error(`flock.js 结构变了，找不到锚点：${flockPath}`)
const flockPatched = flockBase.replace(
  flockAnchor,
  `export async function tryLockExclusive(fd) {
    // ${flockMarker}
    if (process.platform === 'android')
        return;`,
)
if (flockPatched === flockSource) {
  console.log('flock patch already applied')
} else {
  writeFileSync(flockPath, flockPatched)
  console.log(`patched: ${flockPath}`)
}

// 补丁 2：jsonl 会话持久化用硬链接发布文件；Android 沙箱对 app data 的 link() 返回
// EACCES。内嵌单进程下用 rename（原子发布、同目录）替代，语义足够。
const linkMarker = 'Factotum patch: Android 用 rename 发布'
const jsonlPath = join(modulesDir, '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js')
const jsonl = readFileSync(jsonlPath, 'utf8')
const linkAnchor = 'await link(tmp, finalPath);'
const renameCall = 'await rename(tmp, finalPath);'
if (jsonl.includes(renameCall)) {
  // 已打过：只把可能过期的标记串归一化，不动代码
  const normalized = jsonl.replace(
    /\/\*[^*]*Android 用 rename 发布 \*\/ await rename\(tmp, finalPath\);/,
    `/* ${linkMarker} */ await rename(tmp, finalPath);`,
  )
  if (normalized !== jsonl) writeFileSync(jsonlPath, normalized)
  console.log('jsonl link patch already applied')
} else {
  if (!jsonl.includes(linkAnchor)) {
    throw new Error(`jsonl 结构变了，既没有 link 锚点也没有已应用的 rename：${jsonlPath}`)
  }
  let patched = jsonl.replace(linkAnchor, `/* ${linkMarker} */ await rename(tmp, finalPath);`)
  if (!/import \{[^}]*\brename\b[^}]*\} from "node:fs\/promises"/.test(patched)) {
    patched = patched.replace(
      'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";',
      'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from "node:fs/promises";',
    )
  }
  writeFileSync(jsonlPath, patched)
  console.log(`patched: ${jsonlPath}`)
}

// 补丁 4：把进程内的 token 级增量（agent/assistant-stream）转发给宿主。
//
// SDK 原本只把「每一步组装完成的 assistant/message」经 session.event 发给宿主 —— 一次模型回复
// 只对应一条消息，所以手机上看起来是整段蹦出来。进程内其实一直有 delta 级事件
// （text-delta / reasoning-delta / tool-call-delta…），只是 JSON-RPC server 没有转发它。
// 这里补一段同样的 ctx.on(...) → transport.notify("session.stream", ...)。
//
// 诊断只打**结构**（帧数与字符数），不打正文 —— 与「屏幕与聊天正文不落日志」那条红线一致。
const streamMarker = 'Factotum patch: forward assistant stream'
const streamPath = join(modulesDir, '@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js')
const streamSrc = readFileSync(streamPath, 'utf8')
const streamAnchor = '\t\tthis.disposers.push(ctx.on("session/created", (session) => {'
if (streamSrc.includes(streamMarker)) {
  console.log('assistant stream patch already applied')
} else {
  if (!streamSrc.includes(streamAnchor)) {
    throw new Error(`assistant stream 结构变了，找不到锚点：${streamPath}`)
  }
  const injected = [
    '\t\t// ' + streamMarker,
    '\t\tconst factotumStreamStats = new Map();',
    '\t\tthis.disposers.push(ctx.on("agent/assistant-stream", ({ agent, frame }) => {',
    '\t\t\tif (!frame || frame.type !== "chunk" || !agent) return;',
    '\t\t\tconst sid = String(agent.session.id);',
    '\t\t\tconst stats = factotumStreamStats.get(sid) ?? { frames: 0, text: 0 };',
    '\t\t\tstats.frames += 1;',
    '\t\t\tconst chunk = frame.chunk ?? {};',
    '\t\t\tif (chunk.type === "text-delta") stats.text += String(chunk.text ?? "").length;',
    '\t\t\tfactotumStreamStats.set(sid, stats);',
    '\t\t\tthis.transport.notify("session.stream", {',
    '\t\t\t\tsessionId: sid,',
    '\t\t\t\tattemptId: String(frame.attemptId),',
    '\t\t\t\trevision: frame.revision,',
    '\t\t\t\tindex: frame.index,',
    '\t\t\t\tchunk',
    '\t\t\t});',
    '\t\t}));',
    '\t\tthis.disposers.push(ctx.on("agent/assistant-stream", ({ agent, frame }) => {',
    '\t\t\tif (!frame || frame.type !== "end" || !agent) return;',
    '\t\t\tconst sid = String(agent.session.id);',
    '\t\t\tconst stats = factotumStreamStats.get(sid) ?? { frames: 0, text: 0 };',
    '\t\t\tfactotumStreamStats.delete(sid);',
    '\t\t\tconsole.error(`[factotum] assistant stream end: frames=${stats.frames} textChars=${stats.text}`);',
    '\t\t}));',
    '',
  ].join('\n')
  writeFileSync(streamPath, streamSrc.replace(streamAnchor, injected + streamAnchor))
  console.log(`patched: ${streamPath}`)
}

// 补丁 3：隐藏 dsh Web 前端的 Preview Notice 引导弹窗。
// 它的确认状态写入 ui-settings-general 设置命名空间，而 settings.write 需要
// file-backed include 树里的 config-editor 条目（我们目前把 rows 直接挂在 loader.root，
// 没有 include 层）→ 保存必然失败且弹窗无法关闭、阻断首次使用。
// 该弹窗只是预览告知，不影响功能；等 launcher 改用 file-backed include 后再去掉本补丁。
const welcomeMarker = 'Factotum patch: hide preview notice'
const welcomePath = join(modulesDir, '@deepseek-ai/dsh-client-ui-settings-models/lib/client.js')
const welcome = readFileSync(welcomePath, 'utf8')
const welcomeAnchor =
  /if \(state\.status === "idle" \|\| state\.status === "loading" \|\| state\.acknowledged\) return null;/
if (/return null; \/\/[^\n]*hide preview notice/.test(welcome)) {
  const normalized = welcome.replace(
    /return null; \/\/[^\n]*hide preview notice/,
    `return null; // ${welcomeMarker}`,
  )
  if (normalized !== welcome) writeFileSync(welcomePath, normalized)
  console.log('welcome notice patch already applied')
} else {
  if (!welcomeAnchor.test(welcome)) {
    throw new Error(`welcome notice 结构变了，找不到锚点：${welcomePath}`)
  }
  writeFileSync(welcomePath, welcome.replace(welcomeAnchor, `return null; // ${welcomeMarker}`))
  console.log(`patched: ${welcomePath}`)
}

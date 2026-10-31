// 对 vendored dsh 运行时做 Android 适配补丁（幂等）。
// 用法：node patch-runtime.mjs <node_modules 目录>
//
// 补丁清单：
// 1. @deepseek-ai/node-addon-system/flock：jsonl 会话持久化的写租约在非 win32 平台走
//    POSIX flock 原生 addon；该 addon 没有 Android 预编译。内嵌 Node 是单进程，
//    没有跨进程争用，Android 上直接放行。PC 上若缺 koffi/win32 binding 也会报同族的错，
//    但那是测试环境的安装平台问题（用 --os=android 安装在 PC 上跑不了写租约）。
// 2. dsh-session-persistence-jsonl：见下方注释。
// 3. dsh-client-ui-settings-models：见下方注释。
// 4. dsh-sdk-jsonrpc-server：token 级增量转发；以及把 dsh 的 resume / cancel 暴露给宿主。
//
// **幂等判定一律看「补丁效果」，不看标记串。** 踩过的坑：标记串里带过项目名，
// 项目改名（Memex → zoneu）后旧标记对不上，脚本误判成「还没打过补丁」，
// 于是 flock 被重复插入、jsonl / welcome 因为锚点已被替换而直接抛错。
// 标记串现在只用于人工 grep，不参与任何判断。
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { patchWorldRuntime } from './world-runtime-patch.mjs'
import { patchWorldLifecycle } from './world-lifecycle-patch.mjs'

const modulesDir = process.argv[2]
if (!modulesDir) {
  console.error('usage: node patch-runtime.mjs <node_modules>')
  process.exit(2)
}

// Optional application projection, after Harness conversion and before provider dispatch.
const worldAdapterPath = join(modulesDir, '@deepseek-ai/dsh-llm-pi-ai/lib/index.js')
const worldAdapterSource = readFileSync(worldAdapterPath, 'utf8')
const worldAdapterPatched = patchWorldRuntime(worldAdapterSource)
if (worldAdapterPatched !== worldAdapterSource) writeFileSync(worldAdapterPath, worldAdapterPatched)
console.log(worldAdapterPatched === worldAdapterSource ? 'world runtime patch already applied' : `patched: ${worldAdapterPath}`)

const flockMarker = 'zoneu patch: Android 单进程无需跨进程 flock'
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
const linkMarker = 'zoneu patch: Android 用 rename 发布'
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
const streamMarker = 'zoneu patch: forward assistant stream'
const streamPath = join(modulesDir, '@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js')
const streamOriginal = readFileSync(streamPath, 'utf8')
const streamSrc = patchWorldLifecycle(streamOriginal)
if (streamSrc !== streamOriginal) writeFileSync(streamPath, streamSrc)
const streamBase = streamSrc.replace(
  /(?:[ \t]*\/\/[^\n]*forward assistant stream\n)?[ \t]*const \w+StreamStats = new Map\(\);[\s\S]*?console\.error\(`\[[^\]]+\] assistant stream end:[^\n]*\n[ \t]*\}\)\);\n/g,
  '',
)
const streamAnchor = '\t\tthis.disposers.push(ctx.on("session/created", (session) => {'
{
  if (streamBase.includes('this.transport.notify("session.stream"') || streamBase.includes('StreamStats = new Map')) {
    throw new Error('assistant stream 存在无法识别的旧补丁，拒绝重复注入')
  }
  if (!streamBase.includes(streamAnchor)) {
    throw new Error(`assistant stream 结构变了，找不到锚点：${streamPath}`)
  }
  const injected = [
    '\t\t// ' + streamMarker,
    '\t\tconst zoneuStreamStats = new Map();',
    '\t\tthis.disposers.push(ctx.on("agent/assistant-stream", ({ agent, frame }) => {',
    '\t\t\tif (!frame || frame.type !== "chunk" || !agent) return;',
    '\t\t\tconst sid = String(agent.session.id);',
    '\t\t\tconst stats = zoneuStreamStats.get(sid) ?? { frames: 0, text: 0 };',
    '\t\t\tstats.frames += 1;',
    '\t\t\tconst chunk = frame.chunk ?? {};',
    '\t\t\tif (chunk.type === "text-delta") stats.text += String(chunk.text ?? "").length;',
    '\t\t\tzoneuStreamStats.set(sid, stats);',
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
    '\t\t\tconst stats = zoneuStreamStats.get(sid) ?? { frames: 0, text: 0 };',
    '\t\t\tzoneuStreamStats.delete(sid);',
    '\t\t\tconsole.error(`[zoneu] assistant stream end: frames=${stats.frames} textChars=${stats.text}`);',
    '\t\t}));',
    '',
  ].join('\n')
  const patched = streamBase.replace(streamAnchor, injected + streamAnchor)
  if (patched !== streamSrc) writeFileSync(streamPath, patched)
  console.log(patched === streamSrc ? 'assistant stream patch already applied' : `patched: ${streamPath}`)
}

// 补丁 3：隐藏 dsh Web 前端的 Preview Notice 引导弹窗。
// 它的确认状态写入 ui-settings-general 设置命名空间，而 settings.write 需要
// file-backed include 树里的 config-editor 条目（我们目前把 rows 直接挂在 loader.root，
// 没有 include 层）→ 保存必然失败且弹窗无法关闭、阻断首次使用。
// 该弹窗只是预览告知，不影响功能；等 launcher 改用 file-backed include 后再去掉本补丁。
const welcomeMarker = 'zoneu patch: hide preview notice'
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


// 补丁 5：把 dsh 的 resume / cancel 能力暴露给宿主。
//
// SDK 的 JSON-RPC server 只暴露 session/prompt；同名会话再 create 会 already exists，
// 于是旧实现（每次进程/重载换 runtimeTag 后缀）只能新开会话，模型上下文整段丢失。
// dsh-agent 本身支持 resume：它加载磁盘事件、补写中断 turn 的结束事件，并把上一个进程
// 留下的排队输入清掉。这里把能力接到 JSON-RPC 上：
//   session/resume → 有磁盘会话就接回来（resumed:true），没有就 resumed:false；
//   session/cancel → 中断正在跑的 turn 并清空排队输入（卡死恢复）。
const resumeMarker = 'zoneu patch: expose session resume and cancel'
const resumePath = streamPath
const resumeOriginal = readFileSync(resumePath, 'utf8')
if (resumeOriginal.includes('case "session/resume"')) {
  console.log('session resume patch already applied')
} else {
  const dispatchAnchor = '			case "session/prompt": return this.prompt(params);'
  const methodAnchor = '	hasAdapterFor(provider) {'
  if (!resumeOriginal.includes(dispatchAnchor)) {
    throw new Error(`session dispatch 结构变了，找不到锚点：${resumePath}`)
  }
  if (!resumeOriginal.includes(methodAnchor)) {
    throw new Error(`session server 结构变了，找不到方法锚点：${resumePath}`)
  }
  const methods = [
    '	// ' + resumeMarker,
    '	async resumeSession(params) {',
    '		if (!this.initialized) throw new Error("SDK server is not initialized");',
    '		const sessionId = params.sessionId;',
    '		if (this.sessions.has(sessionId)) return { sessionId, resumed: true };',
    '		let record;',
    '		try {',
    '			record = { handle: await this.ctx.agents.resume({',
    '				resumeSessionId: brandString(sessionId),',
    '				agentOptions: {',
    '					provider: this.provider,',
    '					model: this.model,',
    '					...this.reasoningEffort === void 0 ? {} : { reasoningEffort: this.reasoningEffort },',
    '					...this.maxTokens === void 0 ? {} : { maxTokens: this.maxTokens }',
    '				}',
    '			}) };',
    '		} catch (error) {',
    '			if (error?.name === "SessionPersistenceNotFoundError") return { sessionId, resumed: false };',
    '			throw error;',
    '		}',
    '		// 上个进程留下的排队输入只在这次 resume 里处理：它们已经不在宿主的发送路径上，',
    '		// 留着会让下一轮先回答旧消息。',
    '		try {',
    '			const inbox = record.handle.agent.inbox;',
    '			if (inbox?.hasPending) console.error("[zoneu] resume dropped pending inbox input");',
    '			inbox?.clear?.();',
    '		} catch (error) {',
    '			console.error(`[zoneu] resume inbox clear failed: ${error?.message ?? error}`);',
    '		}',
    '		this.sessions.set(sessionId, record);',
    '		return { sessionId, resumed: true };',
    '	}',
    '	// zoneu patch: expose session resume and cancel',
    '	async cancelSession(params) {',
    '		const record = this.sessions.get(params.sessionId);',
    '		if (record === void 0) return { sessionId: params.sessionId, cancelled: false };',
    '		record.handle.agent.cancel({ kind: "user" });',
    '		return { sessionId: params.sessionId, cancelled: true };',
    '	}',
    '',
  ].join('\n')
  let patched = resumeOriginal.replace(
    dispatchAnchor,
    dispatchAnchor +
      '\n			case "session/resume": return this.resumeSession(params);' +
      '\n			case "session/cancel": return this.cancelSession(params);',
  )
  patched = patched.replace(methodAnchor, methods + methodAnchor)
  writeFileSync(resumePath, patched)
  console.log(`patched: ${resumePath}`)
}

// Mount this class IN PLACE OF fs-local (not alongside a second ctx.fs service).
// Config, target resolution, reads, file URLs and attachment byte reads stay upstream.
// This is a generic-write guard, NOT a same-UID shell sandbox. Deliberate concurrent
// filesystem swaps can race the checks; no openat/dirfd publication primitive exists here.
import { AsyncLocalStorage } from 'node:async_hooks'
import { lstat, realpath } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, isAbsolute, join, parse, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

// Repository tests use the exact vendored runtime without copying or modifying it.
async function runtimeModule(name) {
  try { return await import(name) } catch (error) {
    if (error.code !== 'ERR_MODULE_NOT_FOUND') throw error
    const require = createRequire(new URL('../../../android/.dsh-cache/runtime/package.json', import.meta.url))
    return import(pathToFileURL(require.resolve(name)).href)
  }
}
const { LocalFileSystem } = await runtimeModule('@deepseek-ai/dsh-fs-local')
const { FileSystem, FsError } = await runtimeModule('@deepseek-ai/dsh-fs')
const { resolveDshHome } = await runtimeModule('@deepseek-ai/dsh-home-paths')

const stateKey = Symbol('zoneu protected fs')
const safeError = code => {
  const error = new FsError(code, code)
  error.stack = `${error.name}: ${code}`
  return error
}
const denied = () => { throw safeError('FS_PERMISSION_DENIED') }
const safeCodes = new Set(['FS_NOT_FOUND', 'FS_NOT_DIRECTORY', 'FS_NOT_TEXT', 'FS_NOT_REGULAR_FILE',
  'FS_TOO_LARGE', 'FS_PERMISSION_DENIED', 'FS_SANDBOX_DENIED', 'FS_IO_ERROR', 'FS_STALE_VERSION',
  'FS_NOT_OBSERVED', 'FS_AMBIGUOUS_EDIT', 'FS_EDIT_NOT_FOUND', 'FS_ABORTED'])
const redact = error => {
  const code = safeCodes.has(error?.code) ? error.code : 'FS_IO_ERROR'
  // Do not retain cause: upstream errors may contain a path, edit text or native diagnostics.
  return safeError(code)
}

function absolutePath(path) {
  if (typeof path !== 'string' || !isAbsolute(path) || path.includes('\0')) denied()
  // Refuse Windows device/UNC paths, ADS and normalization aliases rather than guessing.
  if (process.platform === 'win32') {
    if (!/^[a-z]:[\\/]/i.test(path)) denied()
    for (const part of path.slice(3).split(/[\\/]/)) {
      if (part === '.' || part === '..' || part === '') continue
      if (/[<>:"|?*\x00-\x1f]/.test(part) || /[ .]$/.test(part) ||
        /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part)) denied()
    }
  }
  return path
}
const identity = path => {
  const value = resolve(absolutePath(path))
  return process.platform === 'win32' ? value.toLowerCase() : value
}
const spelling = path => process.platform === 'win32' ? path.replaceAll('/', sep).toLowerCase() : path
const contains = (parent, child) => {
  const suffix = relative(identity(parent), identity(child))
  return suffix === '' || (!isAbsolute(suffix) && suffix !== '..' && !suffix.startsWith(`..${sep}`))
}

/** Pure lexical policy. Pass canonical paths to also compare real filesystem identities. */
export function createProtectedPathPolicy({ home, dshHome = join(home, 'dsh-home'), workDir = dshHome }) {
  const files = resolve(absolutePath(home))
  const homes = [...new Set([resolve(absolutePath(dshHome)), resolve(absolutePath(workDir)), join(files, 'dsh-home')])]
  return Object.freeze({
    trees: Object.freeze([join(files, 'roleplay'), join(files, 'dsh-runtime'),
      resolve(files, '..', 'databases'), resolve(files, '..', 'shared_prefs')]),
    // Both reload INPUTS and SIGNALS must be guarded; protecting config alone is not enough.
    files: Object.freeze(homes.flatMap(root => ['runtime-config.json', 'runtime.reload', 'runtime.ready', 'web-url.txt']
      .map(name => join(root, name)))),
  })
}

/** Pure helper: protect descendants AND attempts to replace a protected root's ancestors. */
export function isProtectedPath(path, policy) {
  absolutePath(path)
  return [...policy.trees, ...policy.files].some(root => contains(root, path) || contains(path, root))
}

// Resolve the nearest EXISTING ancestor, not merely the nearest realpath success.
// lstat distinguishes a missing entry from a dangling symlink, which must fail closed.
async function canonicalRoot(path) {
  const missing = []
  let current = resolve(absolutePath(path))
  for (;;) {
    let info
    try { info = await lstat(current) } catch (error) {
      if (error.code !== 'ENOENT') throw error
      const parent = dirname(current)
      if (parent === current) denied()
      missing.unshift(parse(current).base)
      current = parent
      continue
    }
    const canonical = await realpath(current)
    if (missing.length && !info.isDirectory()) {
      // A symlinked protected root is followed for identity, but its missing suffix
      // still needs a real directory. Dangling links already failed at realpath.
      if (!(await lstat(canonical)).isDirectory()) denied()
    }
    return join(canonical, ...missing)
  }
}

// Mutation paths are stricter than reads: apart from the captured trusted HOME
// mapping, reject symlink components (including dangling links), non-directory
// parents and any regular file with nlink > 1.
// This intentionally also refuses benign links/hardlinks. It is not a workspace fence.
async function inspectMutationPath(path, trustedHome) {
  absolutePath(path)
  // Android may expose filesDir through a system-owned ancestor alias (/data/user/0).
  // Only the HOME mapping captured at mount is trusted; links below it still fail.
  // Match the RAW prefix so '..' is walked physically, not normalized past a link.
  const prefix = [trustedHome.home, trustedHome.canonical].find(anchor =>
    spelling(path.slice(0, anchor.length)) === spelling(anchor) &&
    (path.length === anchor.length || path[anchor.length] === '/' || path[anchor.length] === sep))
  const root = prefix ? trustedHome.canonical : parse(path).root
  const remainder = path.slice(prefix?.length ?? root.length)
  const parts = remainder.replaceAll(sep, '/').split('/')
  let current = root
  let missing = false
  let parentIsDirectory = true
  let existing = root
  const suffix = []
  for (const part of parts) {
    if (!parentIsDirectory) denied()
    if (!part || part === '.') continue
    if (part === '..') {
      // Never collapse traversal across a missing or unreadable parent.
      if (missing) denied()
      current = dirname(current)
      existing = current
      continue
    }
    current = join(current, part)
    if (missing) { suffix.push(part); continue }
    let info
    try { info = await lstat(current) } catch (error) {
      if (error.code !== 'ENOENT') throw error
      missing = true
      suffix.push(part)
      continue
    }
    if (info.isSymbolicLink() || (info.isFile() && info.nlink > 1)) denied()
    if (!info.isDirectory() && !info.isFile()) denied()
    parentIsDirectory = info.isDirectory()
    existing = current
  }
  // realpath is authoritative even for a new file below several absent directories.
  const canonical = join(await realpath(existing), ...suffix)
  if (identity(canonical) !== identity(current)) denied()
  return canonical
}

async function assertWritable(target, policy, trustedHome) {
  if (isProtectedPath(target.displayPath, policy) || isProtectedPath(target.targetKey, policy)) denied()
  if (!trustedHome || identity(await realpath(trustedHome.home)) !== identity(trustedHome.canonical)) denied()
  // HOME can itself be an Android system alias. Guard the physical filesDir
  // siblings too, not only the siblings of its lexical spelling.
  const physical = createProtectedPathPolicy({ home: trustedHome.canonical })
  const roots = await Promise.all([...policy.trees, ...policy.files, ...physical.trees, ...physical.files].map(canonicalRoot))
  const canonicalPolicy = { trees: roots, files: [] }
  const display = await inspectMutationPath(target.displayPath, trustedHome)
  const key = await inspectMutationPath(target.targetKey, trustedHome)
  // A formerly resolved target is not authority to use a changed alias or forged key.
  if (identity(display) !== identity(key) || identity(key) !== identity(target.targetKey)) denied()
  if (isProtectedPath(display, canonicalPolicy) || isProtectedPath(key, canonicalPolicy)) denied()
}

// Fail closed on unreviewed provider API changes, including new inherited mutations.
// withLock/versionAfterWrite are TS-private but ordinary JS methods in rc.2. This
// wrapper deliberately depends on the reviewed withLock seam, rather than checking
// only before enqueue and leaving a stale-target window while the lock is held.
export const reviewedLocalMethods = Object.freeze(['constructor', 'watch', 'withLock', 'resolve', 'processPath',
  'processPathFromHostPath', 'fileUrl', 'contains', 'stat', 'lstat', 'readText', 'streamText', 'readBytes',
  'readByteRange', 'listDir', 'writeText', 'editText', 'versionAfterWrite'].sort())
export const reviewedBaseMethods = Object.freeze(['constructor', 'watch', 'sandboxMode', 'processPathFromHostPath'].sort())
function assertReviewedProvider() {
  for (const [prototype, methods] of [[LocalFileSystem.prototype, reviewedLocalMethods], [FileSystem.prototype, reviewedBaseMethods]]) {
    const actual = Reflect.ownKeys(prototype)
    if (actual.some(key => typeof key !== 'string') || actual.sort().join('\0') !== methods.join('\0')) denied()
  }
}

export default class ProtectedLocalFileSystem extends LocalFileSystem {
  static Config = LocalFileSystem.Config

  constructor(ctx, config) {
    assertReviewedProvider()
    // Capture trusted host environment once, not from write arguments or mutable config.
    const home = absolutePath(process.env.HOME)
    const dshHome = resolveDshHome()
    const policy = createProtectedPathPolicy({ home, dshHome, workDir: process.env.ZONEU_WORKDIR ?? dshHome })
    super(ctx, config)
    const state = { policy, scope: new AsyncLocalStorage(), hooks: this.internals,
      // Attach the rejection handler immediately; a missing/unreadable HOME is a
      // closed guard, never an unhandled startup promise or a lexical fallback.
      trustedHome: realpath(home).then(canonical => Object.freeze({ home: resolve(home), canonical }), () => null) }
    this[stateKey] = state
    // Keep upstream's test hooks usable, but replacing internals or inspectTemp must
    // never remove the last safety check. No cached dependency is patched.
    const inspectTemp = async paths => {
      const operation = state.scope.getStore()
      if (!operation) denied()
      await state.hooks.inspectTemp?.(paths)
      await assertWritable(operation.target, policy, await state.trustedHome)
    }
    const guardedInternals = new Proxy({}, {
      get: (_target, key) => key === 'inspectTemp' ? inspectTemp : state.hooks[key],
      set: (_target, key, value) => { state.hooks[key] = value; return true },
    })
    Object.defineProperty(this, 'internals', {
      configurable: false,
      get: () => guardedInternals,
      set: hooks => { state.hooks = hooks ?? {} },
    })
  }

  async withLock(key, operation) {
    return super.withLock(key, async () => {
      const active = this[stateKey].scope.getStore()
      if (!active || active.target.targetKey !== key) denied()
      // This runs INSIDE LocalFileSystem's actual FIFO lock and BEFORE any read,
      // mkdir, staging or replacement. inspectTemp rechecks before publication.
      await assertWritable(active.target, this[stateKey].policy, await this[stateKey].trustedHome)
      return operation()
    })
  }

  async writeText(target, content, expected, signal, sandboxPolicy) {
    try {
      const snapshot = Object.freeze({ displayPath: absolutePath(target?.displayPath), targetKey: absolutePath(target?.targetKey) })
      return await this[stateKey].scope.run({ target: snapshot }, () =>
        super.writeText(snapshot, content, expected, signal, sandboxPolicy))
    } catch (error) { throw redact(error) }
  }

  async editText(target, edit, expected, signal, sandboxPolicy) {
    try {
      const snapshot = Object.freeze({ displayPath: absolutePath(target?.displayPath), targetKey: absolutePath(target?.targetKey) })
      return await this[stateKey].scope.run({ target: snapshot }, () =>
        super.editText(snapshot, edit, expected, signal, sandboxPolicy))
    } catch (error) { throw redact(error) }
  }
}

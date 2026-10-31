import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createRequire } from 'node:module'
import { mkdtemp, mkdir, readFile, writeFile, readdir, rm, rename, symlink, link, lstat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import ProtectedLocalFileSystem, { createProtectedPathPolicy, isProtectedPath,
  reviewedLocalMethods, reviewedBaseMethods } from '../protected-fs.mjs'

const require = createRequire(new URL('../../../../android/.dsh-cache/runtime/package.json', import.meta.url))
const load = name => import(pathToFileURL(require.resolve(name)).href)
const { Context } = await load('@deepseek-ai/cordis')
const { LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local')
const { FileSystem, FsError } = await load('@deepseek-ai/dsh-fs')
const envKeys = ['HOME', 'DSH_HOME', 'ZONEU_WORKDIR']
const body = 'SYNTHETIC_PRIVATE_BODY'
const edit = { oldString: body, newString: 'new value', replaceAll: false }
const blocked = operation => assert.rejects(operation, error => {
  assert.ok(error instanceof FsError)
  assert.equal(error.code, 'FS_PERMISSION_DENIED')
  assert.equal(error.message, 'FS_PERMISSION_DENIED')
  assert.equal(error.stack, `${error.name}: ${error.code}`)
  assert.equal(error.cause, undefined)
  assert.ok(!JSON.stringify(error).includes(body))
  return true
})
const latch = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

async function fixture(t, { absentRoots = false, customWork = false, homeAlias = false, finalHomeAlias = false } = {}) {
  // All writes, symlinks, hard links and provider staging stay in this isolated root.
  const root = await mkdtemp(join(tmpdir(), 'zoneu-protected-fs-'))
  const canonicalHome = join(root, 'app', 'files')
  await mkdir(canonicalHome, { recursive: true })
  if (homeAlias) await directoryLink(join(root, 'app'), join(root, 'app-alias'))
  if (finalHomeAlias) await directoryLink(canonicalHome, join(root, 'files-alias'))
  const home = finalHomeAlias ? join(root, 'files-alias') : homeAlias ? join(root, 'app-alias', 'files') : canonicalHome
  const dshHome = join(home, 'dsh-home')
  const workDir = customWork ? join(home, 'custom-work') : dshHome
  await mkdir(workDir, { recursive: true })
  await mkdir(dshHome, { recursive: true })
  const original = Object.fromEntries(envKeys.map(key => [key, process.env[key]]))
  Object.assign(process.env, { HOME: home, DSH_HOME: dshHome, ZONEU_WORKDIR: workDir })
  let ctx, mounted
  t.after(async () => {
    try { await mounted?.dispose(); await ctx?.root.fiber.dispose() } finally {
      for (const key of envKeys) {
        if (original[key] === undefined) delete process.env[key]
        else process.env[key] = original[key]
      }
      await rm(root, { recursive: true, force: true })
    }
  })
  const put = async (path, text = body) => {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(path, text)
    return path
  }
  const protectedFiles = [join(home, 'roleplay', 'cards', 'synthetic.json'),
    join(home, 'roleplay', 'memories', 'synthetic.md'), join(home, 'roleplay', 'card-requests', 'receipt.json'),
    join(home, 'roleplay', 'receipts', 'receipt.json'), join(home, 'dsh-runtime', 'launcher.mjs'),
    join(home, 'dsh-runtime', 'node_modules', 'dsh-plugin-character-cards', 'protected-fs.mjs'),
    join(home, 'dsh-runtime', 'profiles', 'phone', 'cordis.patch.yml'),
    join(home, 'dsh-runtime', 'profiles', 'phone', 'package.json'),
    join(home, '..', 'databases', 'zoneu.db'), join(home, '..', 'databases', 'zoneu.db-wal'),
    join(home, '..', 'databases', 'zoneu.db-shm'), join(home, '..', 'shared_prefs', 'wrapped-key.xml'),
    ...[...new Set([dshHome, workDir])].flatMap(dir =>
      ['runtime-config.json', 'runtime.reload', 'runtime.ready', 'web-url.txt'].map(name => join(dir, name)))]
  if (!absentRoots) for (const path of protectedFiles) await put(path)
  ctx = new Context()
  // Actual Cordis class-plugin construction applies the real upstream Config schema.
  mounted = await ctx.plugin(ProtectedLocalFileSystem, { cwd: workDir })
  const fs = ctx.get('fs')
  // Cache targets Android, so Windows DACL native binaries are not installed.
  // Exercise the real upstream POSIX publication branch used on Android; every
  // path, lock, symlink, hardlink and I/O operation still uses the real host fs.
  fs.internals.platform = 'linux'
  assert.ok(fs instanceof LocalFileSystem)
  assert.ok(fs instanceof ProtectedLocalFileSystem)
  return { root, home, canonicalHome, dshHome, workDir, protectedFiles, fs, ctx, put }
}

async function directoryLink(target, alias) {
  await symlink(target, alias, process.platform === 'win32' ? 'junction' : 'dir')
}

// Top-level node:test execution is serial: environment changes never overlap fixtures.
test('pure path policy guards all roots, runtime controls, ancestors, and component boundaries', () => {
  const home = resolve(tmpdir(), 'pure-policy', 'app', 'files')
  const dshHome = join(home, 'dsh-home')
  const workDir = join(home, 'work')
  const policy = createProtectedPathPolicy({ home, dshHome, workDir })
  for (const path of [home, dirname(home), join(home, 'roleplay'), join(home, 'roleplay', 'new'),
    join(home, 'dsh-runtime', 'any-module'), join(home, '..', 'databases', 'zoneu.db-shm'),
    join(home, '..', 'shared_prefs', 'identity.xml'), ...policy.files]) assert.equal(isProtectedPath(path, policy), true)
  for (const path of [join(dshHome, 'AGENTS.md'), join(dshHome, 'memory', 'note.md'),
    join(dshHome, 'runtime-config.json.notes'), join(home, 'roleplay-copy', 'card.json'),
    join(home, 'dsh-runtime-copy', 'module.mjs'), join(home, '..', 'databases-copy', 'note')]) {
    assert.equal(isProtectedPath(path, policy), false)
  }
  assert.equal(Object.isFrozen(policy), true)
})

test('current real provider/base method inventory is pinned; only writeText/editText mutate', () => {
  assert.deepEqual(Reflect.ownKeys(LocalFileSystem.prototype).sort(), reviewedLocalMethods)
  assert.deepEqual(Reflect.ownKeys(FileSystem.prototype).sort(), reviewedBaseMethods)
  assert.equal(ProtectedLocalFileSystem.Config, LocalFileSystem.Config)
  for (const method of ['resolve', 'processPath', 'processPathFromHostPath', 'fileUrl', 'contains', 'stat', 'lstat',
    'readText', 'streamText', 'readBytes', 'readByteRange', 'listDir', 'watch']) {
    assert.equal(ProtectedLocalFileSystem.prototype[method], LocalFileSystem.prototype[method])
  }
})

test('real Cordis mounts exactly the subclass as fs, preserves Config and read/attachment interfaces', async t => {
  const f = await fixture(t)
  const path = await f.put(join(f.workDir, 'image.png'), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
  const target = await f.fs.resolve('image.png')
  assert.equal(f.fs.config.cwd, f.workDir)
  assert.ok(f.fs.config.diffBasisMaxBytes > 0)
  assert.equal(f.fs.name, 'fs')
  assert.equal(f.fs.sandboxMode, undefined, 'do not claim a shell/workspace sandbox')
  assert.equal(f.fs.processPath(target), path)
  assert.equal(f.fs.fileUrl(target), pathToFileURL(path).href)
  assert.deepEqual([...await f.fs.readBytes(target, undefined, 100)], [137, 80, 78, 71, 13, 10, 26, 10])
  assert.deepEqual([...await f.fs.readByteRange(target, { offset: 1, length: 3 })], [80, 78, 71])
  const privateTarget = await f.fs.resolve(f.protectedFiles[0])
  assert.equal(await f.fs.readText(privateTarget), body, 'this boundary limits mutation, not read capability')
})

test('real write/create, overwrite and literal edit permit AGENTS.md and nested memory files', async t => {
  const f = await fixture(t)
  for (const path of ['AGENTS.md', join('memory', 'new', 'topic.md')]) {
    const target = await f.fs.resolve(path)
    const created = await f.fs.writeText(target, body, { kind: 'createIfAbsent' })
    assert.equal(created.operation, 'create')
    assert.equal(await f.fs.readText(target), body)
    const updated = await f.fs.writeText(target, `${body}\r\n`, { kind: 'replaceIfVersion', version: created.version })
    assert.equal(updated.operation, 'update')
    const edited = await f.fs.editText(target, edit, { version: updated.version })
    assert.equal(edited.after, 'new value\n')
    assert.equal(await f.fs.readText(target), 'new value\r\n')
    let streamed = ''
    for await (const chunk of await f.fs.streamText(target)) streamed += chunk
    assert.equal(streamed, 'new value\r\n')
  }
})

test('real provider denies every protected existing file, relative alias, write intent and edit', async t => {
  const f = await fixture(t)
  for (const path of f.protectedFiles) {
    for (const spelling of [path, relative(f.workDir, path)]) {
      const target = await f.fs.resolve(spelling)
      const info = await f.fs.stat(target)
      for (const intent of [undefined, { kind: 'createIfAbsent' }, { kind: 'replaceIfVersion', version: info.version }]) {
        await blocked(f.fs.writeText(target, 'overwrite', intent))
      }
      await blocked(f.fs.editText(target, edit))
      await blocked(f.fs.editText(target, edit, { version: info.version }))
    }
    assert.equal(await readFile(path, 'utf8'), body)
  }
})

test('new protected files and missing protected roots fail closed without creating parents', async t => {
  const f = await fixture(t, { absentRoots: true })
  for (const path of f.protectedFiles) {
    const target = await f.fs.resolve(path)
    await blocked(f.fs.writeText(target, 'overwrite'))
    await blocked(f.fs.editText(target, edit))
    await assert.rejects(lstat(path), { code: 'ENOENT' })
  }
  for (const name of ['roleplay', 'dsh-runtime']) await assert.rejects(lstat(join(f.home, name)), { code: 'ENOENT' })
  assert.deepEqual(await readdir(f.workDir), [])
})

test('protected directories and all of their ancestors cannot be replaced by files', async t => {
  const f = await fixture(t)
  const policy = createProtectedPathPolicy({ home: f.home })
  for (const path of new Set([f.root, f.home, f.dshHome, ...policy.trees, dirname(f.home)])) {
    const target = await f.fs.resolve(path)
    await blocked(f.fs.writeText(target, 'replacement'))
    await blocked(f.fs.editText(target, edit))
    assert.ok((await lstat(path)).isDirectory())
  }
})

test('launcher overrides do not remove default controls and both config + reload stay protected', async t => {
  const f = await fixture(t, { customWork: true })
  for (const root of [f.dshHome, f.workDir]) {
    for (const name of ['runtime-config.json', 'runtime.reload', 'runtime.ready', 'web-url.txt']) {
      const target = await f.fs.resolve(join(root, name))
      await blocked(f.fs.writeText(target, '{"guard":false}'))
      await blocked(f.fs.editText(target, edit))
    }
    const note = await f.fs.resolve(join(root, 'memory', 'note.md'))
    await f.fs.writeText(note, body)
    assert.equal(await f.fs.readText(note), body)
  }
  // Trusted environment is captured at construction, not widened by a later reload/config mutation.
  process.env.HOME = join(f.root, 'elsewhere')
  process.env.DSH_HOME = join(f.root, 'elsewhere-home')
  await blocked(f.fs.writeText(await f.fs.resolve(f.protectedFiles[0]), 'replacement'))
})

test('symlinked parent into each protected tree is refused for existing and new targets', async t => {
  const f = await fixture(t)
  const trees = createProtectedPathPolicy({ home: f.home }).trees
  for (let index = 0; index < trees.length; index++) {
    const alias = join(f.workDir, `alias-${index}`)
    await directoryLink(trees[index], alias)
    const relativeTarget = await f.fs.resolve(join(`alias-${index}`, 'fresh', 'new.txt'))
    await blocked(f.fs.writeText(relativeTarget, body))
    await blocked(f.fs.editText(relativeTarget, edit))
    await assert.rejects(lstat(join(trees[index], 'fresh')), { code: 'ENOENT' })
  }
})

test('even benign directory links are conservative denials, without changing read/resolve', async t => {
  const f = await fixture(t)
  const real = join(f.workDir, 'plain')
  await f.put(join(real, 'note.md'))
  await directoryLink(real, join(f.workDir, 'alias'))
  const target = await f.fs.resolve('alias/note.md')
  assert.equal(await f.fs.readText(target), body)
  await blocked(f.fs.writeText(target, 'replacement'))
  await blocked(f.fs.editText(target, edit))
  const newTarget = await f.fs.resolve('alias/missing/leaf.md')
  await blocked(f.fs.writeText(newTarget, 'replacement'))
  assert.equal(await readFile(join(real, 'note.md'), 'utf8'), body)
  await assert.rejects(lstat(join(real, 'missing')), { code: 'ENOENT' })
})

test('final file symlinks, including dangling links into protected or ordinary locations, fail closed', async t => {
  const f = await fixture(t)
  const plain = await f.put(join(f.workDir, 'plain.md'))
  for (const [index, destination] of [f.protectedFiles[0], plain,
    join(f.home, 'roleplay', 'never-created.json'), join(f.workDir, 'never-created.md')].entries()) {
    const alias = join(f.workDir, `file-link-${index}`)
    // Windows file symlinks require Developer Mode/admin, so use a directory junction
    // for parent-link coverage above; this case explicitly checks actual file links.
    await symlink(destination, alias, 'file')
    const target = await f.fs.resolve(alias)
    await blocked(f.fs.writeText(target, 'replacement'))
    await blocked(f.fs.editText(target, edit))
    assert.ok((await lstat(alias)).isSymbolicLink())
  }
  assert.equal(await readFile(plain, 'utf8'), body)
})

test('dangling directory parent links cannot masquerade as missing writable parents', async t => {
  const f = await fixture(t)
  const alias = join(f.workDir, 'dangling-parent')
  await directoryLink(join(f.workDir, 'missing-parent'), alias)
  const target = await f.fs.resolve('dangling-parent/new/leaf.md')
  await blocked(f.fs.writeText(target, body))
  await blocked(f.fs.editText(target, edit))
  await assert.rejects(lstat(join(f.workDir, 'missing-parent')), { code: 'ENOENT' })
})

test('stale targetKey cannot authorize a display alias after its link changes', async t => {
  const f = await fixture(t)
  const plain = join(f.workDir, 'plain')
  await f.put(join(plain, 'note.md'))
  const alias = join(f.workDir, 'alias')
  await directoryLink(plain, alias)
  const target = await f.fs.resolve('alias/note.md')
  await rm(alias, { recursive: true })
  await directoryLink(join(f.home, 'roleplay', 'cards'), alias)
  await blocked(f.fs.writeText(target, 'replacement'))
  await blocked(f.fs.editText(target, edit))
  assert.equal(await readFile(join(plain, 'note.md'), 'utf8'), body)
  assert.equal(await readFile(f.protectedFiles[0], 'utf8'), body)
})

test('an originally plain target replaced by a file or parent link is rechecked at mutation time', async t => {
  const f = await fixture(t)
  const folder = join(f.workDir, 'plain')
  const path = await f.put(join(folder, 'synthetic.json'))
  const target = await f.fs.resolve(path)
  await rm(path)
  await symlink(f.protectedFiles[0], path, 'file')
  await blocked(f.fs.writeText(target, body))
  await blocked(f.fs.editText(target, edit))
  await rm(folder, { recursive: true })
  await directoryLink(join(f.home, 'roleplay', 'cards'), folder)
  await blocked(f.fs.writeText(target, body))
  await blocked(f.fs.editText(target, edit))
  assert.equal(await readFile(f.protectedFiles[0], 'utf8'), body)
})

test('hardlink aliases to protected files AND harmless multi-linked ordinary files are refused', async t => {
  const f = await fixture(t)
  const plain = await f.put(join(f.workDir, 'plain.md'))
  for (const [index, source] of [f.protectedFiles[0], plain].entries()) {
    const alias = join(f.workDir, `hardlink-${index}.md`)
    await link(source, alias)
    assert.ok((await lstat(alias)).nlink > 1)
    for (const path of [source, alias]) {
      const target = await f.fs.resolve(path)
      await blocked(f.fs.writeText(target, 'replacement'))
      await blocked(f.fs.editText(target, edit))
      assert.equal(await readFile(path, 'utf8'), body)
    }
  }
})

test('missing ancestors resolve by the nearest real directory; suspicious traversal fails without writes', async t => {
  const f = await fixture(t)
  const okay = await f.fs.resolve('memory/new/deep/note.md')
  await f.fs.writeText(okay, body)
  assert.equal(await f.fs.readText(okay), body)
  const missingTraversal = join(f.workDir, 'absent') + '/../../roleplay/new.json'
  await blocked(f.fs.writeText({ displayPath: missingTraversal, targetKey: resolve(missingTraversal) }, body))
  await assert.rejects(lstat(join(f.workDir, 'absent')), { code: 'ENOENT' })
  const notDir = await f.put(join(f.workDir, 'not-directory'))
  const child = notDir + '/child'
  await blocked(f.fs.writeText({ displayPath: child, targetKey: child }, body))
  const alias = join(f.workDir, 'linked')
  await directoryLink(join(f.home, 'roleplay'), alias)
  // Physical '..' after a link is not lexical normalization; reject the link itself.
  const throughParent = alias + '/../AGENTS.md'
  await blocked(f.fs.writeText({ displayPath: throughParent, targetKey: join(f.home, 'AGENTS.md') }, body))
})

test('forged target identities, relative target keys and Windows alternate streams are denied safely', async t => {
  const f = await fixture(t)
  const plain = await f.put(join(f.workDir, 'ordinary.md'))
  for (const target of [null, {}, { displayPath: plain, targetKey: f.protectedFiles[0] },
    { displayPath: f.protectedFiles[0], targetKey: plain }, { displayPath: plain, targetKey: 'ordinary.md' },
    { displayPath: plain, targetKey: plain + '\0bad' }, { displayPath: plain, targetKey: join(f.workDir, 'different.md') }]) {
    await blocked(f.fs.writeText(target, body))
    await blocked(f.fs.editText(target, edit))
  }
  if (process.platform === 'win32') {
    for (const path of [plain + ':stream', plain + '.', plain + ' ', '\\\\?\\' + plain]) {
      await blocked(f.fs.writeText({ displayPath: path, targetKey: path }, body))
    }
  }
})

test('security resolution failure (dangling protected root) stops otherwise ordinary writes', async t => {
  const f = await fixture(t, { absentRoots: true })
  await directoryLink(join(f.root, 'does-not-exist'), join(f.home, 'roleplay'))
  const target = await f.fs.resolve('AGENTS.md')
  await assert.rejects(f.fs.writeText(target, body), error => {
    assert.equal(error.code, 'FS_IO_ERROR')
    assert.equal(error.message, 'FS_IO_ERROR')
    assert.equal(error.cause, undefined)
    return true
  })
  await assert.rejects(lstat(join(f.workDir, 'AGENTS.md')), { code: 'ENOENT' })
})

test('a protected root redirected by a trusted symlink also protects its canonical target path', async t => {
  const f = await fixture(t, { absentRoots: true })
  const redirected = join(f.root, 'redirected-roleplay')
  const path = await f.put(join(redirected, 'card.json'))
  await directoryLink(redirected, join(f.home, 'roleplay'))
  const target = await f.fs.resolve(path)
  await blocked(f.fs.writeText(target, body))
  await blocked(f.fs.editText(target, edit))
  assert.equal(await readFile(path, 'utf8'), body)
})

test('a queued write revalidates INSIDE the real upstream lock, before reading or staging', async t => {
  const f = await fixture(t)
  const path = await f.put(join(f.workDir, 'queue.md'))
  const target = await f.fs.resolve(path)
  const entered = latch(), release = latch()
  let stages = 0
  f.fs.internals.inspectTemp = async () => { stages++; entered.resolve(); await release.promise }
  const first = f.fs.writeText(target, 'first')
  await entered.promise
  const second = f.fs.writeText(target, 'second')
  const backup = join(f.workDir, 'queue.backup.md')
  await rename(path, backup)
  await symlink(f.protectedFiles[0], path, 'file')
  release.resolve()
  await blocked(first)
  await blocked(second)
  assert.equal(stages, 1, 'queued second call must be denied before staging')
  assert.equal(await readFile(backup, 'utf8'), body)
  assert.equal(await readFile(f.protectedFiles[0], 'utf8'), body)
})

test('prepublication hook rechecks hardlinks for edit and cleans all staging on denial', async t => {
  const f = await fixture(t)
  const path = await f.put(join(f.workDir, 'edit.md'))
  const alias = join(f.workDir, 'new-hardlink.md')
  f.fs.internals.inspectTemp = async () => { await link(path, alias) }
  await blocked(f.fs.editText(await f.fs.resolve(path), edit))
  assert.equal(await readFile(path, 'utf8'), body)
  assert.equal(await readFile(alias, 'utf8'), body)
  assert.deepEqual((await readdir(f.workDir)).filter(name => name.includes('.tmpdir')), [])
})

test('replacing upstream internals cannot disable prepublish guard', async t => {
  const f = await fixture(t)
  const path = await f.put(join(f.workDir, 'note.md'))
  f.fs.internals = { platform: 'linux', inspectTemp: async () => { await link(path, join(f.workDir, 'alias.md')) } }
  await blocked(f.fs.writeText(await f.fs.resolve(path), 'replacement'))
  assert.equal(await readFile(path, 'utf8'), body)
})

test('ordinary concurrent version guards retain upstream single-winner semantics', async t => {
  const f = await fixture(t)
  const target = await f.fs.resolve('memory/queue.md')
  const created = await f.fs.writeText(target, body)
  const results = await Promise.allSettled(['one', 'two'].map(text =>
    f.fs.writeText(target, text, { kind: 'replaceIfVersion', version: created.version })))
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1)
  const failed = results.find(result => result.status === 'rejected').reason
  assert.equal(failed.code, 'FS_STALE_VERSION')
  assert.equal(failed.message, 'FS_STALE_VERSION')
})

test('ordinary edit errors and native failures are redacted to code-only with no cause', async t => {
  const f = await fixture(t)
  const target = await f.fs.resolve(await f.put(join(f.workDir, 'PRIVATE_PATH.md')))
  await assert.rejects(f.fs.editText(target, { oldString: 'PRIVATE_EDIT_TEXT', newString: body }), error => {
    assert.equal(error.message, 'FS_EDIT_NOT_FOUND')
    assert.equal(error.stack, `${error.name}: ${error.code}`)
    assert.equal(error.code, 'FS_EDIT_NOT_FOUND')
    assert.equal(error.cause, undefined)
    assert.ok(!JSON.stringify(error).includes('PRIVATE'))
    return true
  })
  f.fs.internals.inspectTemp = () => { throw new Error(`${target.displayPath} ${body}`) }
  await assert.rejects(f.fs.writeText(target, body), error => {
    assert.equal(error.message, 'FS_IO_ERROR')
    assert.equal(error.cause, undefined)
    return true
  })
  assert.equal(await readFile(target.targetKey, 'utf8'), body)
})

test('unreviewed future mutation methods fail closed on construction instead of silently escaping guard', async () => {
  const original = process.env.HOME
  process.env.HOME = resolve(tmpdir(), 'zoneu-inventory-only')
  const ctx = new Context()
  LocalFileSystem.prototype.deleteFile = async () => assert.fail('unreviewed mutation ran')
  try {
    assert.throws(() => new ProtectedLocalFileSystem(ctx, {}), error => error.code === 'FS_PERMISSION_DENIED')
    assert.equal(ctx.get('fs'), undefined)
  } finally {
    delete LocalFileSystem.prototype.deleteFile
    if (original === undefined) delete process.env.HOME
    else process.env.HOME = original
    await ctx.root.fiber.dispose()
  }
})


test('trusted Android-style HOME ancestor alias permits workspace writes but not child links or protected canonical roots', async t => {
  const f = await fixture(t, { homeAlias: true })
  for (const path of ['AGENTS.md', 'memory/new/note.md']) {
    const target = await f.fs.resolve(path)
    await f.fs.writeText(target, body)
    await f.fs.editText(target, edit)
    assert.equal(await f.fs.readText(target), 'new value')
  }
  await blocked(f.fs.writeText(await f.fs.resolve(join(f.canonicalHome, 'roleplay', 'cards', 'synthetic.json')), body))
  await blocked(f.fs.editText(await f.fs.resolve(join(f.canonicalHome, 'dsh-home', 'runtime-config.json')), edit))
  await directoryLink(join(f.canonicalHome, 'dsh-home', 'memory'), join(f.workDir, 'child-link'))
  await blocked(f.fs.writeText(await f.fs.resolve('child-link/fresh.md'), body))
})

test('a changed trusted HOME alias fails closed rather than trusting a new root', async t => {
  const f = await fixture(t, { homeAlias: true })
  // Force initialization to settle before changing the alias.
  await f.fs.writeText(await f.fs.resolve('before.md'), body)
  const replacement = join(f.root, 'replacement')
  await mkdir(join(replacement, 'files', 'dsh-home'), { recursive: true })
  await rm(join(f.root, 'app-alias'), { recursive: true })
  await directoryLink(replacement, join(f.root, 'app-alias'))
  const target = await f.fs.resolve('after.md')
  await blocked(f.fs.writeText(target, body))
  await assert.rejects(lstat(join(replacement, 'files', 'dsh-home', 'after.md')), { code: 'ENOENT' })
})


for (const method of ['writeText', 'editText']) {
  test(`queued ${method} checks a changed parent inside the upstream FIFO before staging`, async t => {
    const f = await fixture(t)
    const folder = join(f.workDir, 'queued-parent')
    const path = await f.put(join(folder, 'synthetic.json'))
    const target = await f.fs.resolve(path)
    const entered = latch(), release = latch()
    let stages = 0
    f.fs.internals.inspectTemp = async () => { stages++ }
    // Hold the real FIFO without an open staging descriptor. Windows cannot
    // rename a parent containing an open temp file, unlike the Android target.
    const holder = LocalFileSystem.prototype.withLock.call(f.fs, target.targetKey, async () => {
      entered.resolve()
      await release.promise
    })
    await entered.promise
    const invoke = () => method === 'writeText' ? f.fs.writeText(target, 'replacement') : f.fs.editText(target, edit)
    const first = invoke(), second = invoke()
    const rejected = Promise.all([blocked(first), blocked(second)])
    const backup = join(f.workDir, 'queued-parent-backup')
    try {
      await rename(folder, backup)
      await directoryLink(join(f.home, 'roleplay', 'cards'), folder)
    } finally { release.resolve() }
    await holder
    await rejected
    assert.equal(stages, 0, 'both queued operations must fail before staging')
    assert.equal(await readFile(join(backup, 'synthetic.json'), 'utf8'), body)
    assert.equal(await readFile(f.protectedFiles[0], 'utf8'), body)
    assert.ok(!(await readdir(join(f.home, 'roleplay', 'cards'))).some(name => name.includes('.tmpdir')))
  })
}

test('new target replaced by a protected file link after staging is never published', async t => {
  const f = await fixture(t)
  const path = join(f.workDir, 'new-target.json')
  const target = await f.fs.resolve(path)
  let stages = 0
  f.fs.internals.inspectTemp = async () => {
    stages++
    await symlink(f.protectedFiles[0], path, 'file')
  }
  await blocked(f.fs.writeText(target, 'replacement', { kind: 'createIfAbsent' }))
  assert.equal(stages, 1)
  assert.ok((await lstat(path)).isSymbolicLink())
  assert.equal(await readFile(f.protectedFiles[0], 'utf8'), body)
  assert.ok(!(await readdir(f.workDir)).some(name => name.includes('.tmpdir')))
})

test('cancelled actual provider mutation does not create the target', async t => {
  const f = await fixture(t)
  const path = join(f.workDir, 'cancelled.md')
  const target = await f.fs.resolve(path)
  await assert.rejects(f.fs.writeText(target, body, undefined, AbortSignal.abort()), error => {
    assert.equal(error.code, 'FS_ABORTED')
    assert.equal(error.message, 'FS_ABORTED')
    assert.equal(error.cause, undefined)
    return true
  })
  await assert.rejects(lstat(path), { code: 'ENOENT' })
})

test('a final HOME alias still protects the physical package databases and preferences', async t => {
  const f = await fixture(t, { finalHomeAlias: true })
  for (const name of ['databases/zoneu.db', 'databases/zoneu.db-wal', 'databases/zoneu.db-shm', 'shared_prefs/identity.xml']) {
    const path = await f.put(join(f.canonicalHome, '..', name))
    const target = await f.fs.resolve(path)
    await blocked(f.fs.writeText(target, body))
    await blocked(f.fs.editText(target, edit))
    assert.equal(await readFile(path, 'utf8'), body)
  }
  const target = await f.fs.resolve('memory/note.md')
  await f.fs.writeText(target, body)
  await f.fs.editText(target, edit)
  assert.equal(await f.fs.readText(target), 'new value')
})

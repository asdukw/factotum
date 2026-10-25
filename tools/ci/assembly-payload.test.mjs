import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const bashPath = (value) => value.replaceAll(String.fromCharCode(92), '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`)
const verifier = bashPath(resolve(root, 'tools/verify-apk.sh'))
const baseEntries = [
  'lib/arm64-v8a/libnode.so',
  'lib/arm64-v8a/libc++_shared.so',
  'lib/arm64-v8a/librg.so',
  'lib/arm64-v8a/libsherpa-onnx-jni.so',
]
const prootEntries = [
  'lib/arm64-v8a/libproot.so',
  'lib/arm64-v8a/libprootloader.so',
  'lib/arm64-v8a/libtalloc.so',
  'lib/arm64-v8a/libandroidshmem.so',
  'assets/proot-rootfs.bin',
  'assets/proot-runtime.json',
]
function crc32(data) {
  let crc = 0xffffffff
  for (const byte of data) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1))
  }
  return (crc ^ 0xffffffff) >>> 0
}

function zip(entries) {
  const local = []
  const central = []
  let offset = 0
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name)
    const payload = Buffer.from(data)
    const header = Buffer.alloc(30 + nameBytes.length)
    header.writeUInt32LE(0x04034b50, 0)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(0, 6)
    header.writeUInt16LE(0, 8)
    header.writeUInt32LE(crc32(payload), 14)
    header.writeUInt32LE(payload.length, 18)
    header.writeUInt32LE(payload.length, 22)
    header.writeUInt16LE(nameBytes.length, 26)
    nameBytes.copy(header, 30)
    local.push(header, payload)
    const directory = Buffer.alloc(46 + nameBytes.length)
    directory.writeUInt32LE(0x02014b50, 0)
    directory.writeUInt16LE(20, 4)
    directory.writeUInt16LE(20, 6)
    directory.writeUInt16LE(0, 8)
    directory.writeUInt16LE(0, 10)
    directory.writeUInt32LE(crc32(payload), 16)
    directory.writeUInt32LE(payload.length, 20)
    directory.writeUInt32LE(payload.length, 24)
    directory.writeUInt16LE(nameBytes.length, 28)
    directory.writeUInt32LE(offset, 42)
    nameBytes.copy(directory, 46)
    central.push(directory)
    offset += header.length + payload.length
  }
  const centralData = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralData.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, centralData, end])
}

function makeApk(dir, { omit = [], empty = [], fakeDsh = false } = {}) {
  const apk = join(dir, 'app.apk')
  const names = [...baseEntries, ...prootEntries, 'assets/dsh-runtime.zip']
    .filter((entry) => !omit.includes(entry))
  const entries = names.map((name) => {
    if (name === 'assets/dsh-runtime.zip' && !fakeDsh) return [name, zip([['package.json', '{}']])]
    if (name === 'assets/dsh-runtime.zip') return [name, Buffer.from('PK\x03\x04not-a-zip')]
    return [name, empty.includes(name) ? Buffer.alloc(0) : Buffer.from('payload')]
  })
  writeFileSync(apk, zip(entries))
  return apk
}

function runVerifier(apk, mode = '--proot=required') {
  const dir = resolve(apk, '..')
  const signerDir = join(dir, 'sdk', 'build-tools', '36.0.0')
  const signer = join(signerDir, 'apksigner')
  mkdirSync(signerDir, { recursive: true })
  writeFileSync(signer, Buffer.from('#!/bin/sh\nexit 0\n'), { mode: 0o755 })
  return spawnSync(process.platform === 'win32' ? 'bash.exe' : 'bash', [verifier, apk, '--allow-unsigned', mode], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, ANDROID_HOME: bashPath(join(dir, 'sdk')) },
  })
}

test('strict APK verification requires every native and proot payload', { skip: process.platform === 'win32' ? 'Bash verifier tests run on Linux CI' : false }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'factotum-apk-'))
  try {
    const result = runVerifier(makeApk(dir))
    assert.equal(result.status, 0, `${result.error ?? ''} ${result.stderr}`)
    for (const entry of [...baseEntries, ...prootEntries]) {
      const missing = runVerifier(makeApk(dir, { omit: [entry] }))
      assert.notEqual(missing.status, 0, `missing ${entry} unexpectedly passed`)
    }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('empty payloads and fake or truncated dsh zips fail', { skip: process.platform === 'win32' ? 'Bash verifier tests run on Linux CI' : false }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'factotum-apk-'))
  try {
    for (const entry of [...baseEntries, ...prootEntries]) {
      const empty = runVerifier(makeApk(dir, { empty: [entry] }))
      assert.notEqual(empty.status, 0, `empty ${entry} unexpectedly passed`)
    }
    const fake = runVerifier(makeApk(dir, { fakeDsh: true }))
    assert.notEqual(fake.status, 0, 'fake dsh zip unexpectedly passed')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('historical no-proot mode is explicit and rejects injected proot payloads', { skip: process.platform === 'win32' ? 'Bash verifier tests run on Linux CI' : false }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'factotum-apk-'))
  try {
    const historical = runVerifier(makeApk(dir, { omit: prootEntries }), '--proot=absent')
    assert.equal(historical.status, 0, historical.stderr)
    const injected = runVerifier(makeApk(dir), '--proot=absent')
    assert.notEqual(injected.status, 0, 'injected proot payload unexpectedly passed')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

// dsh-android-attachments 的 PC 侧单测：跑在 dsh 运行时缓存里（依赖要能解析到
// @deepseek-ai/*）。先跑 tools/bundle-dsh.ps1 把插件拷进缓存，再执行：
//
//   node tools/dsh-phone/android-attachments/test.mjs
//
// 覆盖：PNG/JPEG 头解析与尺寸、类型校验、内容寻址存取、摘要校验、请求版本。
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { JPEG_BASE64, PNG_BASE64 } from './test/fixtures.mjs'

const repoRoot = fileURLToPath(new URL('../../../', import.meta.url))
const cache = join(repoRoot, 'android', '.dsh-cache', 'runtime')
const pluginPath = join(cache, 'node_modules', 'dsh-android-attachments', 'index.mjs')

const { default: AndroidAttachmentStore } = await import(pathToFileURL(pluginPath).href)
const libUrl = (pkg, file) =>
  pathToFileURL(join(cache, 'node_modules', '@deepseek-ai', pkg, 'lib', file)).href
const { AttachmentStore } = await import(libUrl('dsh-attachment', 'index.js'))
const { Context } = await import(libUrl('cordis', 'index.js'))

const home = mkdtempSync(join(tmpdir(), 'factotum-attach-'))
// 真 Context：AttachmentStore 的 Service 基类构造时要往 ctx.reflect 注册服务名。
const ctx = new Context()
const store = new AndroidAttachmentStore(ctx, { dshHome: home })
assert.ok(store instanceof AttachmentStore, '必须实现 AttachmentStore 契约')
// ctx.get 拿到的是 cordis 的服务视图（不是同一个对象引用），只校验接口可达。
const resolved = ctx.get('attachments')
assert.equal(typeof resolved?.saveImage, 'function', '要注册成 ctx.attachments')

const png = Buffer.from(PNG_BASE64, 'base64')
const jpeg = Buffer.from(JPEG_BASE64, 'base64')

// --- 1. 尺寸解析（fixture: 200x100）---
const pngRef = await store.saveImage({ data: png, mediaType: 'image/png', name: 'shot' })
assert.equal(pngRef.width, 200, 'PNG 宽')
assert.equal(pngRef.height, 100, 'PNG 高')
assert.equal(pngRef.bytes, png.length)
assert.equal(pngRef.name, 'shot')
assert.match(String(pngRef.attachmentId), /^[0-9a-f]{64}$/, 'attachmentId 是 sha256')

const jpegRef = await store.saveImage({ data: jpeg, mediaType: 'image/jpeg' })
assert.equal(jpegRef.width, 200, 'JPEG 宽')
assert.equal(jpegRef.height, 100, 'JPEG 高')

// --- 2. 类型不符 / 垃圾字节要被拒 ---
await assert.rejects(
  () => store.saveImage({ data: png, mediaType: 'image/jpeg' }),
  (e) => e.code === 'IMAGE_TYPE_MISMATCH',
  'PNG 字节声明成 jpeg 要拒',
)
await assert.rejects(
  () => store.saveImage({ data: Buffer.from('not an image'), mediaType: 'image/png' }),
  (e) => e.code === 'INVALID_IMAGE',
  '非图像字节要拒',
)
await assert.rejects(
  () => store.saveImage({ data: Buffer.alloc(0), mediaType: 'image/png' }),
  (e) => e.code === 'INVALID_IMAGE',
  '空字节要拒',
)

// --- 3. 内容寻址：同样字节 → 同一个 id，且不重复落盘 ---
const again = await store.saveImage({ data: png, mediaType: 'image/png' })
assert.equal(again.attachmentId, pngRef.attachmentId, '同内容同 id')

// --- 4. 读回一致 ---
const stored = await store.readImage(pngRef)
assert.deepEqual(Buffer.from(stored.data), png, '读回字节一致')
assert.deepEqual(stored.ref, pngRef)

// --- 5. 摘要不符 → 损坏 ---
const objectPath = join(home, 'attachments', 'android-v1', String(pngRef.attachmentId))
writeFileSync(objectPath, Buffer.from('tampered'))
await assert.rejects(
  () => store.readImage(pngRef),
  (e) => e.code === 'ATTACHMENT_CORRUPT',
  '被改过的对象要报损坏',
)
await assert.rejects(
  () => store.readImage({ attachmentId: '../../etc/passwd', mediaType: 'image/png' }),
  (e) => e.code === 'INVALID_ATTACHMENT_REF',
  '非法引用要拒',
)

// --- 6. 请求版本：本轮实现不重编码，字节与尺寸原样 ---
const version = await store.readImageRequest(jpegRef, { width: 64, height: 32, maxBytes: 1024 })
assert.equal(version.bytes, jpeg.length)
assert.equal(version.mediaType, 'image/jpeg')
assert.equal(version.width, 200)
assert.equal(version.height, 100)
assert.equal(version.depth, 'uchar')
assert.equal(String(version.variantId), `android-raw-v1:${jpegRef.attachmentId}`)
assert.deepEqual(Buffer.from(version.data), jpeg)

// --- 7. 体积与尺寸上限 ---
await assert.rejects(
  () => store.saveImage({ data: Buffer.concat([png, Buffer.alloc(9 * 1024 * 1024)]), mediaType: 'image/png' }),
  (e) => e.code === 'IMAGE_TOO_LARGE',
  '超过单图体积上限要拒',
)

// --- 8. 媒体类型白名单 ---
assert.deepEqual([...store.imageLimits.mediaTypes], ['image/png', 'image/jpeg'])

// --- 9. 对象确实落在 <dshHome>/attachments/android-v1/<sha256> ---
assert.equal(readFileSync(objectPath, 'utf8'), 'tampered', '第 5 步篡改的正是读回用的那个文件')

rmSync(home, { recursive: true, force: true })
console.log('ok: AndroidAttachmentStore 契约与边界检查通过')

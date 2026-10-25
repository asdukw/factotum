// Factotum phone profile 的附件存储：`ctx.attachments` 的纯 JS 实现。
//
// 为什么不用 dsh 自带的 `@deepseek-ai/dsh-attachment-local`：它依赖 sharp 做
// 归一化与质量阶梯编码，而 sharp 没有 Android 预编译产物（bundle 用
// --os=android 装，原生二进制根本不在），一调用 saveImage 就崩。
//
// 与 attachment-local 的差异（都是有意的取舍）：
//   * **不做重编码**：截图由 Kotlin 侧按尺寸与体积预算缩好再送进来；
//     readImageRequest 直接回存图字节，路由目标更小也不缩（报告真实尺寸）。
//     代价是路由的 maxBytes 只是个软目标，超了也不会有质量阶梯兜底。
//   * **只认 PNG / JPEG**：尺寸解析走纯 JS 头解析（PNG IHDR / JPEG SOF）。
//   * **内容寻址**：文件名就是 sha256，读回时校验摘要，损坏报 ATTACHMENT_CORRUPT。
//
// 契约与 attachment-local 一致：实现 AttachmentStore 的四个抽象成员
// （imageLimits / validateImage / saveImage / readImage）+ readImageRequest。
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import {
  AttachmentError,
  AttachmentId,
  AttachmentStore,
  ImageVariantId,
} from '@deepseek-ai/dsh-attachment'
import { resolveDshHome } from '@deepseek-ai/dsh-home-paths'
import z from '@deepseek-ai/schemastery'

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

/** 附件目录版本号：换存储布局时改它，旧目录自然失效。 */
const STORE_VERSION = 'android-v1'

/** 请求版本的固定编码参数标识（本实现不重编码，因此只随附件 id 变化）。 */
const VARIANT_PREFIX = 'android-raw-v1:'

const ID_PATTERN = /^[0-9a-f]{64}$/

function asBuffer(data) {
  return Buffer.from(data.buffer, data.byteOffset, data.byteLength)
}

/** PNG：签名 + 头一个块必须是 IHDR，宽高在 16..24。 */
function pngDimensions(buffer) {
  if (buffer.length < 24) return undefined
  for (let i = 0; i < PNG_SIGNATURE.length; i++) {
    if (buffer[i] !== PNG_SIGNATURE[i]) return undefined
  }
  if (buffer.subarray(12, 16).toString('latin1') !== 'IHDR') return undefined
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) }
}

/** JPEG：扫段找 SOF（排除 DHT/JPG/DAC），高在 +5、宽在 +7。 */
function jpegDimensions(buffer) {
  if (buffer.length < 4 || buffer[0] !== 0xff || buffer[1] !== 0xd8) return undefined
  let offset = 2
  while (offset + 9 <= buffer.length) {
    if (buffer[offset] !== 0xff) {
      offset += 1
      continue
    }
    const marker = buffer[offset + 1]
    // 无长度字段的标记：填充、SOI、RSTn
    if (marker === 0xff || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2
      continue
    }
    const isStartOfFrame =
      marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc
    if (isStartOfFrame) {
      return {
        height: buffer.readUInt16BE(offset + 5),
        width: buffer.readUInt16BE(offset + 7),
      }
    }
    offset += 2 + buffer.readUInt16BE(offset + 2)
  }
  return undefined
}

/**
 * 解析编码字节的类型与尺寸。
 *
 * @param data - 编码后的图像字节。
 * @param declared - 调用方声明的 media type。
 * @returns 实际尺寸。
 * @throws AttachmentError 未识别 / 类型不符 / 尺寸非法。
 */
function decodeImage(data, declared) {
  const buffer = asBuffer(data)
  const png = pngDimensions(buffer)
  if (png !== undefined) {
    if (declared !== 'image/png') {
      throw new AttachmentError(
        `Image bytes are image/png but were declared as ${declared}.`,
        'IMAGE_TYPE_MISMATCH',
      )
    }
    if (png.width === 0 || png.height === 0) {
      throw new AttachmentError('Image header reports a zero dimension.', 'INVALID_IMAGE')
    }
    return png
  }
  const jpeg = jpegDimensions(buffer)
  if (jpeg !== undefined) {
    if (declared !== 'image/jpeg') {
      throw new AttachmentError(
        `Image bytes are image/jpeg but were declared as ${declared}.`,
        'IMAGE_TYPE_MISMATCH',
      )
    }
    if (jpeg.width === 0 || jpeg.height === 0) {
      throw new AttachmentError('Image header reports a zero dimension.', 'INVALID_IMAGE')
    }
    return jpeg
  }
  throw new AttachmentError(
    'Only PNG and JPEG bytes are accepted by this attachment provider.',
    'INVALID_IMAGE',
  )
}

export default class AndroidAttachmentStore extends AttachmentStore {
  static Config = z.object({
    dshHome: z.string(),
    maxImageBytes: z.number().step(1).min(1).default(8 * 1024 * 1024),
    maxImagesPerMessage: z.number().step(1).min(1).default(8),
    maxMessageImageBytes: z.number().step(1).min(1).default(16 * 1024 * 1024),
    maxImagePixels: z.number().step(1).min(1).default(4096 * 4096),
    maxImageDimension: z.number().step(1).min(1).default(4096),
  })

  constructor(ctx, config = {}) {
    super(ctx)
    const home = resolveDshHome(config.dshHome)
    this.root = join(home, 'attachments', STORE_VERSION)
    this.imageLimits = Object.freeze({
      maxImageBytes: config.maxImageBytes ?? 8 * 1024 * 1024,
      maxImagesPerMessage: config.maxImagesPerMessage ?? 8,
      maxMessageImageBytes: config.maxMessageImageBytes ?? 16 * 1024 * 1024,
      maxImagePixels: config.maxImagePixels ?? 4096 * 4096,
      maxImageDimension: config.maxImageDimension ?? 4096,
      mediaTypes: Object.freeze(['image/png', 'image/jpeg']),
    })
  }

  pathOf(attachmentId) {
    return join(this.root, attachmentId)
  }

  assertRef(ref) {
    const id = String(ref?.attachmentId ?? '')
    if (!ID_PATTERN.test(id)) {
      throw new AttachmentError(
        'The attachment reference is not a content digest issued by this provider.',
        'INVALID_ATTACHMENT_REF',
      )
    }
  }

  /** 校验尺寸与体积；不落盘。 */
  async validateImage(input) {
    const { maxImageBytes, maxImagePixels, maxImageDimension } = this.imageLimits
    if (input.data.byteLength > maxImageBytes) {
      throw new AttachmentError(
        `Image exceeds the ${maxImageBytes}-byte limit for one image.`,
        'IMAGE_TOO_LARGE',
      )
    }
    const { width, height } = decodeImage(input.data, input.mediaType)
    if (width > maxImageDimension || height > maxImageDimension) {
      throw new AttachmentError(
        `Image ${width}x${height} exceeds the ${maxImageDimension}px dimension limit.`,
        'IMAGE_DIMENSION_TOO_LARGE',
      )
    }
    if (width * height > maxImagePixels) {
      throw new AttachmentError(
        `Image ${width}x${height} exceeds the ${maxImagePixels}-pixel budget.`,
        'IMAGE_TOO_MANY_PIXELS',
      )
    }
  }

  /** 内容寻址落盘：摘要即文件名，重复写入直接复用已有对象。 */
  async saveImage(input) {
    await this.validateImage(input)
    const digest = createHash('sha256').update(asBuffer(input.data)).digest('hex')
    await mkdir(this.root, { recursive: true })
    const target = this.pathOf(digest)
    const exists = await readFile(target).then(
      () => true,
      () => false,
    )
    if (!exists) {
      const staging = `${target}.${randomUUID()}.tmp`
      try {
        await writeFile(staging, asBuffer(input.data))
        await rename(staging, target)
      } catch (cause) {
        await rm(staging, { force: true })
        throw new AttachmentError('Failed to persist the image attachment.', 'ATTACHMENT_WRITE_FAILED', {
          cause,
        })
      }
    }
    const { width, height } = decodeImage(input.data, input.mediaType)
    return {
      attachmentId: AttachmentId(digest),
      mediaType: input.mediaType,
      bytes: input.data.byteLength,
      width,
      height,
      ...(input.name === undefined ? {} : { name: input.name }),
    }
  }

  /** 读回并校验摘要：内容与引用不一致一律当损坏处理。 */
  async readImage(ref, signal) {
    signal?.throwIfAborted()
    this.assertRef(ref)
    let data
    try {
      data = await readFile(this.pathOf(String(ref.attachmentId)))
    } catch (cause) {
      throw new AttachmentError('The stored image attachment was not found.', 'ATTACHMENT_NOT_FOUND', {
        cause,
      })
    }
    const digest = createHash('sha256').update(data).digest('hex')
    if (digest !== String(ref.attachmentId)) {
      throw new AttachmentError(
        'Stored image bytes no longer match the recorded digest.',
        'ATTACHMENT_CORRUPT',
      )
    }
    return { ref, data }
  }

  /**
   * 路由要的请求版本：本实现不重编码，直接给存图字节。
   *
   * 路由的 `target` 比源图小时这里不会缩图 —— 报告的是真实尺寸，调用方按真实
   * 字节数走预算判断（截图在 Kotlin 侧已经缩到预算内）。
   */
  async readImageRequest(ref, _target, signal) {
    const stored = await this.readImage(ref, signal)
    return {
      variantId: ImageVariantId(`${VARIANT_PREFIX}${ref.attachmentId}`),
      attachment: ref,
      data: stored.data,
      mediaType: ref.mediaType,
      bytes: stored.data.byteLength,
      width: ref.width,
      height: ref.height,
      depth: 'uchar',
      space: 'srgb',
    }
  }
}

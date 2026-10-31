package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/** 有界读取外部数据；解析错误不包含 JSON 正文。 */
object CardInput {
  const val MAX_FILE = 32 * 1024 * 1024
  const val MAX_JSON = 8 * 1024 * 1024
  private val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
  private val json = Json { isLenient = true }
  private val keywords = listOf("ccv3", "chara", "character")

  data class Parsed(val root: JsonObject, val keyword: String? = null, val fallback: Boolean = false)

  private data class Candidate(val keyword: String, val root: JsonObject?)

  fun read(stream: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = stream.read(buffer)
      if (count < 0) break
      if (out.size() + count > MAX_FILE) throw CharacterImporter.ImportException("文件超过 32 MiB 上限")
      out.write(buffer, 0, count)
    }
    return out.toByteArray()
  }

  fun parse(bytes: ByteArray): Parsed {
    checkSize(bytes.size, MAX_FILE, "文件超过 32 MiB 上限")
    if (bytes.size >= zipMagic.size && zipMagic.indices.all { bytes[it] == zipMagic[it] }) {
      return parseCharx(bytes)
    }
    if (bytes.size < magic.size || !magic.indices.all { bytes[it] == magic[it] }) {
      checkSize(bytes.size, MAX_JSON, "JSON 超过 8 MiB 上限")
      return Parsed(
        parseJson(String(bytes, Charsets.UTF_8))
          ?: throw CharacterImporter.ImportException("卡数据不是合法的 JSON 对象"),
      )
    }
    val candidates = scan(bytes)
    val selected = keywords.firstNotNullOfOrNull { key -> candidates.firstOrNull { it.keyword == key && it.root != null } }
      ?: throw CharacterImporter.ImportException("这张 PNG 里没有角色卡数据（找不到有效 chara / ccv3），或载荷损坏、超限")
    return Parsed(selected.root!!, selected.keyword, selected.keyword != "ccv3" && candidates.any { it.keyword == "ccv3" })
  }

  private fun checkSize(size: Int, limit: Int, message: String) {
    if (size > limit) throw CharacterImporter.ImportException(message)
  }

  /** CHARX：ZIP 根目录的 `card.json`。资产实体先不解析，兼容报告会说明。 */
  private fun parseCharx(bytes: ByteArray): Parsed {
    val text = readCharxJson(bytes)
    val root = text?.let { parseJson(it) }
      ?: throw CharacterImporter.ImportException("CHARX 里没有可用的 card.json（缺失、损坏或超过 8 MiB）")
    return Parsed(root, "charx")
  }

  private fun readCharxJson(bytes: ByteArray): String? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        if (!entry.isDirectory && entry.name.lowercase().trimStart('/') == "card.json") return readBounded(zip)
        entry = zip.nextEntry
      }
    }
    return null
  }

  private fun readBounded(zip: ZipInputStream): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val count = zip.read(buffer)
      if (count < 0) break
      if (out.size() + count > MAX_JSON) return null
      out.write(buffer, 0, count)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
  }

  private fun scan(bytes: ByteArray): List<Candidate> {
    val candidates = linkedMapOf<String, Candidate>()
    var offset = magic.size
    while (offset.toLong() + 12 <= bytes.size) {
      val length = readInt(bytes, offset)
      if (length < 0 || offset.toLong() + 12 + length > bytes.size) {
        throw CharacterImporter.ImportException("PNG 块被截断或长度无效")
      }
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      if (type == "IEND") break
      if (type == "tEXt" || type == "iTXt") {
        val chunk = bytes.copyOfRange(offset + 8, offset + 8 + length)
        val candidate = decodeChunk(chunk, type)
        if (candidate != null && candidates[candidate.keyword]?.root == null) candidates[candidate.keyword] = candidate
      }
      offset += 12 + length
    }
    return candidates.values.toList()
  }

  private fun decodeChunk(chunk: ByteArray, type: String): Candidate? {
    val nul = chunk.indexOf(0)
    if (nul <= 0) return null
    val keyword = String(chunk, 0, nul, Charsets.ISO_8859_1).lowercase()
    if (keyword !in keywords) return null
    val root = runCatching {
      val body = if (type == "tEXt") chunk.copyOfRange(nul + 1, chunk.size) else intlBody(chunk, nul)
      val text = String(body, Charsets.UTF_8).trim()
      val decoded = if (text.startsWith("{")) {
        text
      } else {
        String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8)
      }
      if (decoded.toByteArray(Charsets.UTF_8).size > MAX_JSON) null else parseJson(decoded)?.takeIf(::hasIdentity)
    }.getOrNull()
    return Candidate(keyword, root)
  }

  private fun hasIdentity(root: JsonObject): Boolean {
    val data = root["data"] as? JsonObject ?: root
    // 与 CharacterImporter 保持同一判据：旁白/设定卡可能只有 scenario 或 first_mes。
    return listOf("description", "personality", "scenario", "first_mes", "first_message").any {
      val value = data[it] as? kotlinx.serialization.json.JsonPrimitive
      value?.isString == true && value.content.isNotBlank()
    }
  }

  private fun intlBody(chunk: ByteArray, nul: Int): ByteArray {
    require(nul + 3 <= chunk.size)
    val flag = chunk[nul + 1].toInt()
    require(flag in 0..1 && chunk[nul + 2].toInt() == 0)
    val languageEnd = (nul + 3 until chunk.size).firstOrNull { chunk[it].toInt() == 0 } ?: error("language")
    val translatedEnd = (languageEnd + 1 until chunk.size).firstOrNull { chunk[it].toInt() == 0 } ?: error("translated")
    val body = chunk.copyOfRange(translatedEnd + 1, chunk.size)
    return if (flag == 1) inflate(body) else body
  }

  private fun inflate(body: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
      inflater.setInput(body)
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(8192)
      while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        check(count > 0 || inflater.finished()) { "压缩载荷不完整" }
        require(out.size() + count <= MAX_JSON) { "解压后 JSON 超过 8 MiB 上限" }
        out.write(buffer, 0, count)
      }
      return out.toByteArray()
    } finally {
      inflater.end()
    }
  }

  private fun parseJson(text: String): JsonObject? =
    runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

  private fun readInt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
      ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
}

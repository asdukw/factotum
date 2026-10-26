package com.tappet.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.Inflater

/**
 * 从 SillyTavern 角色卡（PNG 或 JSON）读出一张 [CharacterCard]。
 *
 * 只取「身份与语气」这一层，**不**接受卡里的行为指令：
 * - `system_prompt` / `post_history_instructions` 丢弃 —— 社区卡里这两段常写着「忽略一切既有规则」
 *   「你必须永远以……回答」，与手机 agent 的操作契约直接冲突，等于把执行权交给一张外部下载的卡；
 * - `mes_example` 丢弃 —— 占 token，而且模型会过度模仿示例里的口癖；
 * - `{{char}}` / `{{user}}` 保留原样，渲染时统一替换（见 [CharacterStore.renderPrompt]）。
 *
 * 解析尽量宽松：卡格式的版本、字段名、嵌套层级都不统一（v1 是平铺，v2/v3 在 `data` 下，
 * 世界书有时叫 `character_book`、有时叫 `world`、有时压根没有）。宁可丢字段，也不要因为
 * 一个不认识的键就整张卡导入失败。
 */
object CharacterImporter {
  /** 导入结果：卡 + 给用户看的提示（哪些字段被丢掉、为什么）。 */
  data class Result(val card: CharacterCard, val notes: List<String>)

  class ImportException(message: String) : Exception(message)

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private val CARD_KEYWORDS = setOf("chara", "ccv3", "character")

  /** 入口：字节流可能是 PNG（卡藏在 tEXt/iTXt 块里），也可能是直接的 JSON。 */
  fun fromBytes(bytes: ByteArray, fallbackName: String): Result {
    val payload = if (startsWithPng(bytes)) extractFromPng(bytes) else String(bytes, Charsets.UTF_8)
    val root =
      runCatching { json.parseToJsonElement(payload).jsonObject }
        .getOrElse { throw ImportException("卡数据不是合法的 JSON（${it.message ?: "解析失败"}）") }
    return map(root, fallbackName)
  }

  private fun startsWithPng(bytes: ByteArray): Boolean =
    bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }

  /**
   * 逐块扫 PNG：`tEXt`（key\0base64）与 `iTXt`（key\0flag\0method\0lang\0translated\0正文，
   * 正文可能是 UTF-8 明文，也可能按 zlib 压过）。
   *
   * 不依赖图片解码库：这里只需要读懂块头，用 BitmapFactory 解一遍图纯属浪费内存（卡的图经常 2MB+）。
   */
  private fun extractFromPng(bytes: ByteArray): String {
    var offset = PNG_MAGIC.size
    while (offset + 8 <= bytes.size) {
      val length = readInt(bytes, offset)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      val dataStart = offset + 8
      // 三个终止条件并成一条：块长度越界（文件被截断）、遇到 IEND、扫完
      if (length < 0 || dataStart + length + 4 > bytes.size || type == "IEND") break
      val text =
        when (type) {
          "tEXt" -> decodeTextChunk(bytes, dataStart, length)
          "iTXt" -> decodeInternationalTextChunk(bytes, dataStart, length)
          else -> null
        }
      if (text != null) return text
      offset = dataStart + length + 4
    }
    throw ImportException("这张 PNG 里没有角色卡数据（找不到 chara / ccv3 文本块）")
  }

  private fun decodeTextChunk(bytes: ByteArray, start: Int, length: Int): String? {
    val chunk = bytes.copyOfRange(start, start + length)
    val nul = chunk.indexOf(0)
    if (nul <= 0) return null
    val keyword = String(chunk, 0, nul, Charsets.ISO_8859_1).lowercase()
    if (keyword !in CARD_KEYWORDS) return null
    // tEXt 正文是 Latin-1 文本，但卡一律把 base64 放这里
    val body = String(chunk, nul + 1, chunk.size - nul - 1, Charsets.ISO_8859_1).trim()
    return decodeBase64OrPlain(body)
  }

  private fun decodeInternationalTextChunk(bytes: ByteArray, start: Int, length: Int): String? {
    val chunk = bytes.copyOfRange(start, start + length)
    val nul = chunk.indexOf(0)
    if (nul <= 0 || nul + 3 > chunk.size) return null
    if (String(chunk, 0, nul, Charsets.ISO_8859_1).lowercase() !in CARD_KEYWORDS) return null
    val body = intlBody(chunk, nul + 3) ?: return null
    // 字节 nul+1 = compression flag，nul+2 = compression method
    val text =
      if (chunk[nul + 1].toInt() == 1) runCatching { inflate(body) }.getOrNull() else String(body, Charsets.UTF_8)
    return text?.let { decodeBase64OrPlain(it.trim()) }
  }

  /** 跳过 iTXt 的 languageTag\0translatedKeyword\0，剩下的就是正文。 */
  private fun intlBody(chunk: ByteArray, from: Int): ByteArray? {
    val languageEnd = chunk.indexOfFrom(0, from)
    val translatedEnd = if (languageEnd < 0) -1 else chunk.indexOfFrom(0, languageEnd + 1)
    return if (translatedEnd < 0) null else chunk.copyOfRange(translatedEnd + 1, chunk.size)
  }

  private fun inflate(body: ByteArray): String {
    val inflater = Inflater()
    inflater.setInput(body)
    val out = ByteArray(64 * 1024)
    val size = inflater.inflate(out)
    inflater.end()
    return String(out, 0, size, Charsets.UTF_8)
  }

  /**
   * 卡有两种写法：`tEXt` 里是 base64，也有工具直接塞明文 JSON。
   * 先按 base64 解，解出来不像 JSON 就退回原文 —— 猜错一次比报错好。
   */
  private fun decodeBase64OrPlain(text: String): String? {
    if (text.isEmpty()) return null
    // 用 java.util 的 MIME 解码器：社区卡的 base64 偶尔带换行，严格解码器会直接抛。
    // （不用 android.util.Base64：它在本地单测里是空实现，会把整条导入链路测不到。）
    val decoded =
      runCatching { String(java.util.Base64.getMimeDecoder().decode(text), Charsets.UTF_8) }.getOrNull()
    return when {
      decoded != null && decoded.trimStart().startsWith("{") -> decoded
      text.trimStart().startsWith("{") -> text
      else -> null
    }
  }

  private fun ByteArray.indexOfFrom(value: Byte, from: Int): Int {
    for (index in from until size) if (this[index] == value) return index
    return -1
  }

  private fun readInt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
      ((bytes[at + 1].toInt() and 0xFF) shl 16) or
      ((bytes[at + 2].toInt() and 0xFF) shl 8) or
      (bytes[at + 3].toInt() and 0xFF)

  /** 把卡 JSON 映射成我们的模型；`data` 存在时以它为准（v2/v3），否则按 v1 平铺读。 */
  private fun map(root: JsonObject, fallbackName: String): Result {
    val data = root["data"]?.jsonObject ?: root
    val notes = mutableListOf<String>()

    if (data["system_prompt"]?.string()?.isNotBlank() == true) {
      notes += "已忽略卡里的 system_prompt（外部卡常在这里写「忽略一切规则」，不能让它接管手机 agent）"
    }
    if (data["post_history_instructions"]?.string()?.isNotBlank() == true) {
      notes += "已忽略 post_history_instructions（同上：属于行为指令，不属于人设）"
    }
    if (data["mes_example"]?.string()?.isNotBlank() == true) {
      notes += "已丢弃 mes_example（示例对话占 token，且模型会过度模仿其中的口癖）"
    }

    val name = data["name"]?.string()?.trim().orEmpty().ifEmpty { fallbackName }
    val description = data["description"]?.string().orEmpty().trim()
    val personality = data["personality"]?.string().orEmpty().trim()
    if (description.isBlank() && personality.isBlank()) {
      throw ImportException("这张卡没有 description / personality，导入后没法扮演")
    }

    val card =
      CharacterCard(
        id = newId(),
        name = name,
        tagline = data["creator_notes"]?.string()?.trim()?.lines()?.firstOrNull()?.take(40).orEmpty(),
        tags = data["tags"]?.let { element -> (element as? JsonArray)?.mapNotNull { it.string() } }.orEmpty().take(6),
        description = description,
        personality = personality,
        scenario = data["scenario"]?.string().orEmpty().trim(),
        firstMes = (data["first_mes"] ?: data["first_message"])?.string().orEmpty().trim(),
        world = readWorld(data),
        creator = data["creator"]?.string().orEmpty().trim(),
        license = "",
        imported = true,
      )
    if (card.firstMes.isBlank()) notes += "这张卡没有开场白，进去后它会直接接着你说的话开口"
    if (card.world.isEmpty()) notes += "这张卡没有世界设定，只有人设"
    return Result(card, notes)
  }

  private fun readWorld(data: JsonObject): List<WorldEntry> {
    val book = data["character_book"]?.jsonObject ?: data["world"]?.jsonObject ?: return emptyList()
    val entries = book["entries"]?.jsonArray ?: book["items"]?.jsonArray ?: return emptyList()
    return entries.mapNotNull { element ->
      val entry = element.jsonObject
      val content = entry["content"]?.string()?.trim().orEmpty()
      if (content.isEmpty()) return@mapNotNull null
      val extensions = entry["extensions"]?.jsonObject
      WorldEntry(
        keys = readKeys(entry, "keys", "key"),
        content = content,
        order = entry["insertion_order"]?.jsonPrimitive?.intOrNull ?: 0,
        // `constant` 在 v2 规范里是「每轮都注入」；`enabled: false` 的条目直接跳过
        constant = entry["constant"]?.jsonPrimitive?.booleanOrNull == true,
        // 编排参数在 v3 卡里放在 extensions 里，v2 卡偶尔放在顶层 —— 两处都认
        depth = extInt(extensions, entry, "depth", 4),
        secondary = readKeys(entry, "secondary_keys", "secondaryKeys", "keysecondary"),
        secondaryLogic = SecondaryLogic.fromRaw(entry["selectiveLogic"]?.jsonPrimitive?.intOrNull),
        exclude = readKeys(entry, "exclude_keys", "excludeKeys"),
        probability = extInt(extensions, entry, "probability", 100),
        sticky = extInt(extensions, entry, "sticky", 0),
        cooldown = extInt(extensions, entry, "cooldown", 0),
      ).takeIf { entry["enabled"]?.jsonPrimitive?.booleanOrNull != false }
    }
  }

  /** 读一个整型编排参数：先看 extensions，再看条目顶层，都没有就用 [fallback]。 */
  private fun extInt(extensions: JsonObject?, entry: JsonObject, name: String, fallback: Int): Int =
    extensions?.get(name)?.jsonPrimitive?.intOrNull ?: entry[name]?.jsonPrimitive?.intOrNull ?: fallback

  /** 读一组关键词：数组取每项，单字符串当一条；空串丢掉（真卡里偶有空元素）。 */
  private fun readKeys(entry: JsonObject, vararg names: String): List<String> =
    names.firstNotNullOfOrNull { name ->
      when (val value = entry[name]) {
        is JsonArray -> value.mapNotNull { it.string() }
        null -> null
        else -> listOfNotNull(value.string())
      }
    }
      ?.filter { it.isNotBlank() }
      ?: emptyList()

  private fun newId(): String =
    "imported-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

  /** 只认字符串原语：社区卡里的 `name` 偶尔会是一个对象或数字，那种情况按「没有」处理。 */
  private fun JsonElement.string(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

  private fun JsonObject.string(key: String): String? = this[key]?.string()
}

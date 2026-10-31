package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 外部卡只投影白名单；原始指令及扩展隔离存储，供主动导出恢复。 */
object CharacterImporter {
  data class Result(val card: CharacterCard, val notes: List<String>)

  class ImportException(message: String) : Exception(message)

  fun fromBytes(bytes: ByteArray, fallbackName: String): Result {
    val parsed = CardInput.parse(bytes)
    val result = map(parsed.root, fallbackName)
    val extra = mutableListOf<CardCompatibilityNote>()
    if (parsed.keyword in setOf("chara", "ccv3", "character")) {
      extra += CardCompatibilityNote(
        "png_source",
        "PNG",
        CardHandling.CONVERTED,
        "采用 ${parsed.keyword} 载荷；只保存 JSON，不保存图片与附件实体。",
      )
    }
    if (parsed.keyword == "charx") {
      extra +=
        CardCompatibilityNote(
          "charx_source",
          "CHARX",
          CardHandling.CONVERTED,
          "只读取了 card.json；嵌入式图片与附件实体未解包，导出 CHARX 时不包含这些资产。",
        )
    }
    if (parsed.fallback) {
      extra += CardCompatibilityNote(
        "png_fallback",
        "PNG.ccv3",
        CardHandling.UNAVAILABLE,
        "V3 载荷无效，已明确回落到有效的旧版载荷。",
      )
    }
    val metadata = result.card.importMetadata!!
    val card = result.card.copy(importMetadata = metadata.copy(report = metadata.report + extra))
    return Result(card, card.compatibilityNotes().map { it.summary() })
  }

  private fun compatibility(data: JsonObject, root: JsonObject): List<CardCompatibilityNote> = buildList {
    add(CardCompatibilityNote("identity", "人设/场景/开场白", CardHandling.ACTIVE, "仅受支持字段进入运行投影。"))
    listOf("system_prompt", "post_history_instructions", "mes_example").forEach { field ->
      if (data[field] != null) {
        add(
          CardCompatibilityNote(
            "archived_prompt",
            field,
            CardHandling.PRESERVED,
            "原样保存供主动导出；不发送给模型，不展开宏，不提供执行入口。",
          ),
        )
      }
    }
    add(
      CardCompatibilityNote(
        "raw_json",
        "原始 JSON",
        CardHandling.PRESERVED,
        "未知字段、作者说明、完整标签和扩展保留；本地简介不覆盖原作者说明。",
      ),
    )
    add(
      CardCompatibilityNote(
        "normalize",
        "V3 导出",
        CardHandling.CONVERTED,
        "导出补齐必需字段及类型；损坏字段或非对象条目可能规范化，原值仅在私有原始记录中保留。",
      ),
    )
    if ((root["spec_version"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { it > 3.0 } == true) {
      add(CardCompatibilityNote("newer_version", "spec_version", CardHandling.PRESERVED, "来源版本较新，不保证所有能力可运行。"))
    }
    add(
      CardCompatibilityNote(
        "unsupported",
        "高级能力",
        CardHandling.PRESERVED,
        "资源引用、昵称、群聊开场白、decorator、脚本和未知扩展不作为已支持运行能力；不下载或执行资源。",
      ),
    )
    if (data["assets"] != null) {
      add(
        CardCompatibilityNote(
          "assets",
          "assets",
          CardHandling.UNAVAILABLE,
          "只保留 JSON 引用；图片、嵌入附件实体未保存，不能视为完整资源备份。",
        ),
      )
    }
  }

  /** 把卡 JSON 映射成我们的模型；`data` 存在时以它为准（v2/v3），否则按 v1 平铺读。 */
  private fun map(root: JsonObject, fallbackName: String): Result {
    val data = (root["data"] as? JsonObject) ?: root

    val name = data["name"]?.string()?.trim().orEmpty().ifEmpty { fallbackName }
    val description = data["description"]?.string().orEmpty().trim()
    val personality = data["personality"]?.string().orEmpty().trim()
    // 空的人设字段在 CCv2/V3 里合法：旁白卡、设定卡、CYOA 卡经常只写 scenario / first_mes。
    val hasAnyContent =
      listOf("description", "personality", "scenario", "first_mes", "first_message").any {
        data[it]?.string()?.isNotBlank() == true
      }
    if (!hasAnyContent) {
      throw ImportException("这张卡没有任何扮演内容（description / personality / scenario / first_mes 都为空）")
    }

    val card =
      CharacterCard(
        id = newId(),
        name = name,
        tagline = data["creator_notes"]?.string()?.trim()?.lines()?.firstOrNull()?.take(40).orEmpty(),
        // 完整保留：截断只属于列表展示，写回导出时必须带着作者的全部标签。
        tags = data["tags"]?.let { element -> (element as? JsonArray)?.mapNotNull { it.string() } }.orEmpty(),
        description = description,
        personality = personality,
        scenario = data["scenario"]?.string().orEmpty().trim(),
        firstMes = (data["first_mes"] ?: data["first_message"])?.string().orEmpty().trim(),
        alternateGreetings = readGreetings(data, "alternate_greetings", "alternateGreetings"),
        groupOnlyGreetings = readGreetings(data, "group_only_greetings", "groupOnlyGreetings"),
        nickname = data["nickname"]?.string()?.trim().orEmpty(),
        world = readWorld(data),
        creator = data["creator"]?.string().orEmpty().trim(),
        license = "",
        imported = true,
      )
    val report = compatibility(data, root)
    val archived = CardRoundTrip.attach(card, root, report)
    return Result(archived, archived.compatibilityNotes().map { it.summary() })
  }

  private fun readWorld(data: JsonObject): List<WorldEntry> {
    val book = CardRoundTrip.findBook(data) ?: return emptyList()
    return WorldEntryReader.entriesOf(book["entries"] as JsonArray)
  }

  /** 开场白数组：v3 里是 `alternate_greetings` / `group_only_greetings`，个别卡写成驼峰。 */
  private fun readGreetings(data: JsonObject, vararg keys: String): List<String> =
    keys.firstNotNullOfOrNull { data[it] }
      ?.let { element -> if (element is JsonArray) element else JsonArray(listOf(element)) }
      ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim() }
      ?.filter { it.isNotEmpty() }
      ?: emptyList()

  private fun newId(): String =
    "imported-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

  /** 只认字符串原语：社区卡里的 `name` 偶尔会是一个对象或数字，那种情况按「没有」处理。 */
  private fun JsonElement.string(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

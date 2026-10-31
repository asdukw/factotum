package com.zoneu.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 只用于保存和主动导出；prompt 编排不得读取这些原始内容。 */
@Serializable
data class CardImportMetadata(
  val raw: JsonObject? = null,
  val baseline: JsonObject = JsonObject(emptyMap()),
  val sources: List<WorldBookSource> = emptyList(),
  val report: List<CardCompatibilityNote> = emptyList(),
  /** 私有收据与来源；CardRoundTrip 不将这些字段导出。 */
  val contentHash: String = "",
  val originHost: String = "",
  val originUrl: String = "",
)

@Serializable
data class WorldBookSource(
  val fingerprint: String,
  val book: JsonObject,
  val slots: List<WorldSourceSlot>,
)

@Serializable
data class WorldSourceSlot(
  val raw: JsonElement,
  val token: String? = null,
  val baseline: JsonObject = JsonObject(emptyMap()),
  val duplicate: Boolean = false,
)

@Serializable
enum class CardHandling(val label: String) {
  ACTIVE("已启用"),
  PRESERVED("仅保留"),
  CONVERTED("转换补齐"),
  UNAVAILABLE("无法保留"),
}

@Serializable
data class CardCompatibilityNote(
  val code: String,
  val path: String,
  val handling: CardHandling,
  val detail: String,
  val count: Int = 1,
) {
  fun summary(): String = "${handling.label} · $path：$detail" + if (count != 1) "（$count 项）" else ""
}

/** 旧卡的缺失信息无法推断，不能伪称原样保真。 */
fun CharacterCard.compatibilityNotes(): List<CardCompatibilityNote> =
  importMetadata?.report ?: listOf(
    CardCompatibilityNote(
      "no_source",
      "原始文档",
      CardHandling.UNAVAILABLE,
      "没有保存原始来源，仅能导出现有字段；旧卡已丢失的内容需重新导入原文件恢复。",
    ),
  )

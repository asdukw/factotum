package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/** 仅识别精确公开角色路径；不把相似主机或任意路径当成 Chub API。 */
internal object ChubCardAdapter {
  private val hosts = setOf("chub.ai", "www.chub.ai", "characterhub.org", "www.characterhub.org")

  fun apiUrl(url: HttpUrl): HttpUrl? {
    if (url.host !in hosts) return null
    val parts = url.pathSegments.filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "characters") it.drop(1) else it }
    if (parts.size != 2 || parts.any { !validSegment(it) }) {
      throw CardOperationException("CARD_UNSUPPORTED_SOURCE")
    }
    return url.newBuilder().host("api.chub.ai").port(443)
      .encodedPath("/api/characters/").addPathSegment(parts[0]).addPathSegment(parts[1])
      .query("full=true").fragment(null).build()
  }

  private fun sourceChanged(): Nothing = throw CardOperationException("CARD_SOURCE_CHANGED")

  private fun validSegment(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_.-]{1,160}")) && value !in listOf(".", "..")

  fun convert(bytes: ByteArray, source: HttpUrl): ByteArray {
    require(bytes.size <= CardInput.MAX_JSON) { "CARD_TOO_LARGE" }
    val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
      ?: throw CardOperationException("CARD_SOURCE_CHANGED")
    val node = root["node"] as? JsonObject ?: sourceChanged()
    val definition = node["definition"] as? JsonObject ?: sourceChanged()
    val mapped = mapOf(
      "name" to "name",
      "description" to "personality",
      "personality" to "tavern_personality",
      "scenario" to "scenario",
      "first_mes" to "first_message",
      "mes_example" to "example_dialogs",
      "creator_notes" to "description",
      "system_prompt" to "system_prompt",
      "post_history_instructions" to "post_history_instructions",
      "alternate_greetings" to "alternate_greetings",
      "character_book" to "embedded_lorebook",
      "character_version" to "character_version",
    )
    val data = mutableMapOf<String, JsonElement>()
    mapped.forEach { (key, field) -> definition[field]?.let { data[key] = it } }
    data["tags"] = node["topics"] as? JsonArray ?: JsonArray(emptyList())
    data["creator"] = JsonPrimitive(
      source.pathSegments.filter { it.isNotEmpty() }.let {
        if (it.firstOrNull() == "characters") it.getOrNull(1).orEmpty() else it.firstOrNull().orEmpty()
      },
    )
    val ext = (definition["extensions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    val archive = buildJsonObject {
      put("node", JsonObject(node - "definition" - "topics"))
      put("definition", JsonObject(definition.filterKeys { it !in mapped.values && it != "extensions" }))
      put("response", JsonObject(root - "node"))
    }
    var key = "com.zoneu.chub_source"
    var suffix = 1
    while (key in ext) key = "com.zoneu.chub_source.${suffix++}"
    ext[key] = archive
    data["extensions"] = JsonObject(ext)
    return buildJsonObject {
      put("spec", "chara_card_v2")
      put("spec_version", "2.0")
      put("data", JsonObject(data))
    }.toString().toByteArray(Charsets.UTF_8)
  }
}

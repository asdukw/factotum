package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One-shot public snapshot. Shared identities and revision metadata never become card extensions. */
internal object WorldBookFormatPortable {
  fun export(card: CharacterCard, books: List<WorldBook>): String {
    val root = CardRoundTrip.card(card)
    if (books.isEmpty()) return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    val data = (root["data"] as JsonObject).toMutableMap()
    val headers = mutableListOf<JsonObject>()
    (data["character_book"] as? JsonObject)?.let { headers += it }
    books.forEach { book -> headers += standardBook(book) }
    // All entries are preservation slots, not semantic-deduplication candidates. CardRoundTrip owns
    // collision-safe secondary-header archival, including pre-existing external archive extensions.
    val primary = headers.first()
    val archivedHeaders = if (primary["extensions"] != null && primary["extensions"] !is JsonObject) {
      // CardRoundTrip's archive seam expects an object. Keep the original non-object value in a
      // secondary-header archive rather than silently discarding that external JSON value.
      headers + JsonObject(primary - "entries" - "items")
    } else {
      headers
    }
    val sources = archivedHeaders.map { header ->
      WorldBookSource(
        fingerprint = cardHash(cardCanonical(header).toString()),
        book = header,
        slots = (header["entries"] as? JsonArray).orEmpty().map { WorldSourceSlot(it) },
      )
    }
    val snapshot = CharacterCard(
      id = "portable",
      name = card.name,
      importMetadata = CardImportMetadata(sources = sources),
    )
    val archived = CardRoundTrip.book(snapshot)
    val preserved = JsonArray(headers.flatMap { (it["entries"] as? JsonArray).orEmpty() })
    val combined = archived.toMutableMap()
    combined["entries"] = preserved
    if ("items" in archived) combined["items"] = preserved
    // A disabled primary source must not disable another source's enabled rows after flattening.
    WorldBookFormatFields.syncEnabled(combined, true)
    val embedded = JsonObject(combined)
    data["character_book"] = embedded
    if ("world" in data) data["world"] = embedded
    val result = JsonObject(root + ("data" to JsonObject(data)))
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result)
  }

  private fun standardBook(book: WorldBook): JsonObject {
    val header = WorldBookFormat.standalone(book).toMutableMap()
    val entries = book.entries.mapIndexed { index, entry ->
      val value = WorldBookFormatFields.write(entry, index)
      if (value is JsonObject) {
        // Freeze effective enablement as well as data: legacy disable aliases must not be
        // normalized to enabled=true by other card readers. Unsupported flags remain archived.
        val raw = value.toMutableMap()
        WorldBookFormatFields.syncEnabled(raw, book.enabled && entry.enabled && entry.unsupported.isEmpty())
        JsonObject(raw)
      } else {
        value
      }
    }
    header.remove("items")
    header["entries"] = JsonArray(entries)
    return JsonObject(header)
  }
}

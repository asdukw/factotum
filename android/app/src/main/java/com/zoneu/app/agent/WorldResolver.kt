package com.zoneu.app.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Existing chats use only their manifest, never later role defaults. */
object WorldResolver {
  fun resolve(
    sessionId: String,
    cards: List<CharacterCard>,
    manifest: WorldManifest?,
    books: List<WorldBook>,
  ): List<WorldResolvedEntry> = buildList {
    worldCheck(manifest == null || manifest.sessionId == sessionId, "WORLD_MANIFEST_SESSION_MISMATCH")
    cards.distinctBy { it.id }.forEach { card ->
      card.world.forEachIndexed { index, rule ->
        val revision = digest(Json.encodeToString(rule))
        val entryId = rule.sourceToken.ifBlank { "legacy-$index" }
        add(
          WorldResolvedEntry(
            key = identity(sessionId, "card:${card.id}", entryId, revision, "live"),
            bookId = "card:${card.id}",
            revision = revision,
            entryId = entryId,
            epoch = "live",
            sources = listOf("character:${card.id}"),
            enabled = true,
            unsupported = if (WorldBookFormatFields.decorated(rule.content)) listOf("decorator") else emptyList(),
            rule = rule,
          ),
        )
      }
    }
    manifest?.books.orEmpty().groupBy { it.bookId }.forEach { (id, refs) ->
      val binding = refs.first()
      worldCheck(refs.all { it.revision == binding.revision && it.epoch == binding.epoch }, "WORLD_BINDING_CONFLICT")
      val book = books.firstOrNull { it.id == id && it.revision == binding.revision }
        ?: throw WorldBookException("WORLD_PINNED_REVISION_MISSING")
      val enabled = book.enabled && refs.none { it.override == WorldOverride.DISABLE }
      book.entries.forEach { entry ->
        add(
          WorldResolvedEntry(
            key = identity(sessionId, id, entry.id, binding.revision, binding.epoch),
            bookId = id,
            revision = binding.revision,
            entryId = entry.id,
            epoch = binding.epoch,
            sources = refs.flatMap { it.sources }.distinct(),
            enabled = enabled && entry.enabled,
            unsupported = entry.unsupported,
            rule = entry.rule,
          ),
        )
      }
    }
  }

  internal fun identity(session: String, book: String, entry: String, revision: String, epoch: String): String =
    digest(Json.encodeToString(listOf(session, book, entry, revision, epoch)))

  internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

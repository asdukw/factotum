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
        val unsupported = buildList {
          if (WorldBookFormatFields.decorated(rule.content)) add("decorator")
          if (rule.position !in setOf(WorldEntry.POSITION_BEFORE, WorldEntry.POSITION_AFTER, WorldEntry.POSITION_DEPTH)) add("position")
          if (rule.role !in WorldEntry.ROLE_SYSTEM..WorldEntry.ROLE_ASSISTANT) add("role")
        }
        add(
          WorldResolvedEntry(
            key = identity(sessionId, "card:${card.id}", entryId, revision, "live"),
            bookId = "card:${card.id}",
            revision = revision,
            entryId = entryId,
            epoch = "live",
            sources = listOf("character:${card.id}"),
            // 卡内条目也尊重自己的 enabled：禁用后仍可编辑/导出，只是不参与运行。
            enabled = rule.enabled,
            unsupported = unsupported,
            rule = rule,
          ),
        )
      }
    }
    manifest?.books.orEmpty().groupBy { it.bookId }.forEach { (id, refs) ->
      addAll(resolveBook(sessionId, id, refs, books))
    }
  }

  private fun resolveBook(
    sessionId: String,
    id: String,
    refs: List<WorldBookBinding>,
    books: List<WorldBook>,
  ): List<WorldResolvedEntry> {
    val binding = refs.first()
    worldCheck(refs.all { it.revision == binding.revision && it.epoch == binding.epoch }, "WORLD_BINDING_CONFLICT")
    val book =
      books.firstOrNull { it.id == id && it.revision == binding.revision }
        ?: throw WorldBookException("WORLD_PINNED_REVISION_MISSING")
    val enabled = book.enabled && refs.none { it.override == WorldOverride.DISABLE }
    return book.entries.map { entry ->
      val rule =
        entry.rule.copy(
          scanDepth = entry.rule.scanDepth ?: book.scanDepth,
          preventRecursion = entry.rule.preventRecursion || book.recursiveScanning == false,
        )
      WorldResolvedEntry(
        key = identity(sessionId, id, entry.id, binding.revision, binding.epoch),
        bookId = id,
        revision = binding.revision,
        entryId = entry.id,
        epoch = binding.epoch,
        sources = refs.flatMap { it.sources }.distinct(),
        enabled = enabled && entry.enabled,
        unsupported = entry.unsupported,
        rule = rule,
        bookBudget = book.tokenBudget,
      )
    }
  }

  internal fun identity(session: String, book: String, entry: String, revision: String, epoch: String): String =
    digest(Json.encodeToString(listOf(session, book, entry, revision, epoch)))

  internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

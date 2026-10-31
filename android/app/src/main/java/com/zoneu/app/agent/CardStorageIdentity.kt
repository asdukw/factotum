package com.zoneu.app.agent

import java.util.UUID

/** Revision-bound slot references for old tokenless UI drafts; never matched by body or keywords. */
object CardStorageIdentity {
  fun draft(card: CharacterCard): CharacterCard {
    val revision = card.storageRevision ?: throw CardOperationException("CARD_REVISION_REQUIRED")
    return card.copy(
      world = card.world.mapIndexed { index, entry ->
        if (entry.sourceToken.isNotBlank()) entry else entry.copy(sourceToken = legacyRef(card.id, revision, index))
      },
    )
  }

  /** Only approved, changed writes materialize identities; reads/no-ops never do. */
  fun commit(current: CharacterCard, draft: CharacterCard): CharacterCard {
    val revision = current.storageRevision ?: throw CardOperationException("CARD_REVISION_REQUIRED")
    val legacy = current.world.mapIndexedNotNull { index, entry ->
      if (entry.sourceToken.isBlank()) legacyRef(current.id, revision, index) else null
    }.toSet()
    val used = mutableSetOf<String>()
    val world = draft.world.map { entry ->
      val token = entry.sourceToken
      cardStorageCheck(token.isBlank() || used.add(token), "CARD_ENTRY_CONFLICT")
      cardStorageCheck(!token.startsWith("ui-legacy-") || token in legacy, "CARD_ENTRY_CONFLICT")
      if (token.isBlank() || token in legacy) entry.copy(sourceToken = UUID.randomUUID().toString()) else entry
    }
    return draft.copy(world = world)
  }

  /** Validate every reserved reference, and use its original slot even when entries share a body. */
  fun unchanged(current: CharacterCard, draft: CharacterCard): Boolean {
    val revision = current.storageRevision ?: return current == draft
    val slots = current.world.mapIndexedNotNull { index, entry ->
      if (entry.sourceToken.isBlank()) legacyRef(current.id, revision, index) to index else null
    }.toMap()
    val used = mutableSetOf<String>()
    var reordered = false
    val world = draft.world.mapIndexed { index, entry ->
      val token = entry.sourceToken
      cardStorageCheck(token.isBlank() || used.add(token), "CARD_ENTRY_CONFLICT")
      if (token.startsWith("ui-legacy-")) {
        val originalSlot = slots[token] ?: throw CardOperationException("CARD_ENTRY_CONFLICT")
        if (originalSlot != index) reordered = true
        entry.copy(sourceToken = "")
      } else {
        entry
      }
    }
    return !reordered && current == draft.copy(world = world)
  }

  private fun legacyRef(id: String, revision: String, index: Int) = "ui-legacy-" + cardHash("$id:$revision:$index")
}

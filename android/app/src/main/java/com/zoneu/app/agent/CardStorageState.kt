package com.zoneu.app.agent

import kotlinx.serialization.Serializable

/** Storage-only state. Never use this object to build exports or model-facing projections. */
@Serializable
data class CardStorageState(
  val revision: String,
  val pending: CardRequestReceipt? = null,
  /** Stable for this lifetime of the card ID, including later manual edits. */
  val instanceId: String = "",
)

data class CardSnapshot(val card: CharacterCard, val revision: String, val editable: Boolean)

data class CardLibraryListing(val cards: List<CharacterCard>, val unreadable: Int)

internal data class CardSnapshotListing(val snapshots: List<CardSnapshot>, val unreadable: Int)

/** Safe field names and counts only; no character/world text belongs in the journal. */
@Serializable
data class CardChangeCounts(
  val changedFields: List<String> = emptyList(),
  val added: Int = 0,
  val updated: Int = 0,
  val removed: Int = 0,
)

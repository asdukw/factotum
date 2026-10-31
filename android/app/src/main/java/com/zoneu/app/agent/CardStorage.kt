package com.zoneu.app.agent

import java.util.UUID

/** All mutations, intent writes and evidence acknowledgements execute under the same lock. */
internal object CardStorageTransactions {
  val lock = Any()
}

internal class CardStorage(
  private val read: (String) -> CardSnapshot?,
  private val all: () -> List<CardSnapshot>,
  private val write: (CharacterCard) -> Unit,
  private val remove: (String) -> Unit,
  val readReceipt: (String) -> CardRequestReceipt?,
  val writeReceipt: (CardRequestReceipt) -> Unit,
  private val browse: (() -> CardSnapshotListing)? = null,
) {
  fun <T> transaction(block: CardStorage.() -> T): T = synchronized(CardStorageTransactions.lock) { block() }

  fun snapshots(): List<CardSnapshot> = transaction { all() }

  fun listing(): CardSnapshotListing = transaction { browse?.invoke() ?: CardSnapshotListing(all(), 0) }

  fun snapshot(id: String): CardSnapshot? = transaction {
    requireId(id)
    read(id)
  }

  fun create(card: CharacterCard): CardSnapshot = transaction { createWithEvidence(card, null) }

  fun update(id: String, expected: String, transform: (CharacterCard) -> CharacterCard): CardSnapshot = transaction {
    val before = writable(id, expected)
    val next = sanitize(before.card, transform(before.card))
    if (CardStorageIdentity.unchanged(before.card, next)) before else persist(CardStorageIdentity.commit(before.card, next), null)
  }

  fun delete(id: String, expected: String): CardSnapshot = transaction {
    val before = writable(id, expected)
    remove(id)
    if (read(id) != null) throw CardOperationException("CARD_DELETE_FAILED")
    before
  }

  /** Must precede every later write/delete, even a write by a different request or the UI. */
  fun acknowledge(snapshot: CardSnapshot) {
    val evidence = snapshot.card.storageState()?.pending ?: return
    val prior = readReceipt(evidence.request)
    if (prior != null && prior.copy(committed = true) != evidence.copy(committed = true)) {
      throw CardOperationException("CARD_REQUEST_CONFLICT")
    }
    if (prior?.committed != true) writeReceipt(evidence.copy(committed = true))
    if (readReceipt(evidence.request) != evidence.copy(committed = true)) {
      throw CardOperationException("CARD_RECEIPT_UNCONFIRMED")
    }
  }

  fun writable(id: String, expected: String): CardSnapshot {
    requireId(id)
    val current = read(id) ?: throw CardOperationException("CARD_NOT_FOUND")
    cardStorageCheck(current.editable, "CARD_READ_ONLY")
    acknowledge(current)
    cardStorageCheck(expected.isNotBlank() && current.revision == expected, "CARD_REVISION_CONFLICT")
    return current
  }

  fun createWithEvidence(card: CharacterCard, evidence: CardRequestReceipt?): CardSnapshot {
    requireId(card.id)
    val prior = read(card.id)
    if (prior != null) {
      acknowledge(prior)
      throw CardOperationException("CARD_ALREADY_EXISTS")
    }
    val metadata = card.importMetadata?.let { source ->
      if (source.contentHash.isBlank() && source.raw != null) {
        source.copy(contentHash = cardHash(cardCanonical(source.raw).toString()))
      } else {
        source
      }
    }
    return persist(card.copy(imported = true, importMetadata = metadata).withStorage(null), evidence)
  }

  fun updateWithEvidence(before: CardSnapshot, candidate: CharacterCard, evidence: CardRequestReceipt): CardSnapshot =
    persist(CardStorageIdentity.commit(before.card, sanitize(before.card, candidate)), evidence)

  private fun persist(card: CharacterCard, evidence: CardRequestReceipt?): CardSnapshot {
    val revision = evidence?.appliedRevision ?: UUID.randomUUID().toString()
    val instance = evidence?.targetInstance?.takeIf { it.isNotBlank() }
      ?: card.storageState()?.instanceId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    val stored = card.withStorage(CardStorageState(revision, evidence, instance))
    write(stored)
    val saved = read(card.id) ?: throw CardOperationException("CARD_SAVE_UNCONFIRMED")
    if (!saved.editable || saved.revision != revision || saved.card != stored) throw CardOperationException("CARD_SAVE_UNCONFIRMED")
    return saved
  }

  /** A transform cannot replace storage evidence, the original raw document or creation identity. */
  fun sanitize(current: CharacterCard, candidate: CharacterCard): CharacterCard {
    val metadata = current.importMetadata?.let { old ->
      (candidate.importMetadata ?: old).copy(
        raw = old.raw,
        baseline = old.baseline,
        contentHash = old.contentHash,
        originHost = old.originHost,
        originUrl = old.originUrl,
      )
    } ?: candidate.importMetadata?.copy(contentHash = "", originHost = "", originUrl = "")
    return candidate.copy(id = current.id, imported = true, importMetadata = metadata).withStorage(current.storageState())
  }

  companion object {
    fun requireId(id: String) {
      if (!id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))) throw CardOperationException("CARD_INVALID_ID")
    }
  }
}

internal fun cardStorageCheck(condition: Boolean, code: String) {
  if (!condition) throw CardOperationException(code)
}

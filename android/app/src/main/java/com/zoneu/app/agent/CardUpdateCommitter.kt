package com.zoneu.app.agent

import android.content.Context
import java.util.UUID

data class CardUpdateProposal(val card: CharacterCard, val changes: CardChangeCounts = CardChangeCounts())

data class CardUpdateResult(
  val snapshot: CardSnapshot,
  /** The revision originally applied, which may differ from the current snapshot on replay. */
  val appliedRevision: String,
  val replay: Boolean,
  val unchanged: Boolean,
  val changes: CardChangeCounts,
)

/** No approval/network waits belong inside this coordinator. The transform must be pure. */
class CardUpdateCommitter internal constructor(private val storage: CardStorage) {
  constructor(context: Context) : this(CharacterStore.storage(context))

  fun submit(
    request: String,
    parameters: String,
    targetId: String,
    expectedRevision: String,
    cancelCheck: () -> Unit = {},
    transform: (CharacterCard) -> CardUpdateProposal,
  ): CardUpdateResult = storage.transaction {
    require(request.isNotBlank() && request.length <= 256 && parameters.isNotBlank())
    val prior = readReceipt(request)
    if (prior != null) replay(prior, parameters, targetId, expectedRevision)?.let { return@transaction it }
    // An applied operation is replayed above even when its old control has since been cancelled.
    cancelCheck()
    val before = writable(targetId, expectedRevision)
    val mutation = transform(before.card)
    val candidate = sanitize(before.card, mutation.card)
    validateCounts(mutation.changes)
    val unchanged = CardStorageIdentity.unchanged(before.card, candidate)
    val receipt = CardRequestReceipt(
      request = request,
      parameters = parameters,
      targetId = targetId,
      contentHash = "",
      committed = unchanged,
      protocol = 2,
      operation = "update",
      expectedRevision = expectedRevision,
      appliedRevision = if (unchanged) before.revision else prior?.appliedRevision ?: UUID.randomUUID().toString(),
      unchanged = unchanged,
      changes = if (unchanged) CardChangeCounts() else mutation.changes,
      targetInstance = before.card.storageState()?.instanceId.orEmpty(),
    )
    cancelCheck()
    writeReceipt(receipt)
    if (unchanged) return@transaction result(before, receipt, false)
    cancelCheck()
    val saved = updateWithEvidence(before, candidate, receipt)
    acknowledge(saved)
    result(saved, receipt, false)
  }

  private fun replay(
    prior: CardRequestReceipt,
    parameters: String,
    targetId: String,
    expectedRevision: String,
  ): CardUpdateResult? {
    val identity = listOf(prior.operation, prior.parameters, prior.targetId, prior.expectedRevision)
    cardStorageCheck(identity == listOf("update", parameters, targetId, expectedRevision), "CARD_REQUEST_CONFLICT")
    val current = storage.snapshot(targetId) ?: throw CardOperationException("CARD_PREVIOUSLY_DELETED")
    cardStorageCheck(
      prior.targetInstance.isEmpty() || current.card.storageState()?.instanceId == prior.targetInstance,
      "CARD_PREVIOUSLY_DELETED",
    )
    if (prior.committed) return result(current, prior, true)
    if (current.card.storageState()?.pending == prior) {
      storage.acknowledge(current)
      return result(current, prior, true)
    }
    cardStorageCheck(current.revision == expectedRevision, "CARD_RECOVERY_UNCERTAIN")
    return null
  }

  private fun result(snapshot: CardSnapshot, receipt: CardRequestReceipt, replayed: Boolean) = CardUpdateResult(
    snapshot,
    receipt.appliedRevision,
    replayed,
    receipt.unchanged,
    receipt.changes,
  )

  private fun validateCounts(changes: CardChangeCounts) {
    val allowed = setOf(
      "name",
      "nickname",
      "tagline",
      "tags",
      "description",
      "personality",
      "scenario",
      "first_mes",
      "alternate_greetings",
      "group_only_greetings",
    )
    require(changes.changedFields.all { it in allowed } && changes.changedFields.distinct().size == changes.changedFields.size)
    require(changes.added >= 0 && changes.updated >= 0 && changes.removed >= 0)
  }
}

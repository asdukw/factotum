package com.zoneu.app.agent

import java.util.UUID

/** Creation/import dedupe under the same transaction boundary as UI updates and deletion. */
internal class CardCommitter(private val storage: CardStorage) {
  fun existing(request: String, parameters: String): CharacterCard? = storage.transaction {
    val receipt = readReceipt(request) ?: return@transaction null
    verify(receipt.parameters == parameters && receipt.operation == "create", "CARD_REQUEST_CONFLICT")
    val current = snapshot(receipt.targetId)
    if (current == null) {
      verify(!receipt.committed, "CARD_PREVIOUSLY_DELETED")
      verify(receipt.protocol >= 2, "CARD_RECOVERY_UNCERTAIN")
      return@transaction null
    }
    verify(
      receipt.targetInstance.isEmpty() || current.card.storageState()?.instanceId == receipt.targetInstance,
      "CARD_PREVIOUSLY_DELETED",
    )
    verify(contentIdentity(current.card) == receipt.contentHash, "CARD_REQUEST_CONFLICT")
    if (!receipt.committed) {
      if (receipt.protocol >= 2) {
        verify(current.card.storageState()?.pending == receipt, "CARD_RECOVERY_UNCERTAIN")
        acknowledge(current)
      } else {
        writeReceipt(receipt.copy(committed = true))
      }
    }
    current.card
  }

  fun submit(request: String, parameters: String, card: CharacterCard, control: CardDownloadControl, png: ByteArray? = null): Pair<CharacterCard, Boolean> =
    storage.transaction {
      existing(request, parameters)?.let { return@transaction it to true }
      control.check()
      val hash = cardHash(cardCanonical(card.importMetadata!!.raw!!).toString())
      val prior = readReceipt(request)
      if (prior != null) verify(prior.parameters == parameters && prior.contentHash == hash, "CARD_REQUEST_CONFLICT")
      val duplicate = snapshots().firstOrNull { it.editable && contentIdentity(it.card) == hash }
      if (duplicate != null) {
        acknowledge(duplicate)
        control.check()
        writeReceipt(
          CardRequestReceipt(
            request,
            parameters,
            duplicate.card.id,
            hash,
            true,
            2,
            appliedRevision = duplicate.revision,
            targetInstance = duplicate.card.storageState()?.instanceId.orEmpty(),
          ),
        )
        return@transaction duplicate.card to true
      }
      val targetId = prior?.targetId ?: "imported-${UUID.randomUUID()}"
      val receipt = CardRequestReceipt(
        request,
        parameters,
        targetId,
        hash,
        protocol = 2,
        appliedRevision = prior?.appliedRevision?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        targetInstance = prior?.targetInstance?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
      )
      writeReceipt(receipt)
      control.check()
      val candidate = card.copy(id = targetId, importMetadata = card.importMetadata.copy(contentHash = hash))
      val saved = createWithEvidence(candidate, receipt, png)
      acknowledge(saved)
      saved.card to false
    }

  private fun contentIdentity(card: CharacterCard): String? = card.importMetadata?.let { metadata ->
    metadata.contentHash.takeIf { it.isNotBlank() } ?: metadata.raw?.let { cardHash(cardCanonical(it).toString()) }
  }

  private fun verify(condition: Boolean, code: String) {
    if (!condition) throw CardOperationException(code)
  }
}

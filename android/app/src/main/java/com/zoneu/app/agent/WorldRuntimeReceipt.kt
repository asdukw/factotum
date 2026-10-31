package com.zoneu.app.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Validates only identifiers/sequence numbers. Content is never a receipt or a log field. */
internal object WorldRuntimeReceipt {
  data class Verified(val advance: Boolean, val evidence: String)

  fun verify(binding: WorldRuntimeBindings.Binding, response: JsonObject, cancelled: Boolean): Verified? {
    val status = response.string("status")
    val advance = (response["advance"] as? JsonPrimitive)?.booleanOrNull
    val durable = (response["durable"] as? JsonPrimitive)?.booleanOrNull
    if (status !in listOf("settled", "cancelled", "failed") || advance == null || durable == null) return null
    worldCheck(!advance || (status == "settled" && durable), "WORLD_RECEIPT_INVALID")
    worldCheck(status != "settled" || advance, "WORLD_RECEIPT_INVALID")
    return if (durable) {
      val evidence = response["evidence"] as? JsonObject ?: throw WorldBookException("WORLD_RECEIPT_INVALID")
      verifyEvidence(binding, evidence, advance, cancelled)
    } else {
      verifyUnqueued(binding, response, status, advance, cancelled)
    }
  }

  private fun verifyUnqueued(
    binding: WorldRuntimeBindings.Binding,
    response: JsonObject,
    status: String?,
    advance: Boolean,
    cancelled: Boolean,
  ): Verified {
    val code = response.string("code")
    // WORLD_AGENT_UNAVAILABLE: the lifecycle can no longer observe this request
    // (invalidated record). The user's explicit cancel still releases it, but the
    // outcome is not durable proof, so timing must not advance.
    val released = status == "cancelled" && code in setOf("WORLD_PROMPT_NOT_QUEUED", "WORLD_AGENT_UNAVAILABLE")
    worldCheck(!advance && cancelled && released, "WORLD_RECEIPT_INVALID")
    return Verified(false, "cancelled:$code:${binding.dshSessionId}:${binding.requestId}:${binding.trigger}")
  }

  private fun verifyEvidence(
    binding: WorldRuntimeBindings.Binding,
    evidence: JsonObject,
    advance: Boolean,
    cancelled: Boolean,
  ): Verified {
    val identity = listOf(evidence.string("sessionId"), evidence.string("requestId"), evidence.string("trigger"))
    worldCheck(identity == listOf(binding.dshSessionId, binding.requestId, binding.trigger), "WORLD_RECEIPT_IDENTITY_MISMATCH")
    val turn = (evidence["turn"] as? JsonPrimitive)?.intOrNull
    val endSeq = (evidence["endSeq"] as? JsonPrimitive)?.longOrNull
    val assistantSeq = (evidence["assistantSeq"] as? JsonPrimitive)?.longOrNull
    worldCheck(turn != null && turn > 0 && (binding.dshTurn == null || binding.dshTurn == turn), "WORLD_RECEIPT_TURN_MISMATCH")
    worldCheck(endSeq != null && endSeq > 0, "WORLD_RECEIPT_INVALID")
    val reason = evidence.string("reason")
    worldCheck(reason in listOf("completed", "max-tokens", "blocked", "aborted", "interrupted", "error", "forked"), "WORLD_RECEIPT_INVALID")
    if (advance) verifyAnswer(reason, assistantSeq, endSeq!!)
    val proof = (identity + listOf(turn, endSeq, reason, assistantSeq)).joinToString("|")
    return Verified(advance && !cancelled && !binding.cancelled, proof)
  }

  private fun verifyAnswer(reason: String?, assistantSeq: Long?, endSeq: Long) {
    worldCheck(reason == "completed" && assistantSeq != null, "WORLD_RECEIPT_INVALID")
    worldCheck(assistantSeq!! > 0 && assistantSeq < endSeq, "WORLD_RECEIPT_INVALID")
  }

  private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}

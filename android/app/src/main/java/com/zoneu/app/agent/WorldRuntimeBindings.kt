package com.zoneu.app.agent

/** Exact identities originate in Kotlin and are acknowledged before SDK followup. */
internal class WorldRuntimeBindings {
  data class Binding(
    val requestId: String,
    val sessionId: String,
    val dshSessionId: String,
    val turn: Int,
    val trigger: String? = null,
    val cancelled: Boolean = false,
    val dshTurn: Int? = null,
  )

  private val requests = linkedMapOf<String, Binding>()

  @Synchronized
  fun register(binding: Binding) {
    worldCheck(binding.requestId !in requests, "WORLD_REQUEST_EXISTS")
    worldCheck(requests.values.none { it.dshSessionId == binding.dshSessionId }, "WORLD_REQUEST_BUSY")
    requests[binding.requestId] = binding
  }

  @Synchronized
  fun bind(requestId: String, dshSessionId: String, trigger: String): Binding {
    val binding = requests[requestId] ?: throw WorldBookException("WORLD_UNKNOWN_REQUEST")
    worldCheck(binding.dshSessionId == dshSessionId && trigger.isNotBlank(), "WORLD_IDENTITY_MISMATCH")
    worldCheck(binding.trigger == null || binding.trigger == trigger, "WORLD_TRIGGER_MISMATCH")
    val bound = binding.copy(trigger = trigger).also { requests[requestId] = it }
    worldCheck(!bound.cancelled, "WORLD_REQUEST_CANCELLED")
    return bound
  }

  @Synchronized
  fun resolve(requestId: String, dshSessionId: String, trigger: String, turn: Int?): Binding {
    val binding = requests[requestId] ?: throw WorldBookException("WORLD_UNKNOWN_REQUEST")
    worldCheck(!binding.cancelled && binding.dshSessionId == dshSessionId && binding.trigger == trigger, "WORLD_IDENTITY_MISMATCH")
    worldCheck(turn == null || (turn > 0 && (binding.dshTurn == null || turn == binding.dshTurn)), "WORLD_TURN_MISMATCH")
    return if (turn == null) binding else binding.copy(dshTurn = turn).also { requests[requestId] = it }
  }

  @Synchronized
  fun get(requestId: String): Binding? = requests[requestId]

  @Synchronized
  fun remove(requestId: String) {
    requests.remove(requestId)
  }

  @Synchronized
  fun cancel(requestId: String) {
    requests[requestId]?.let { requests[requestId] = it.copy(cancelled = true) }
  }

  @Synchronized
  fun clear() {
    requests.clear()
  }
}

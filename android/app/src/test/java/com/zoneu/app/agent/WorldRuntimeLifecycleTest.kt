package com.zoneu.app.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorldRuntimeLifecycleTest {
  private fun registry(): WorldRuntimeBindings = WorldRuntimeBindings().apply {
    register(WorldRuntimeBindings.Binding("request", "kotlin-session", "exact-dsh-session-tag", 2))
  }

  private inline fun rejected(block: () -> Unit) {
    try {
      block()
      error("expected rejection")
    } catch (_: WorldBookException) {
    }
  }

  @Test fun exactMappingRejectsSuffixGuessCrossSessionAndReplacedTrigger() {
    val bindings = registry()
    rejected { bindings.bind("request", "exact-dsh-session", "trigger") }
    bindings.bind("request", "exact-dsh-session-tag", "trigger")
    rejected { bindings.bind("request", "other-session-tag", "trigger") }
    rejected { bindings.bind("request", "exact-dsh-session-tag", "other-trigger") }
    rejected { bindings.resolve("request", "exact-dsh-session-tag", "other-trigger", 12) }
  }

  @Test fun learnsRealDshTurnIndependentlyOfHostAccountingTurn() {
    val bindings = registry()
    bindings.bind("request", "exact-dsh-session-tag", "trigger")
    val resolved = bindings.resolve("request", "exact-dsh-session-tag", "trigger", 12)
    assertEquals(2, resolved.turn)
    assertEquals(12, resolved.dshTurn)
    rejected { bindings.resolve("request", "exact-dsh-session-tag", "trigger", 13) }
  }

  @Test fun reloadAndCancelFailClosed() {
    val bindings = registry()
    bindings.bind("request", "exact-dsh-session-tag", "trigger")
    bindings.cancel("request")
    rejected { bindings.resolve("request", "exact-dsh-session-tag", "trigger", 12) }
    bindings.clear()
    rejected { bindings.bind("request", "exact-dsh-session-tag", "trigger") }
    assertNull(bindings.get("request"))
  }

  private fun receipt(
    status: String = "settled",
    advance: Boolean = true,
    durable: Boolean = true,
    trigger: String = "trigger",
    assistant: Long? = 10,
  ): JsonObject = buildJsonObject {
    put("status", status)
    put("advance", advance)
    put("durable", durable)
    put(
      "evidence",
      buildJsonObject {
        put("sessionId", "exact-dsh-session-tag")
        put("requestId", "request")
        put("trigger", trigger)
        put("turn", 12)
        put("endSeq", 15)
        put("reason", if (status == "settled") "completed" else "aborted")
        if (assistant != null) put("assistantSeq", assistant)
      },
    )
  }

  @Test fun durableAloneDoesNotAuthorizeTimingAndEvidenceIsExact() {
    val binding = registry().bind("request", "exact-dsh-session-tag", "trigger").copy(dshTurn = 12)
    assertNull(
      WorldRuntimeReceipt.verify(
        binding,
        buildJsonObject {
          put("status", "pending")
          put("durable", true)
          put("advance", false)
        },
        false,
      ),
    )
    assertNotNull(WorldRuntimeReceipt.verify(binding, receipt(), false))
    rejected { WorldRuntimeReceipt.verify(binding, receipt(trigger = "another"), false) }
    rejected { WorldRuntimeReceipt.verify(binding, receipt(assistant = 15), false) }
    rejected { WorldRuntimeReceipt.verify(binding, receipt(assistant = null), false) }
    assertFalse(WorldRuntimeReceipt.verify(binding.copy(cancelled = true), receipt(), true)!!.advance)
    assertFalse(WorldRuntimeReceipt.verify(binding, receipt("cancelled", false), true)!!.advance)
  }

  @Test fun localPersistenceFailureNeverReachesAdvanceCallback() = runTest {
    var advanced = false
    try {
      finishWorldTurn(persist = { throw IOException("synthetic storage failure") }) { advanced = true }
    } catch (_: IOException) {
    }
    assertFalse(advanced)
    val order = mutableListOf<String>()
    finishWorldTurn(persist = { order += "durable-answer" }) { order += "settled" }
    assertEquals(listOf("durable-answer", "settled"), order)
  }

  @Test fun flowRecollectionKeepsLogicalRequestAndPreparedMacroOutput() = runTest {
    val requests = mutableListOf<String>()
    val frozen = WorldRequestPlan(
      requestId = "request",
      sessionId = "session",
      bindingRevision = "empty",
      generation = "direct",
      turn = 1,
      seed = 42,
      injections = emptyList(),
      decisions = emptyList(),
      activated = emptyList(),
      messages = listOf(WorldPromptMessage("system", "frozen-random-output")),
    )
    val direct = object : WorldDirect {
      override suspend fun prepare(request: WorldDirect.Request): WorldRequestPlan {
        requests += request.requestId
        return frozen
      }

      override suspend fun settle(requestId: String, success: Boolean, evidence: String) {
        error("provider must not settle")
      }
    }
    val sent = mutableListOf<TavernRequest>()
    val turns = TavernTurns(
      cards = { error("must use session snapshot") },
      channel = {
        TavernChannel { request ->
          sent += request
          flowOf(AgentStreamEvent.Completed)
        }
      },
      world = direct,
    )
    val flow = turns.stream(
      "session",
      "irrelevant-selected-card",
      listOf(
        ChatMessage(
          "user",
          ChatRole.User,
          "hello",
        ),
      ),
      requestId = "request",
    )!!
    flow.toList()
    flow.toList()
    assertEquals(listOf("request", "request"), requests)
    assertEquals(sent[0], sent[1])
    assertEquals("frozen-random-output", sent.singleOrNull()?.messages?.firstOrNull()?.content ?: sent.first().messages.first().content)
    assertTrue(sent.size == 2)
  }

  @Test fun cancelledReservationsCannotBeReplacedBeforeConvergence() {
    val bindings = registry()
    bindings.cancel("request")
    rejected { bindings.register(WorldRuntimeBindings.Binding("new", "kotlin-session", "exact-dsh-session-tag", 2)) }
    bindings.remove("request")
    bindings.register(WorldRuntimeBindings.Binding("new", "kotlin-session", "exact-dsh-session-tag", 2))
    assertNotNull(bindings.get("new"))
  }

  @Test fun terminalFailureReasonsNeverAdvanceAndUnqueuedCancelNeedsExactCode() {
    val binding = registry().bind("request", "exact-dsh-session-tag", "trigger").copy(dshTurn = 12)
    listOf("max-tokens", "blocked", "interrupted", "error", "forked").forEach { reason ->
      val original = receipt("failed", false)
      val evidence = original["evidence"] as JsonObject
      val result =
        JsonObject(original + ("evidence" to JsonObject(evidence + ("reason" to kotlinx.serialization.json.JsonPrimitive(reason)))))
      assertFalse(WorldRuntimeReceipt.verify(binding, result, false)!!.advance)
    }
    rejected { WorldRuntimeReceipt.verify(binding, receipt("cancelled", false, false), true) }
    val result = buildJsonObject {
      put("status", "cancelled")
      put("advance", false)
      put("durable", false)
      put("code", "WORLD_PROMPT_NOT_QUEUED")
    }
    assertFalse(WorldRuntimeReceipt.verify(binding, result, true)!!.advance)
    // An invalidated lifecycle record is releasable by explicit cancel, but only as
    // an unadvanced cancellation; without the cancel flag it is still rejected.
    val unavailable = buildJsonObject {
      put("status", "cancelled")
      put("advance", false)
      put("durable", false)
      put("code", "WORLD_AGENT_UNAVAILABLE")
    }
    assertFalse(WorldRuntimeReceipt.verify(binding, unavailable, true)!!.advance)
    rejected { WorldRuntimeReceipt.verify(binding, unavailable, false) }
  }
}

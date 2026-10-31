package com.zoneu.app.agent

import android.content.Context
import com.zoneu.app.data.session.ChatSession
import com.zoneu.app.data.session.zoneuSessionStore
import com.zoneu.app.device.DeviceActionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.random.Random

/** Durable preparation and the private exact-identity embedded SDK bridge. */
object WorldRuntime {
  private val prepareMutex = Mutex()
  private val bindings = WorldRuntimeBindings()

  private data class Snapshot(
    val session: ChatSession,
    val cards: List<CharacterCard>,
    val manifest: WorldManifest?,
    val entries: List<WorldResolvedEntry>,
    val state: WorldRuntimeState,
    val variables: Map<String, String>,
    val memory: String,
    val preset: TavernPreset,
  )

  fun direct(context: Context): WorldDirect = object : WorldDirect {
    override suspend fun prepare(request: WorldDirect.Request): WorldRequestPlan = prepareMutex.withLock {
      val repo = context.worldBookRepository()
      if (request.auxiliary) {
        val snapshot = snapshot(context, request.sessionId, includeWorld = false)
        val persona = cleanPersona(snapshot.cards)
        val seed = WorldResolver.digest(request.requestId).take(8).toLong(16).toInt()
        val rendered = renderDirect(
          snapshot,
          persona,
          request.history,
          request.tail to request.recap,
          seed,
          emptyList(),
        )
        return@withLock WorldRequestPlan(
          request.requestId,
          request.sessionId,
          snapshot.manifest?.revision ?: "empty",
          "auxiliary",
          snapshot.state.turn,
          seed,
          emptyList(),
          emptyList(),
          emptyList(),
          messages = rendered.first.map { WorldPromptMessage(it.role, it.content) },
        )
      }
      repo.plan(request.requestId)?.let {
        worldCheck(it.sessionId == request.sessionId && it.generation == "direct" && it.status == "prepared", "WORLD_REQUEST_MISMATCH")
        return@withLock it
      }
      val snapshot = snapshot(context, request.sessionId)
      repo.savePlan(makePlan(snapshot, request.requestId, "direct", request.history, request.tail, request.recap))
    }

    override suspend fun settle(requestId: String, success: Boolean, evidence: String) {
      val repo = context.worldBookRepository()
      val plan = repo.plan(requestId) ?: return
      val settled = repo.settlePlan(requestId, success, WorldResolver.digest(evidence))
      if (success && plan.status == "prepared" && settled.status == "settled") PrefsVarsStore(context).save(plan.sessionId, plan.variables)
    }
  }

  private suspend fun snapshot(context: Context, sessionId: String, readOnly: Boolean = false, includeWorld: Boolean = true): Snapshot {
    val session = context.zoneuSessionStore().session(sessionId) ?: throw WorldBookException("WORLD_SESSION_NOT_FOUND")
    worldCheck(session.isRoleplay, "WORLD_NOT_ROLEPLAY")
    val repo = context.worldBookRepository()
    val manifest = if (includeWorld) repo.manifest(sessionId) else null
    val books = if (includeWorld) {
      manifest?.books.orEmpty().distinctBy { it.bookId to it.revision }.map { binding ->
        repo.read(binding.bookId, binding.revision)
      }
    } else {
      emptyList()
    }
    val (cards, memory, preset) = CharacterStore.transaction {
      val library = CharacterStore.cards(context)
      val cards = session.cast.distinct().take(GroupCast.MAX).map { id ->
        library.firstOrNull { it.id == id } ?: throw WorldBookException("WORLD_CHARACTER_MISSING")
      }
      worldCheck(cards.isNotEmpty(), "WORLD_CHARACTER_MISSING")
      Triple(
        cards,
        if (readOnly) "" else CharacterMemory.notes(context, cards.first().id),
        if (readOnly) TavernPresetStore.peekForCard(context, cards.first().id) else TavernPresetStore.forCard(context, cards.first().id),
      )
    }
    return Snapshot(
      session,
      cards,
      manifest,
      if (includeWorld) WorldResolver.resolve(sessionId, cards, manifest, books) else emptyList(),
      if (includeWorld) repo.runtimeState(sessionId) else WorldRuntimeState(),
      if (readOnly) emptyMap() else PrefsVarsStore(context).load(sessionId),
      memory,
      preset,
    )
  }

  private fun cleanPersona(cards: List<CharacterCard>): CharacterCard {
    val clean = cards.map { it.copy(world = emptyList()) }
    return if (clean.size > 1) GroupCast.merge(clean) else clean.first()
  }

  private fun makePlan(
    snapshot: Snapshot,
    requestId: String,
    generation: String,
    history: List<TavernMessage>,
    tail: String? = null,
    recap: String? = null,
    dshAnchor: String = "",
  ): WorldRequestPlan {
    val trimmed = ContextBudget.trim(history.filter { it.content.isNotBlank() }).turns
    val seed = WorldResolver.digest(requestId).take(8).toLong(16).toInt()
    val now = System.currentTimeMillis()
    var values = snapshot.variables
    val persona = cleanPersona(snapshot.cards)
    val selection = WorldSelection.select(
      WorldSelection.Input(
        snapshot.entries,
        TavernPrompt.keywordSource(trimmed),
        snapshot.state,
        seed,
        WorldScan.Limits(depth = snapshot.preset.recursionDepth),
      ),
    ) { entry, budget ->
      val table = TavernVars(values)
      val card = snapshot.cards.firstOrNull { "card:${it.id}" == entry.bookId } ?: persona
      val content = CharacterStore.renderText(
        TavernMacros.render(
          entry.rule.content,
          table,
          now,
          Random(seed.toLong() xor entry.key.hashCode().toLong()),
        ),
        card,
      )
      if (content.isNotBlank() && content.length <= budget) values = table.snapshot()
      content
    }
    val rendered = if (generation == "direct") {
      renderDirect(
        snapshot,
        persona,
        trimmed,
        tail to recap,
        seed,
        selection.injections,
        values,
      )
    } else {
      null
    }
    val anchor = rendered?.first?.firstOrNull { it.role == TavernMessage.SYSTEM }?.content ?: dshAnchor
    val messages = rendered?.first.orEmpty()
    values = rendered?.second ?: values
    return WorldRequestPlan(
      requestId,
      snapshot.session.id,
      snapshot.manifest?.revision ?: "empty",
      generation,
      snapshot.state.turn + 1,
      seed,
      selection.injections,
      selection.decisions,
      selection.activated,
      characterAnchor = anchor,
      variables = values,
      messages = messages.map { WorldPromptMessage(it.role, it.content) },
    )
  }

  private fun renderDirect(
    snapshot: Snapshot,
    persona: CharacterCard,
    history: List<TavernMessage>,
    context: Pair<String?, String?>,
    seed: Int,
    injections: List<WorldInjection>,
    values: Map<String, String> = snapshot.variables,
  ): Pair<List<TavernMessage>, Map<String, String>> {
    // TavernPrompt.planned is the sole renderer for card, preset, memory, and injection text.
    // Rendering here first would rerun random/setvar macros and make retries non-identical.
    val (tail, recap) = context
    val table = TavernVars(values)
    val requestTail = listOfNotNull(
      if (snapshot.cards.size > 1) GroupCast.rule(snapshot.cards) else null,
      tail,
    ).joinToString("\n\n").ifBlank { null }
    val base = TavernPrompt.build(
      persona,
      listOfNotNull(snapshot.memory, recap).joinToString("\n\n"),
      history,
      snapshot.preset,
      random = Random(seed.toLong()),
      state = TavernPrompt.TurnState(vars = table, tail = requestTail, injections = injections),
    )
    return base to table.snapshot()
  }

  suspend fun explain(context: Context, sessionId: String, text: String = ""): JsonObject {
    val snapshot = snapshot(context, sessionId, readOnly = true)
    val result = WorldSelection.select(
      WorldSelection.Input(
        snapshot.entries,
        text,
        snapshot.state,
        limits = WorldScan.Limits(depth = snapshot.preset.recursionDepth),
        explain = true,
      ),
    )
    return buildJsonObject {
      put("kind", "zoneu.world.explain.v1")
      put("session_id", sessionId)
      put("binding_revision", snapshot.manifest?.revision ?: "empty")
      put("turn", snapshot.state.turn)
      put(
        "decisions",
        JsonArray(
          result.decisions.map { decision ->
            buildJsonObject {
              put("entry_id", decision.entryId)
              put("book_id", decision.bookId)
              put("sources", JsonArray(decision.sources.map(::JsonPrimitive)))
              put("reason", decision.reason)
              put("selected", decision.selected)
              put("chars", decision.chars)
            }
          },
        ),
      )
    }
  }

  /** Apply only the saved chat cast, then reload before acquiring the task lease. */
  suspend fun activateCast(context: Context, sessionId: String): Boolean {
    val session = context.zoneuSessionStore().session(sessionId)
    val cast = if (session?.isRoleplay == true) {
      CharacterStore.transaction {
        val library = CharacterStore.cards(context)
        session.cast.distinct().take(GroupCast.MAX).map { id ->
          library.firstOrNull { it.id == id } ?: throw WorldBookException("WORLD_CHARACTER_MISSING")
        }.also { worldCheck(it.isNotEmpty(), "WORLD_CHARACTER_MISSING") }
      }
    } else {
      null
    }
    return PersonaStore.activateWorldCast(context, cast)
  }

  /** Register before the real prompt; daily and auxiliary requests have no world identity. */
  suspend fun register(context: Context, sessionId: String, dshSessionId: String, text: String): String? = prepareMutex.withLock {
    val session = context.zoneuSessionStore().session(sessionId) ?: return@withLock null
    if (!session.isRoleplay) return@withLock null
    val requestId = UUID.randomUUID().toString()
    val snapshot = snapshot(context, sessionId)
    val anchor = PersonaStore.worldCharacterAnchor(context, snapshot.cards)
    val recent = context.zoneuSessionStore().messages(sessionId).filter {
      it.role == com.zoneu.app.data.db.MessageRole.User && it.status == com.zoneu.app.data.db.MessageStatus.Complete
    }.takeLast(TavernPrompt.LOOK_BACK).map { TavernMessage(TavernMessage.USER, it.content) }
    val history = if (recent.lastOrNull()?.content == text) recent else recent + TavernMessage(TavernMessage.USER, text)
    val plan = makePlan(snapshot, requestId, "dsh", history, dshAnchor = anchor)
    if (plan.injections.any { it.position != "depth" }) {
      val system = PersonaStore.compose(context)
      worldCheck(
        anchor.isNotBlank() && system.indexOf(anchor) >= 0 && system.indexOf(anchor) == system.lastIndexOf(anchor),
        "WORLD_CHARACTER_ANCHOR_INCOMPATIBLE",
      )
    }
    context.worldBookRepository().savePlan(plan)
    bindings.register(WorldRuntimeBindings.Binding(requestId, sessionId, dshSessionId, plan.turn))
    requestId
  }

  fun clearBindings() = bindings.clear()

  internal fun confirmPrompt(requestId: String, dshId: String, trigger: String) {
    // Bind the SDK-created trigger exactly once; subsequent hook acknowledgements are idempotent.
    val binding = bindings.get(requestId) ?: throw WorldBookException("WORLD_UNKNOWN_REQUEST")
    if (binding.trigger == trigger) return
    bindings.bind(requestId, dshId, trigger)
  }

  internal fun binding(requestId: String): WorldRuntimeBindings.Binding? = bindings.get(requestId)

  internal fun cancel(requestId: String) = bindings.cancel(requestId)

  suspend fun handleInternal(context: Context, action: String, args: JsonObject): DeviceActionResult = try {
    val requestId = required(args, "request_id")
    val dshId = required(args, "dsh_session_id")
    val trigger = required(args, "trigger_message_id")
    val payload = when (action) {
      "bind_world_request" -> {
        bindings.bind(requestId, dshId, trigger)
        buildJsonObject {
          put("kind", "zoneu.world.bind.v1")
          put("request_id", requestId)
          put("dsh_session_id", dshId)
          put("trigger_message_id", trigger)
        }
      }

      "resolve_world_request" -> {
        val turn = (args["turn"] as? JsonPrimitive)?.intOrNull
        worldCheck(turn != null && turn > 0, "WORLD_TURN_MISMATCH")
        val binding = bindings.resolve(requestId, dshId, trigger, turn)
        val plan = context.worldBookRepository().plan(requestId) ?: throw WorldBookException("WORLD_PLAN_NOT_FOUND")
        worldCheck(plan.sessionId == binding.sessionId && plan.generation == "dsh" && plan.status == "prepared", "WORLD_REQUEST_MISMATCH")
        wire(plan, dshId)
      }

      else -> throw WorldBookException("WORLD_INTERNAL_ACTION_UNKNOWN")
    }
    DeviceActionResult(true, "world request acknowledged", payload.toString())
  } catch (error: WorldBookException) {
    DeviceActionResult(false, "world request rejected", code = error.code)
  }

  private fun required(args: JsonObject, key: String): String =
    (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 512 }
      ?: throw WorldBookException("WORLD_INVALID_IDENTITY")

  private fun wire(plan: WorldRequestPlan, dshId: String): JsonObject = buildJsonObject {
    put("kind", "zoneu.world.request.v1")
    put("active", plan.injections.isNotEmpty())
    put("request_id", plan.requestId)
    put("session_id", dshId)
    put("binding_revision", plan.bindingRevision)
    put("character_anchor", plan.characterAnchor)
    put(
      "injections",
      JsonArray(
        plan.injections.map { injection ->
          buildJsonObject {
            put("entry_id", injection.entryId)
            put("position", injection.position)
            put("depth", injection.depth)
            put("role", injection.role)
            put("content", injection.content)
          }
        },
      ),
    )
  }

  suspend fun converge(context: Context, requestId: String, result: JsonObject): Boolean {
    val binding = bindings.get(requestId) ?: return false
    val receipt = WorldRuntimeReceipt.verify(binding, result, binding.cancelled) ?: return false
    val repo = context.worldBookRepository()
    val plan = repo.plan(requestId) ?: throw WorldBookException("WORLD_PLAN_NOT_FOUND")
    val settled = repo.settlePlan(requestId, receipt.advance, WorldResolver.digest(receipt.evidence))
    if (receipt.advance && plan.status == "prepared" && settled.status == "settled") {
      PrefsVarsStore(context).save(plan.sessionId, plan.variables)
    }
    bindings.remove(requestId)
    return true
  }
}

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
          requestId = request.requestId,
          sessionId = request.sessionId,
          bindingRevision = snapshot.manifest?.revision ?: "empty",
          generation = "auxiliary",
          turn = snapshot.state.turn,
          requestKind = request.generation,
          advanceTurn = false,
          seed = seed,
          injections = emptyList(),
          decisions = emptyList(),
          activated = emptyList(),
          messages = rendered.first.map { WorldPromptMessage(it.role, it.content) },
        )
      }
      repo.plan(request.requestId)?.let {
        worldCheck(it.sessionId == request.sessionId && it.generation == "direct" && it.status == "prepared", "WORLD_REQUEST_MISMATCH")
        return@withLock it
      }
      val snapshot = snapshot(context, request.sessionId)
      repo.savePlan(
        makePlan(
          snapshot,
          PlanSpec(
            requestId = request.requestId,
            generation = "direct",
            history = request.history,
            tail = request.tail,
            recap = request.recap,
            kind = request.generation,
            anchorId = request.anchorId,
          ),
        ),
      )
    }

    override suspend fun settle(requestId: String, success: Boolean, evidence: String) {
      val repo = context.worldBookRepository()
      val plan = repo.plan(requestId) ?: return
      val settled = repo.settlePlan(requestId, success, WorldResolver.digest(evidence))
      val settledOk = plan.status == "prepared" && settled.status == "settled"
      if (success && settledOk && plan.advanceTurn) {
        PrefsVarsStore(context).save(plan.sessionId, plan.variables)
      }
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
      // 读取是安全的：解释器要用真实变量渲染，才能和生成时的预算判定一致。
      PrefsVarsStore(context).load(sessionId),
      memory,
      preset,
    )
  }

  private fun cleanPersona(cards: List<CharacterCard>): CharacterCard {
    val clean = cards.map { it.copy(world = emptyList()) }
    return if (clean.size > 1) GroupCast.merge(clean) else clean.first()
  }

  /** 一次生成请求的输入；打包后 [makePlan] 不再跟着参数表增长。 */
  private data class PlanSpec(
    val requestId: String,
    val generation: String,
    val history: List<TavernMessage>,
    val tail: String? = null,
    val recap: String? = null,
    val dshAnchor: String = "",
    val kind: String = WorldSelection.GENERATION_NORMAL,
    val anchorId: String = "",
  )

  private fun makePlan(snapshot: Snapshot, spec: PlanSpec): WorldRequestPlan {
    val preset = snapshot.preset
    val trimmed = ContextBudget.trim(spec.history.filter { it.content.isNotBlank() }, preset.historyChars).turns
    // 同一个用户交换的重写/续写必须复用同一轮与同一个 seed，否则 sticky 会提前过期、
    // 候选回答看到的设定也会互相矛盾。
    val advance = spec.kind == WorldSelection.GENERATION_NORMAL
    val planTurn = snapshot.state.turn + if (advance) 1 else 0
    val seed = WorldResolver.digest("${spec.anchorId.ifBlank { spec.requestId }}:$planTurn").take(8).toLong(16).toInt()
    val persona = cleanPersona(snapshot.cards)
    val (selection, selectedValues) = selectEntries(snapshot, persona, trimmed, seed, spec.kind, advance)
    var values = selectedValues
    // dsh 的上下文由运行时定，停在前情提要里等于长会话失忆；把提要作为末尾 system 深度注入。
    val recapInjection =
      if (spec.generation != "direct" && !spec.recap.isNullOrBlank()) {
        listOf(
          WorldInjection(
            entryId = "zoneu-recap",
            key = "recap",
            position = "depth",
            depth = 0,
            role = "system",
            content = "【前情提要】\n${spec.recap.trim()}",
          ),
        )
      } else {
        emptyList()
      }
    // dsh 的唯一投影协议只认 before/after/depth：常驻条目在那边退化成末尾深度插入。
    val injections =
      if (spec.generation == "direct") {
        selection.injections
      } else {
        selection.injections.map { if (it.position == "constant") it.copy(position = "depth", depth = 0) else it } + recapInjection
      }
    val rendered =
      if (spec.generation == "direct") {
        renderDirect(snapshot, persona, trimmed, spec.tail to spec.recap, seed, injections, values)
      } else {
        null
      }
    val anchor = rendered?.first?.firstOrNull { it.role == TavernMessage.SYSTEM }?.content ?: spec.dshAnchor
    val messages = rendered?.first.orEmpty()
    values = rendered?.second ?: values
    return WorldRequestPlan(
      requestId = spec.requestId,
      sessionId = snapshot.session.id,
      bindingRevision = snapshot.manifest?.revision ?: "empty",
      generation = spec.generation,
      turn = planTurn,
      requestKind = spec.kind,
      advanceTurn = advance,
      seed = seed,
      injections = injections,
      decisions = selection.decisions,
      activated = selection.activated,
      characterAnchor = anchor,
      variables = values,
      messages = messages.map { WorldPromptMessage(it.role, it.content) },
    )
  }

  /** 选条目并渲染宏；只有装进预算的那条才提交 `{{setvar}}` 的变量写入。 */
  private fun selectEntries(
    snapshot: Snapshot,
    persona: CharacterCard,
    trimmed: List<TavernMessage>,
    seed: Int,
    kind: String,
    advance: Boolean,
  ): Pair<WorldSelection.Result, Map<String, String>> {
    val preset = snapshot.preset
    val now = System.currentTimeMillis()
    var values = snapshot.variables
    val selection =
      WorldSelection.select(
        WorldSelection.Input(
          entries = snapshot.entries,
          text = TavernPrompt.keywordSource(trimmed, preset.scanDepth, persona.name),
          state = snapshot.state,
          seed = seed,
          limits =
            WorldScan.Limits(
              maxEntries = preset.worldEntries,
              maxChars = preset.worldChars,
              maxConstantChars = preset.constantChars,
              depth = preset.recursionDepth,
            ),
          generation = kind,
          characterNames = snapshot.cards.map { it.name },
          characterTags = snapshot.cards.flatMap { it.tags }.toSet(),
          history = trimmed,
          scanDepth = preset.scanDepth,
          characterName = persona.name,
          turnAdvance = advance,
        ),
      ) { entry, budget ->
        val table = TavernVars(values)
        val card = snapshot.cards.firstOrNull { "card:${it.id}" == entry.bookId } ?: persona
        val content = CharacterStore.renderText(
          TavernMacros.render(entry.rule.content, table, now, Random(seed.toLong() xor entry.key.hashCode().toLong())),
          card,
        )
        if (content.isNotBlank() && content.length <= budget) values = table.snapshot()
        content
      }
    return selection to values
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
    val preset = snapshot.preset
    val recent =
      context.zoneuSessionStore().messages(sessionId)
        .filter { it.status == com.zoneu.app.data.db.MessageStatus.Complete }
        .map {
          TavernMessage(
            if (it.role == com.zoneu.app.data.db.MessageRole.User) TavernMessage.USER else TavernMessage.ASSISTANT,
            it.content,
          )
        }
    val scanned = if (text.isBlank()) TavernPrompt.keywordSource(recent, preset.scanDepth, snapshot.cards.first().name) else text
    val seed = WorldResolver.digest("explain:$sessionId:${snapshot.state.turn + 1}").take(8).toLong(16).toInt()
    val now = System.currentTimeMillis()
    val result = WorldSelection.select(
      WorldSelection.Input(
        entries = snapshot.entries,
        text = scanned,
        state = snapshot.state,
        seed = seed,
        limits =
          WorldScan.Limits(
            maxEntries = preset.worldEntries,
            maxChars = preset.worldChars,
            maxConstantChars = preset.constantChars,
            depth = preset.recursionDepth,
          ),
        explain = true,
        characterNames = snapshot.cards.map { it.name },
        characterTags = snapshot.cards.flatMap { it.tags }.toSet(),
        history = if (text.isBlank()) recent else emptyList(),
        scanDepth = preset.scanDepth,
        characterName = snapshot.cards.first().name,
      ),
    ) { entry, _ ->
      // 只读渲染：预算判定必须和真实生成用同一段宏展开结果，但不能回写变量。
      val card = snapshot.cards.firstOrNull { "card:${it.id}" == entry.bookId } ?: snapshot.cards.first()
      CharacterStore.renderText(
        TavernMacros.render(
          entry.rule.content,
          TavernVars(snapshot.variables),
          now,
          Random(seed.toLong() xor entry.key.hashCode().toLong()),
        ),
        card,
      )
    }
    return buildJsonObject {
      put("kind", "zoneu.world.explain.v1")
      put("session_id", sessionId)
      put("binding_revision", snapshot.manifest?.revision ?: "empty")
      put("turn", snapshot.state.turn + 1)
      put(
        "budget",
        buildJsonObject {
          put("max_entries", preset.worldEntries)
          put("max_chars", preset.worldChars)
          put("max_constant_chars", preset.constantChars)
        },
      )
      put("decisions", JsonArray(result.decisions.map(::decisionJson)))
    }
  }

  private fun decisionJson(decision: WorldDecision): JsonObject = buildJsonObject {
    put("entry_id", decision.entryId)
    put("book_id", decision.bookId)
    put("revision", decision.revision)
    put("sources", JsonArray(decision.sources.map(::JsonPrimitive)))
    put("reason", decision.reason)
    put("selected", decision.selected)
    put("chars", decision.chars)
    put("position", decision.position)
    put("role", decision.role)
    put("depth", decision.depth)
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
    val messages =
      context.zoneuSessionStore().messages(sessionId).filter {
        it.status == com.zoneu.app.data.db.MessageStatus.Complete
      }
    val recent =
      messages.map {
        TavernMessage(
          if (it.role == com.zoneu.app.data.db.MessageRole.User) TavernMessage.USER else TavernMessage.ASSISTANT,
          it.content,
        )
      }
    val anchorId = messages.lastOrNull { it.role == com.zoneu.app.data.db.MessageRole.User }?.id.orEmpty()
    val history = if (recent.lastOrNull()?.content == text) recent else recent + TavernMessage(TavernMessage.USER, text)
    val plan =
      makePlan(
        snapshot,
        PlanSpec(
          requestId = requestId,
          generation = "dsh",
          history = history,
          dshAnchor = anchor,
          kind = WorldSelection.GENERATION_NORMAL,
          anchorId = anchorId,
        ),
      )
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

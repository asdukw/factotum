package com.zoneu.app.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.util.UUID

internal data class CardPatchResult(
  val card: CharacterCard,
  val changedFields: List<String>,
  val added: Int,
  val updated: Int,
  val removed: Int,
)

/** Pure sparse edits. Callers perform approval and revision CAS; this object never touches storage. */
internal object CardPatch {
  private val cardFields = listOf(
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
    "use_card_prompt",
    "use_example_dialogue",
  )
  private val worldFields = setOf(
    "keys",
    "content",
    "order",
    "constant",
    "depth",
    "secondary",
    "secondary_logic",
    "exclude",
    "probability",
    "sticky",
    "cooldown",
    "position",
    "role",
    "use_probability",
    "match_whole_words",
    "case_sensitive",
    "prevent_recursion",
    "exclude_recursion",
    "selective",
    "group",
    "group_override",
    "group_weight",
    "use_group_scoring",
    "delay",
    "delay_until_recursion",
    "ignore_budget",
    "triggers",
    "character_filter_names",
    "character_filter_tags",
    "character_filter_exclude",
    "scan_depth",
    "enabled",
    "vectorized",
  )

  fun apply(card: CharacterCard, revision: String, fields: JsonObject?, worldOps: JsonArray?): CardPatchResult {
    patchCheck(revision.isNotBlank(), "CARD_REVISION_REQUIRED")
    patchCheck(!fields.isNullOrEmpty() || !worldOps.isNullOrEmpty(), "CARD_EMPTY_PATCH")
    val request = JsonObject(
      buildMap {
        fields?.let { put("fields", it) }
        worldOps?.let { put("world_ops", it) }
      },
    )
    patchSize(request, "patch", request.toString().toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON)
    val originalIds = card.world.mapIndexed { index, entry -> entryId(card.id, revision, index, entry) }
    patchCheck(originalIds.distinct().size == originalIds.size, "CARD_ENTRY_CONFLICT")
    val next = editCard(card, fields ?: JsonObject(emptyMap()))
    val world = editWorld(card.world, originalIds, worldOps ?: JsonArray(emptyList()))
    val changed = cardFields.filter { field -> fieldValue(card, field) != fieldValue(next, field) }
    val worldChanges = world.added + world.updated + world.removed
    if (changed.isEmpty() && worldChanges == 0) {
      return CardPatchResult(card, emptyList(), 0, 0, 0)
    }
    // Materialize every surviving legacy identity only in the returned changed candidate, never on reads/no-ops.
    val used = card.world.map { it.sourceToken }.filter { it.isNotBlank() }.toMutableSet()
    val identified = world.entries.map { entry ->
      if (entry.sourceToken.isNotBlank()) {
        entry
      } else {
        var token: String
        do {
          token = UUID.randomUUID().toString()
        } while (!used.add(token))
        entry.copy(sourceToken = token)
      }
    }
    return CardPatchResult(next.copy(world = identified), changed, world.added, world.updated, world.removed)
  }

  /** Opaque persistent references survive reordering; legacy references are bound to the exact revision/slot. */
  fun entryId(cardId: String, revision: String, index: Int, entry: WorldEntry): String {
    patchCheck(revision.isNotBlank(), "CARD_REVISION_REQUIRED")
    patchCheck(index >= 0, "CARD_INVALID_FIELDS")
    val identity = if (entry.sourceToken.isBlank()) {
      listOf("legacy", cardId, revision, index.toString())
    } else {
      listOf("token", cardId, entry.sourceToken)
    }
    val bytes = JsonArray(identity.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return "entry-$hash"
  }

  private fun editCard(card: CharacterCard, fields: JsonObject): CharacterCard {
    patchCheck(fields.keys.all { it in cardFields })
    val edit = PatchFields(fields, "fields")
    val name = edit.text("name", card.name)
    if ("name" in fields) patchCheck(name.isNotBlank())
    return card.copy(
      name = name,
      nickname = edit.text("nickname", card.nickname, 200),
      tagline = edit.text("tagline", card.tagline),
      tags = edit.strings("tags", card.tags, 100),
      description = edit.text("description", card.description),
      personality = edit.text("personality", card.personality),
      scenario = edit.text("scenario", card.scenario),
      firstMes = edit.text("first_mes", card.firstMes),
      alternateGreetings = edit.strings("alternate_greetings", card.alternateGreetings, 100),
      groupOnlyGreetings = edit.strings("group_only_greetings", card.groupOnlyGreetings, 100),
      useCardPrompt = edit.boolean("use_card_prompt", card.useCardPrompt),
      useExampleDialogue = edit.boolean("use_example_dialogue", card.useExampleDialogue),
    )
  }

  private fun fieldValue(card: CharacterCard, field: String): Any = when (field) {
    "name" -> card.name
    "nickname" -> card.nickname
    "tagline" -> card.tagline
    "tags" -> card.tags
    "description" -> card.description
    "personality" -> card.personality
    "scenario" -> card.scenario
    "first_mes" -> card.firstMes
    "group_only_greetings" -> card.groupOnlyGreetings
    "use_card_prompt" -> card.useCardPrompt
    "use_example_dialogue" -> card.useExampleDialogue
    else -> card.alternateGreetings
  }

  private data class WorldChanges(val entries: List<WorldEntry>, val added: Int, val updated: Int, val removed: Int)

  private fun editWorld(entries: List<WorldEntry>, ids: List<String>, ops: JsonArray): WorldChanges {
    patchSize(ops, "world_ops", ops.size <= 200)
    val changes = mutableMapOf<Int, WorldEntry?>()
    val additions = mutableListOf<WorldEntry>()
    var updated = 0
    ops.forEachIndexed { opIndex, value ->
      val op = value as? JsonObject ?: patchInvalid()
      val operation = (op["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: patchInvalid()
      if (operation == "add") {
        patchCheck(op.keys == setOf("op", "fields"))
        additions += editEntry(WorldEntry(), entryFields(op), "world_ops[$opIndex].fields")
      } else {
        patchCheck(operation in setOf("update", "remove"))
        val expectedKeys = if (operation == "remove") setOf("op", "entry_id") else setOf("op", "entry_id", "fields")
        patchCheck(op.keys == expectedKeys)
        val id = (op["entry_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: patchInvalid()
        val index = ids.indexOf(id)
        patchCheck(index >= 0, "CARD_ENTRY_NOT_FOUND")
        patchCheck(index !in changes, "CARD_ENTRY_CONFLICT")
        val entry = if (operation == "remove") null else editEntry(entries[index], entryFields(op), "world", id)
        if (entry != null && entry != entries[index]) updated++
        changes[index] = entry
      }
    }
    val result = entries.mapIndexedNotNull { index, entry -> if (index in changes) changes[index] else entry } + additions
    return WorldChanges(result, additions.size, updated, changes.values.count { it == null })
  }

  private fun entryFields(op: JsonObject): JsonObject {
    val fields = op["fields"] as? JsonObject ?: patchInvalid()
    patchCheck(fields.isNotEmpty() && fields.keys.all { it in worldFields })
    return fields
  }

  private fun editEntry(entry: WorldEntry, fields: JsonObject, location: String, entryId: String? = null): WorldEntry {
    val edit = PatchFields(fields, location, entryId)
    return entry.copy(
      keys = edit.strings("keys", entry.keys, WorldEntryDraft.MAX_KEYS),
      content = edit.text("content", entry.content, WorldEntryDraft.MAX_CONTENT),
      order = edit.integer("order", entry.order),
      constant = edit.boolean("constant", entry.constant),
      depth = edit.integer("depth", entry.depth, WorldEntryDraft.MIN_DEPTH..WorldEntryDraft.MAX_DEPTH),
      secondary = edit.strings("secondary", entry.secondary, WorldEntryDraft.MAX_KEYS),
      secondaryLogic = edit.logic(entry.secondaryLogic),
      exclude = edit.strings("exclude", entry.exclude, WorldEntryDraft.MAX_KEYS),
      probability = edit.integer("probability", entry.probability, 0..100),
      sticky = edit.integer("sticky", entry.sticky, 0..WorldEntryDraft.MAX_STICKY),
      cooldown = edit.integer("cooldown", entry.cooldown, 0..WorldEntryDraft.MAX_STICKY),
      position = edit.choice(
        "position",
        entry.position,
        setOf(WorldEntry.POSITION_BEFORE, WorldEntry.POSITION_AFTER, WorldEntry.POSITION_DEPTH),
      ),
      role = edit.choice("role", entry.role, setOf(WorldEntry.ROLE_SYSTEM, WorldEntry.ROLE_USER, WorldEntry.ROLE_ASSISTANT)),
      useProbability = edit.boolean("use_probability", entry.useProbability),
      matchWholeWords = edit.boolean("match_whole_words", entry.matchWholeWords),
      caseSensitive = edit.boolean("case_sensitive", entry.caseSensitive),
      preventRecursion = edit.boolean("prevent_recursion", entry.preventRecursion),
      excludeRecursion = edit.boolean("exclude_recursion", entry.excludeRecursion),
      selective = edit.optionalBoolean("selective", entry.selective),
      group = edit.text("group", entry.group, 512),
      groupOverride = edit.boolean("group_override", entry.groupOverride),
      groupWeight = edit.integer("group_weight", entry.groupWeight, 0..10_000),
      useGroupScoring = edit.boolean("use_group_scoring", entry.useGroupScoring),
      delay = edit.integer("delay", entry.delay, 0..1_000),
      delayUntilRecursion = edit.integer("delay_until_recursion", entry.delayUntilRecursion, 0..TavernPrompt.MAX_DEPTH),
      ignoreBudget = edit.boolean("ignore_budget", entry.ignoreBudget),
      triggers = edit.strings("triggers", entry.triggers, WorldEntryDraft.MAX_KEYS),
      characterFilterNames = edit.strings("character_filter_names", entry.characterFilterNames, WorldEntryDraft.MAX_KEYS),
      characterFilterTags = edit.strings("character_filter_tags", entry.characterFilterTags, WorldEntryDraft.MAX_KEYS),
      characterFilterExclude = edit.boolean("character_filter_exclude", entry.characterFilterExclude),
      scanDepth = edit.optionalInt("scan_depth", entry.scanDepth, 0..100),
      enabled = edit.boolean("enabled", entry.enabled),
      vectorized = edit.boolean("vectorized", entry.vectorized),
    )
  }
}

/** New values are validated, not normalized; omitted legacy values may exceed current editor limits. */
private class PatchFields(private val fields: JsonObject, private val location: String, private val entryId: String? = null) {
  fun text(key: String, before: String, limit: Int = 100_000): String {
    val value = fields[key] ?: return before
    return string(value, limit, "$location.$key")
  }

  fun strings(key: String, before: List<String>, limit: Int): List<String> {
    val value = fields[key] ?: return before
    val array = value as? JsonArray ?: patchInvalid()
    patchSize(array, "$location.$key", array.size <= limit, entryId)
    return array.mapIndexed { index, element -> string(element, 100_000, "$location.$key[$index]") }
  }

  fun integer(key: String, before: Int, range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE): Int {
    val value = fields[key] ?: return before
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: patchInvalid()
    patchCheck(number in range)
    return number
  }

  fun choice(key: String, before: Int, allowed: Set<Int>): Int {
    if (key !in fields) return before
    return integer(key, before).also { patchCheck(it in allowed) }
  }

  fun boolean(key: String, before: Boolean): Boolean {
    val value = fields[key] ?: return before
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: patchInvalid()
  }

  /** 三态布尔：字段显式给 null 时清回「来源没写」。 */
  fun optionalBoolean(key: String, before: Boolean?): Boolean? {
    val value = fields[key] ?: return before
    if (value is kotlinx.serialization.json.JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: patchInvalid()
  }

  fun optionalInt(key: String, before: Int?, range: IntRange): Int? {
    val value = fields[key] ?: return before
    if (value is kotlinx.serialization.json.JsonNull) return null
    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: patchInvalid()
    patchCheck(number in range)
    return number
  }

  fun logic(before: SecondaryLogic): SecondaryLogic {
    val value = fields["secondary_logic"] ?: return before
    val name = string(value, 20, "$location.secondary_logic")
    return SecondaryLogic.entries.firstOrNull { it.name == name } ?: patchInvalid()
  }

  private fun string(value: JsonElement, limit: Int, field: String): String {
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: patchInvalid()
    patchSize(value, field, text.length <= limit, entryId)
    return text
  }
}

private fun patchCheck(condition: Boolean, code: String = "CARD_INVALID_FIELDS") {
  if (!condition) throw CardOperationException(code)
}

private fun patchSize(value: JsonElement, location: String, valid: Boolean, entryId: String? = null) {
  if (!valid) throw CardFieldTooLargeException(location, value.toString().toByteArray(Charsets.UTF_8).size, entryId)
}

private fun patchInvalid(): Nothing = throw CardOperationException("CARD_INVALID_FIELDS")

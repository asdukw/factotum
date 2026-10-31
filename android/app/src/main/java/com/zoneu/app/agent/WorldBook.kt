package com.zoneu.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.UUID

/** Shared assets are independent of cards. Only these shared revisions are pinned by a chat. */
@Serializable
data class WorldBook(
  val id: String = UUID.randomUUID().toString(),
  val revision: String = "",
  val name: String = "新世界书",
  val description: String = "",
  val tags: List<String> = emptyList(),
  val category: String = "",
  val enabled: Boolean = true,
  val entries: List<WorldBookEntry> = emptyList(),
  val archive: WorldBookArchive? = null,
)

/** Disabled, empty and unsupported entries remain editable assets, not discarded runtime rows. */
@Serializable
data class WorldBookEntry(
  val id: String = UUID.randomUUID().toString(),
  val enabled: Boolean = true,
  val rule: WorldEntry = WorldEntry(),
  val unsupported: List<String> = emptyList(),
  val externalKey: String? = null,
  val raw: JsonElement? = null,
  val baseline: JsonObject? = null,
)

@Serializable
data class WorldBookArchive(
  val root: JsonElement,
  val path: List<String> = emptyList(),
  val entriesKey: String = "entries",
  val mapEntries: Boolean = false,
  val fingerprint: String,
  val baseline: JsonObject = JsonObject(emptyMap()),
  val notes: List<String> = emptyList(),
)

@Serializable
data class WorldRoleBindings(
  val characterId: String,
  val revision: String = "empty",
  val bookIds: List<String> = emptyList(),
)

@Serializable
enum class WorldOverride { INHERIT, ENABLE, DISABLE }

@Serializable
data class WorldBookBinding(
  val bookId: String,
  val revision: String,
  val sources: List<String> = emptyList(),
  val override: WorldOverride = WorldOverride.INHERIT,
  val epoch: String = UUID.randomUUID().toString(),
)

@Serializable
data class WorldManifest(
  val sessionId: String,
  val revision: String = "empty",
  val books: List<WorldBookBinding> = emptyList(),
)

@Serializable
data class WorldUsage(val characterIds: List<String> = emptyList(), val sessionIds: List<String> = emptyList()) {
  val referenced: Boolean get() = characterIds.isNotEmpty() || sessionIds.isNotEmpty()
}

@Serializable
data class WorldResolvedEntry(
  val key: String,
  val bookId: String,
  val revision: String,
  val entryId: String,
  val epoch: String,
  val sources: List<String>,
  val enabled: Boolean,
  val unsupported: List<String>,
  val rule: WorldEntry,
)

@Serializable
data class WorldInjection(
  val entryId: String,
  val key: String,
  val position: String,
  val depth: Int,
  val role: String,
  val content: String,
)

@Serializable
data class WorldDecision(
  val entryId: String,
  val bookId: String,
  val sources: List<String>,
  val reason: String,
  val selected: Boolean = false,
  val chars: Int = 0,
  val position: Int = WorldEntry.POSITION_DEPTH,
  val role: Int = WorldEntry.ROLE_SYSTEM,
)

/** Prepared once per logical generation, including macro output and probability decisions. */
@Serializable
data class WorldRequestPlan(
  val requestId: String,
  val sessionId: String,
  val bindingRevision: String,
  val generation: String,
  val turn: Int,
  val seed: Int,
  val injections: List<WorldInjection>,
  val decisions: List<WorldDecision>,
  val activated: List<String>,
  val characterAnchor: String = "",
  val variables: Map<String, String> = emptyMap(),
  val status: String = "prepared",
  val messages: List<WorldPromptMessage> = emptyList(),
)

@Serializable
data class WorldPromptMessage(val role: String, val content: String)

@Serializable
data class WorldRuntimeState(val turn: Int = 0, val activated: Map<String, Int> = emptyMap())

class WorldBookException(val code: String) : IOException(code)

internal fun worldCheck(condition: Boolean, code: String = "WORLD_INVALID_ARGUMENT") {
  if (!condition) throw WorldBookException(code)
}

internal fun worldId(value: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}").matches(value)

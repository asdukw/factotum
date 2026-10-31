package com.zoneu.app.agent

import android.content.Context
import com.zoneu.app.device.DeviceActionResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Thin host routing: only whitelist DTOs leave this boundary, never stored cards or receipts. */
internal class CharacterCardLibrary(
  private val storage: CardStorage,
  private val previews: CardPreviewBindings,
) {
  fun list(args: JsonObject): DeviceActionResult = operation {
    val listing = storage.listing()
    val result = CharacterCardQuery.list(listing.snapshots.map(::queryItem), args, listing.unreadable)
    DeviceActionResult(true, "角色库查询完成", result.toString())
  }

  fun read(args: JsonObject): DeviceActionResult = operation {
    val snapshot = storage.snapshot(requiredString(args, "character_id")) ?: throw CardOperationException("CARD_NOT_FOUND")
    DeviceActionResult(true, "角色卡指定内容已读取", CharacterCardQuery.read(queryItem(snapshot), args).toString())
  }

  /** No writable()/acknowledge here: approval preview must not create even a recovery receipt. */
  fun preview(request: String, args: JsonObject): DeviceActionResult = operation {
    val patch = parameters(request, args)
    storage.transaction {
      val prior = readReceipt(request)
      val snapshot = snapshot(patch.id) ?: throw CardOperationException(
        if (prior == null) "CARD_NOT_FOUND" else "CARD_PREVIOUSLY_DELETED",
      )
      val replay = previewReplay(snapshot, prior, patch)
      val diff = if (replay) {
        null
      } else {
        cardStorageCheck(snapshot.revision == patch.revision, "CARD_REVISION_CONFLICT")
        patch.apply(snapshot.card)
      }
      val changes = diff?.counts() ?: prior!!.changes
      val unchanged = !replay && diff!!.unchanged()
      previews.bind(
        request,
        patch.fingerprint,
        if (replay) {
          "replay"
        } else if (unchanged) {
          "unchanged"
        } else {
          "write"
        },
      )
      val result = buildJsonObject {
        put("kind", "zoneu.character-card.preview.v1")
        put("ok", true)
        put("character_id", patch.id)
        put("expected_revision", patch.revision)
        put("fingerprint", patch.fingerprint)
        put("unchanged", unchanged)
        put("already_applied", replay)
        put("changes", counts(changes))
        put(
          "field_changes",
          JsonArray(
            diff?.changedFields.orEmpty().map { field ->
              buildJsonObject {
                put("field", field)
                put("before_length", fieldLength(snapshot.card, field))
                put("after_length", fieldLength(diff!!.card, field))
              }
            },
          ),
        )
      }
      DeviceActionResult(true, "角色卡修改预检完成", result.toString())
    }
  }

  private fun previewReplay(snapshot: CardSnapshot, prior: CardRequestReceipt?, patch: LibraryPatch): Boolean {
    if (prior != null) {
      cardStorageCheck(
        listOf(prior.operation, prior.parameters, prior.targetId, prior.expectedRevision) ==
          listOf("update", patch.fingerprint, patch.id, patch.revision),
        "CARD_REQUEST_CONFLICT",
      )
    }
    if (prior != null) {
      cardStorageCheck(
        prior.targetInstance.isEmpty() || snapshot.card.storageState()?.instanceId == prior.targetInstance,
        "CARD_PREVIOUSLY_DELETED",
      )
    }
    cardStorageCheck(snapshot.editable, "CARD_READ_ONLY")
    val evidence = snapshot.card.storageState()?.pending
    val replay = prior != null && (prior.committed || evidence == prior)
    if (!replay && evidence != null) {
      val confirmed = storage.readReceipt(evidence.request)
      cardStorageCheck(confirmed?.committed == true && confirmed == evidence.copy(committed = true), "CARD_RECOVERY_UNCERTAIN")
    }
    if (!replay && prior != null) cardStorageCheck(snapshot.revision == patch.revision, "CARD_RECOVERY_UNCERTAIN")
    return replay
  }

  fun update(request: String, args: JsonObject, fingerprint: String): DeviceActionResult = operation {
    val patch = parameters(request, args)
    val mode = previews.check(request, patch.fingerprint, fingerprint)
    val control = CharacterCardOperations.control(request)
    if (mode == "unchanged") {
      // Never call the committer for a no-op that bypassed approval; not even a journal write.
      return@operation storage.transaction {
        control.check()
        val before = snapshot(patch.id) ?: throw CardOperationException("CARD_NOT_FOUND")
        cardStorageCheck(before.editable, "CARD_READ_ONLY")
        cardStorageCheck(before.revision == patch.revision, "CARD_REVISION_CONFLICT")
        cardStorageCheck(patch.apply(before.card).unchanged(), "CARD_PREVIEW_CONFLICT")
        success(CardUpdateResult(before, before.revision, false, true, CardChangeCounts()))
      }
    }
    val result = CardUpdateCommitter(storage).submit(request, patch.fingerprint, patch.id, patch.revision, control::check) { card ->
      // A replay-only preview cannot fall through into a new mutation if its evidence disappears.
      cardStorageCheck(mode != "replay", "CARD_PREVIEW_CONFLICT")
      val diff = patch.apply(card)
      CardUpdateProposal(diff.card, diff.counts())
    }
    success(result)
  }

  private fun parameters(request: String, args: JsonObject): LibraryPatch {
    cardStorageCheck(request.isNotBlank() && request.length <= 256, "CARD_INVALID_REQUEST")
    cardStorageCheck(args.keys.all { it in setOf("character_id", "expected_revision", "fields", "world_ops") }, "CARD_INVALID_FIELDS")
    val id = requiredString(args, "character_id")
    CardStorage.requireId(id)
    val revision = requiredString(args, "expected_revision", "CARD_REVISION_REQUIRED")
    cardStorageCheck(revision.length <= 200, "CARD_INVALID_FIELDS")
    val fields = args["fields"]?.let { it as? JsonObject ?: throw CardOperationException("CARD_INVALID_FIELDS") }
    val world = args["world_ops"]?.let { it as? JsonArray ?: throw CardOperationException("CARD_INVALID_FIELDS") }
    return LibraryPatch(id, revision, fields, world, cardHash("update:" + cardCanonical(args)))
  }

  private fun success(result: CardUpdateResult): DeviceActionResult {
    val payload = buildJsonObject {
      put("kind", CharacterCardOperations.RESULT_KIND)
      put("ok", true)
      put("characterId", result.snapshot.card.id)
      put("disposition", if (result.unchanged) "unchanged" else "updated")
      put("revision", result.snapshot.revision)
      put("applied_revision", result.appliedRevision)
      put("replay", result.replay)
      put("unchanged", result.unchanged)
      put("changes", counts(result.changes))
    }
    val summary = if (result.unchanged) "角色卡无实际变化，未修改卡内容" else "角色卡修改已保存并完成回读"
    return DeviceActionResult(true, summary, payload.toString())
  }

  private fun operation(block: () -> DeviceActionResult): DeviceActionResult = try {
    block()
  } catch (error: CardFieldTooLargeException) {
    val detail = buildJsonObject {
      put("code", error.code)
      put("location", error.location)
      put("length", error.length)
      error.entryId?.let { put("entry_id", it) }
    }
    CharacterCardOperations.failure(error.code).copy(payload = detail.toString())
  } catch (error: CardOperationException) {
    CharacterCardOperations.failure(error.code)
  } catch (_: java.io.IOException) {
    CharacterCardOperations.failure("CARD_STORAGE_FAILED")
  } catch (_: IllegalArgumentException) {
    CharacterCardOperations.failure("CARD_INVALID_FIELDS")
  } catch (_: IllegalStateException) {
    CharacterCardOperations.failure("CARD_SAVE_FAILED")
  }

  companion object {
    private val bindings = CardPreviewBindings()

    fun forContext(context: Context): CharacterCardLibrary = CharacterCardLibrary(CharacterStore.storage(context), bindings)
  }
}

/** Bounded process-local preflight identity. Losing it fails closed and requires a fresh preview. */
internal class CardPreviewBindings {
  private data class Binding(val fingerprint: String, val mode: String)

  private val entries = linkedMapOf<String, Binding>()

  @Synchronized
  fun bind(request: String, fingerprint: String, mode: String) {
    val prior = entries[request]
    cardStorageCheck(prior == null || prior.fingerprint == fingerprint, "CARD_REQUEST_CONFLICT")
    if (entries.size >= 256 && prior == null) entries.remove(entries.keys.first())
    entries[request] = Binding(fingerprint, mode)
  }

  @Synchronized
  fun check(request: String, parameters: String, supplied: String): String {
    val binding = entries[request] ?: throw CardOperationException("CARD_PREVIEW_REQUIRED")
    cardStorageCheck(binding.fingerprint == parameters && supplied == parameters, "CARD_PREVIEW_CONFLICT")
    return binding.mode
  }
}

private data class LibraryPatch(
  val id: String,
  val revision: String,
  val fields: JsonObject?,
  val world: JsonArray?,
  val fingerprint: String,
) {
  fun apply(card: CharacterCard): CardPatchResult = CardPatch.apply(card, revision, fields, world)
}

private fun requiredString(args: JsonObject, key: String, code: String = "CARD_INVALID_FIELDS"): String =
  (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    ?: throw CardOperationException(code)

private fun queryItem(snapshot: CardSnapshot) = CardQueryItem(snapshot.card, snapshot.revision, snapshot.editable)

private fun CardPatchResult.counts() = CardChangeCounts(changedFields, added, updated, removed)

private fun CardPatchResult.unchanged() = changedFields.isEmpty() && added + updated + removed == 0

private fun counts(changes: CardChangeCounts): JsonObject = buildJsonObject {
  put("changed_fields", JsonArray(changes.changedFields.map(::JsonPrimitive)))
  put("added", changes.added)
  put("updated", changes.updated)
  put("removed", changes.removed)
}

private fun fieldLength(card: CharacterCard, field: String): Int = when (field) {
  "name" -> card.name.length
  "nickname" -> card.nickname.length
  "tagline" -> card.tagline.length
  "description" -> card.description.length
  "personality" -> card.personality.length
  "scenario" -> card.scenario.length
  "first_mes" -> card.firstMes.length
  "tags" -> card.tags.size
  "alternate_greetings" -> card.alternateGreetings.size
  "group_only_greetings" -> card.groupOnlyGreetings.size
  else -> error("not a card field")
}

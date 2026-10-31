package com.zoneu.app.agent

import com.zoneu.app.data.db.SessionKind
import com.zoneu.app.data.db.WorldManifestEntity
import com.zoneu.app.data.db.WorldRoleBindingsEntity
import com.zoneu.app.data.db.WorldRoleReferenceEntity
import com.zoneu.app.data.db.WorldSessionReferenceEntity
import com.zoneu.app.data.db.ZoneuDatabase
import kotlinx.serialization.encodeToString
import java.util.UUID

internal class WorldBookStorageBindings(private val database: ZoneuDatabase) {
  private val dao = database.worldBooks()

  suspend fun usage(id: String): WorldUsage = WorldUsage(dao.roleUsage(id), dao.sessionUsage(id))

  suspend fun roleBindings(characterId: String): WorldRoleBindings =
    dao.roleBindings(characterId)?.let { worldStorageJson.decodeFromString(it.payload) } ?: WorldRoleBindings(characterId)

  suspend fun setRoleBindings(
    bindings: WorldRoleBindings,
    expected: String,
    requestId: String,
    parameters: String,
  ): WorldRoleBindings {
    val draft = bindings.copy(revision = expected, bookIds = bindings.bookIds.distinct())
    val hash = worldParameters("role-bindings", parameters, worldStorageJson.encodeToString(draft))
    database.worldReplay(requestId, "role-bindings", hash)?.let { return roleBindings(it.targetId) }
    worldCheck(worldId(bindings.characterId))
    val current = roleBindings(bindings.characterId)
    worldCas(current.revision, expected)
    draft.bookIds.forEach { WorldBookStorageAssets(database).read(it) }
    if (current.bookIds == draft.bookIds) {
      return current
    }
    val saved = draft.copy(revision = UUID.randomUUID().toString())
    dao.clearRoleReferences(saved.characterId)
    dao.putRoleBindings(WorldRoleBindingsEntity(saved.characterId, saved.revision, worldStorageJson.encodeToString(saved)))
    if (saved.bookIds.isNotEmpty()) dao.insertRoleReferences(saved.bookIds.map { WorldRoleReferenceEntity(saved.characterId, it) })
    database.worldReceipt(requestId, "role-bindings", hash, saved.characterId, saved.revision)
    return saved
  }

  suspend fun manifest(sessionId: String): WorldManifest? =
    dao.manifest(sessionId)?.let { worldStorageJson.decodeFromString(it.payload) }

  suspend fun setManifest(
    manifest: WorldManifest,
    expected: String,
    requestId: String,
    parameters: String,
  ): WorldManifest {
    // Epochs are repository-owned and excluded from the normalized operation fingerprint.
    val args = manifest.copy(revision = expected, books = WorldBookStorageRules.bindings(manifest.books))
    val hash = worldParameters("manifest", parameters, worldStorageJson.encodeToString(args))
    database.worldReplay(requestId, "manifest", hash)?.let {
      return manifest(it.targetId) ?: WorldManifest(it.targetId).also { replay -> requireRoleplaySession(replay.sessionId) }
    }
    requireRoleplaySession(manifest.sessionId)
    val current = manifest(manifest.sessionId)
    worldCas(current?.revision ?: "empty", expected)
    val draft = worldNormalizeManifest(manifest, current)
    val assets = WorldBookStorageAssets(database)
    draft.books.forEach { assets.read(it.bookId, it.revision) }
    if (draft.books == current?.books.orEmpty()) {
      val saved = current ?: WorldManifest(manifest.sessionId)
      return saved
    }
    val saved = draft.copy(revision = UUID.randomUUID().toString())
    writeManifest(saved)
    database.worldReceipt(requestId, "manifest", hash, saved.sessionId, saved.revision)
    return saved
  }

  /** Parent means the actual branch source, not the sidebar root. A legacy parent never picks up new defaults. */
  suspend fun initializeSession(sessionId: String, characterIds: List<String>, worldParentId: String?) {
    requireRoleplaySession(sessionId)
    worldCheck(dao.manifest(sessionId) == null, "WORLD_BINDING_CONFLICT")
    val books = if (worldParentId != null) {
      requireRoleplaySession(worldParentId)
      manifest(worldParentId)?.books.orEmpty().map { it.copy(epoch = UUID.randomUUID().toString()) }
    } else {
      val rows = mutableListOf<WorldBookBinding>()
      for (characterId in characterIds.distinct()) {
        for (bookId in roleBindings(characterId).bookIds) {
          val book = WorldBookStorageAssets(database).read(bookId)
          rows += WorldBookBinding(bookId, book.revision, sources = listOf("character:$characterId"))
        }
      }
      worldNormalizeManifest(WorldManifest(sessionId, books = rows), null).books
    }
    writeManifest(WorldManifest(sessionId, UUID.randomUUID().toString(), books))
  }

  private suspend fun requireRoleplaySession(id: String) {
    val session = database.store().session(id) ?: throw WorldBookException("WORLD_SESSION_NOT_FOUND")
    worldCheck(session.kind == SessionKind.Roleplay, "WORLD_NOT_ROLEPLAY")
  }

  private suspend fun writeManifest(manifest: WorldManifest) {
    dao.clearSessionReferences(manifest.sessionId)
    dao.putManifest(WorldManifestEntity(manifest.sessionId, manifest.revision, worldStorageJson.encodeToString(manifest)))
    if (manifest.books.isNotEmpty()) {
      dao.insertSessionReferences(manifest.books.map { WorldSessionReferenceEntity(manifest.sessionId, it.bookId, it.revision) })
    }
  }
}

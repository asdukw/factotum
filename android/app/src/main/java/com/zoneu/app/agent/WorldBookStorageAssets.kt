package com.zoneu.app.agent

import com.zoneu.app.data.db.WorldBookEntryIdentityEntity
import com.zoneu.app.data.db.WorldBookHeadEntity
import com.zoneu.app.data.db.WorldBookRevisionEntity
import com.zoneu.app.data.db.ZoneuDatabase
import kotlinx.serialization.encodeToString
import java.util.UUID

/** All mutators are called by the repository inside Room.withTransaction. */
internal class WorldBookStorageAssets(private val database: ZoneuDatabase) {
  private val dao = database.worldBooks()

  suspend fun list(): List<WorldBook> = dao.heads().map { read(it.id) }

  suspend fun read(id: String, revision: String? = null): WorldBook {
    val head = dao.head(id)?.takeUnless { it.deleted } ?: throw WorldBookException("WORLD_NOT_FOUND")
    val row = dao.revision(id, revision ?: head.revision) ?: throw WorldBookException("WORLD_NOT_FOUND")
    return worldStorageJson.decodeFromString<WorldBook>(row.payload).also {
      worldCheck(it.id == id && it.revision == row.revision, "WORLD_STORAGE_CORRUPT")
    }
  }

  suspend fun create(book: WorldBook, requestId: String, parameters: String): WorldBook {
    val hash = worldParameters("create", parameters, worldStorageJson.encodeToString(WorldBookStorageRules.createDraft(book)))
    database.worldReplay(requestId, "create", hash)?.let { return read(it.targetId) }
    book.validated()
    // The original JSON structure, not a caller-supplied alias, owns the permanent import identity.
    val fingerprint = book.archive?.let { worldHash(worldCanonical(it.root).toString()) }
    val duplicate = fingerprint?.let { dao.fingerprint(it) }
    if (duplicate != null) {
      worldCheck(!duplicate.deleted, "WORLD_DUPLICATE_DELETED")
      val saved = read(duplicate.id)
      return saved
    }
    worldCheck(dao.head(book.id) == null, "WORLD_ID_CONFLICT")
    val saved = insert(book, fingerprint)
    database.worldReceipt(requestId, "create", hash, saved.id, saved.revision)
    return saved
  }

  suspend fun update(book: WorldBook, expected: String, requestId: String, parameters: String): WorldBook {
    val fallback = worldStorageJson.encodeToString(book.copy(revision = expected))
    val hash = worldParameters("update", parameters, fallback)
    database.worldReplay(requestId, "update", hash)?.let { return read(it.targetId) }
    val current = read(book.id)
    worldCas(current.revision, expected)
    book.validated()
    if (current == book.copy(revision = current.revision)) {
      return current
    }
    reserveEntries(book, current.entries.map { it.id }.toSet())
    val saved = book.copy(revision = UUID.randomUUID().toString())
    dao.insertRevision(WorldBookRevisionEntity(saved.id, saved.revision, worldStorageJson.encodeToString(saved)))
    worldCheck(dao.advanceHead(saved.id, expected, saved.revision) == 1, "WORLD_REVISION_CONFLICT")
    database.worldReceipt(requestId, "update", hash, saved.id, saved.revision)
    return saved
  }

  suspend fun copy(id: String, expected: String, requestId: String, parameters: String): WorldBook {
    val hash = worldParameters("copy", parameters, worldStorageJson.encodeToString(listOf(id, expected)))
    database.worldReplay(requestId, "copy", hash)?.let { return read(it.targetId) }
    val original = read(id)
    worldCas(original.revision, expected)
    val draft = original.copy(
      id = UUID.randomUUID().toString(),
      revision = "",
      // A copy of an already-512-char name must not persist an over-limit head.
      name = (original.name + " · 副本").take(512),
      entries = original.entries.map { it.copy(id = UUID.randomUUID().toString()) },
    ).validated()
    // Explicit copies retain interchange archives, but do not claim the original import fingerprint.
    val saved = insert(draft, null)
    database.worldReceipt(requestId, "copy", hash, saved.id, saved.revision)
    return saved
  }

  suspend fun delete(id: String, expected: String, requestId: String, parameters: String) {
    val hash = worldParameters("delete", parameters, worldStorageJson.encodeToString(listOf(id, expected)))
    if (database.worldReplay(requestId, "delete", hash) != null) return
    val current = read(id)
    worldCas(current.revision, expected)
    val usage = WorldBookStorageBindings(database).usage(id)
    worldCheck(!usage.referenced, "WORLD_REFERENCED")
    val revision = UUID.randomUUID().toString()
    // Keep the head pointer and the receipt pointing at an existing revision row.
    dao.insertRevision(WorldBookRevisionEntity(id, revision, worldStorageJson.encodeToString(current.copy(revision = revision))))
    worldCheck(dao.advanceHead(id, expected, revision, true) == 1, "WORLD_REVISION_CONFLICT")
    database.worldReceipt(requestId, "delete", hash, id, revision)
  }

  private suspend fun insert(book: WorldBook, fingerprint: String?): WorldBook {
    val saved = book.copy(revision = UUID.randomUUID().toString())
    dao.insertHead(WorldBookHeadEntity(saved.id, saved.revision, false, fingerprint))
    reserveEntries(saved, emptySet())
    dao.insertRevision(WorldBookRevisionEntity(saved.id, saved.revision, worldStorageJson.encodeToString(saved)))
    return saved
  }

  private suspend fun reserveEntries(book: WorldBook, currentIds: Set<String>) {
    val added = book.entries.filter { it.id !in currentIds }
    for (entry in added) worldCheck(dao.entryIdentity(entry.id) == null, "WORLD_ENTRY_CONFLICT")
    if (added.isNotEmpty()) dao.insertEntryIdentities(added.map { WorldBookEntryIdentityEntity(it.id, book.id) })
  }
}

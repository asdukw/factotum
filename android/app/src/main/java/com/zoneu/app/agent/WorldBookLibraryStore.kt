package com.zoneu.app.agent

import androidx.room.withTransaction

/** Only persistence operations used by management, with the production Room transaction unchanged. */
internal interface WorldBookLibraryStore : WorldLibraryBindings {
  suspend fun <T> transaction(block: suspend () -> T): T

  suspend fun list(): List<WorldBook>

  suspend fun read(id: String, revision: String? = null): WorldBook

  suspend fun usage(id: String): WorldUsage

  suspend fun receipt(request: String, parameters: String): WorldOperationReceipt?

  suspend fun cancelOperation(request: String, parameters: String): Boolean

  suspend fun create(book: WorldBook, request: String, parameters: String): WorldBook

  suspend fun update(book: WorldBook, expected: String, request: String, parameters: String): WorldBook

  suspend fun copy(id: String, expected: String, request: String, parameters: String): WorldBook

  suspend fun delete(id: String, expected: String, request: String, parameters: String)
}

internal interface WorldLibraryBindings {
  suspend fun roleBindings(id: String): WorldRoleBindings

  suspend fun manifest(id: String): WorldManifest?

  suspend fun setRoleBindings(bindings: WorldRoleBindings, expected: String, request: String, parameters: String): WorldRoleBindings

  suspend fun setManifest(manifest: WorldManifest, expected: String, request: String, parameters: String): WorldManifest
}

internal class WorldBookLibraryRepository(private val repository: WorldBookRepository) : WorldBookLibraryStore {
  override suspend fun <T> transaction(block: suspend () -> T): T = repository.database.withTransaction { block() }

  override suspend fun list(): List<WorldBook> = repository.list()

  override suspend fun read(id: String, revision: String?): WorldBook = repository.read(id, revision)

  override suspend fun usage(id: String): WorldUsage = repository.usage(id)

  override suspend fun receipt(request: String, parameters: String): WorldOperationReceipt? = repository.receipt(request, parameters)

  override suspend fun cancelOperation(request: String, parameters: String): Boolean = repository.cancelOperation(request, parameters)

  override suspend fun create(
    book: WorldBook,
    request: String,
    parameters: String,
  ): WorldBook = repository.create(
    book,
    request,
    parameters,
  )

  override suspend fun update(book: WorldBook, expected: String, request: String, parameters: String): WorldBook =
    repository.update(book, expected, request, parameters)

  override suspend fun copy(id: String, expected: String, request: String, parameters: String): WorldBook =
    repository.copy(id, expected, request, parameters)

  override suspend fun delete(
    id: String,
    expected: String,
    request: String,
    parameters: String,
  ) = repository.delete(
    id,
    expected,
    request,
    parameters,
  )

  override suspend fun roleBindings(id: String): WorldRoleBindings = repository.roleBindings(id)

  override suspend fun manifest(id: String): WorldManifest? = repository.manifest(id)

  override suspend fun setRoleBindings(
    bindings: WorldRoleBindings,
    expected: String,
    request: String,
    parameters: String,
  ): WorldRoleBindings =
    repository.setRoleBindings(bindings, expected, request, parameters)

  override suspend fun setManifest(manifest: WorldManifest, expected: String, request: String, parameters: String): WorldManifest =
    repository.setManifest(manifest, expected, request, parameters)
}

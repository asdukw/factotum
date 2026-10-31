package com.zoneu.app.agent

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** The only file writer. Location, not serialized flags, decides mutability. */
internal class CardStorageFiles(
  private val context: Context,
  private val decoder: Json,
  private val encoder: Json,
) {
  private val directory get() = File(context.filesDir, ASSET_DIR)

  fun read(id: String): CardSnapshot? {
    CardStorage.requireId(id)
    val assets = builtins().filter { it.card.id == id }
    val local = readLocal(File(directory, "$id.json"))
    if (assets.size > 1 || (assets.isNotEmpty() && local != null)) throw CardOperationException("CARD_ID_CONFLICT")
    return assets.singleOrNull() ?: local
  }

  fun snapshots(): List<CardSnapshot> {
    val all = builtins() + localFiles().mapNotNull(::readLocal).sortedBy { it.card.name }
    if (all.map { it.card.id }.distinct().size != all.size) throw CardOperationException("CARD_ID_CONFLICT")
    return all
  }

  /** UI browsing isolates damaged legacy files; strict mutation/dedupe reads above still fail closed. */
  fun listing(): CardSnapshotListing {
    var unreadable = 0
    val candidates = builtins() + localFiles().mapNotNull { file ->
      runCatching { readLocal(file) }.getOrElse {
        unreadable++
        null
      }
    }.sortedBy { it.card.name }
    val collisions = candidates.groupBy { it.card.id }.filterValues { it.size > 1 }.keys
    unreadable += candidates.count { it.card.id in collisions }
    return CardSnapshotListing(candidates.filter { it.card.id !in collisions }, unreadable)
  }

  fun write(card: CharacterCard, png: ByteArray? = null) {
    CardStorage.requireId(card.id)
    check(directory.isDirectory || directory.mkdirs())
    val atomic = AtomicFile(File(directory, "${card.id}.json"))
    val bytes = encoder.encodeToString(CharacterCard.serializer(), card).toByteArray(Charsets.UTF_8)
    val stream = atomic.startWrite()
    var finished = false
    try {
      stream.write(bytes)
      atomic.finishWrite(stream)
      finished = true
      if (png != null) FileOutputStream(File(directory, "${card.id}.png")).use { it.write(png) }
    } finally {
      if (!finished) atomic.failWrite(stream)
    }
  }

  fun avatarFile(id: String): File? {
    CardStorage.requireId(id)
    return File(directory, "$id.png").takeIf { it.isFile && it.length() > 0L }
  }

  fun delete(id: String) {
    CardStorage.requireId(id)
    AtomicFile(File(directory, "$id.json")).delete()
    File(directory, "$id.png").delete()
  }

  private fun builtins(): List<CardSnapshot> = context.assets.list(ASSET_DIR).orEmpty()
    .filter { it.endsWith(".json") }
    .mapNotNull { file ->
      val text = context.assets.open("$ASSET_DIR/$file").bufferedReader().use { it.readText() }
      val card = runCatching { decoder.decodeFromString<CharacterCard>(text) }.getOrNull() ?: return@mapNotNull null
      val id = card.id.ifBlank { file.removeSuffix(".json") }
      snapshot(card.copy(id = id, imported = false), text, false)
    }.sortedBy { it.card.id }

  private fun localFiles(): List<File> = directory.listFiles().orEmpty()
    .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
    .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }
    .distinctBy { it.path }

  private fun readLocal(file: File): CardSnapshot? {
    if (!file.exists() && !File(file.path + ".bak").exists()) return null
    val text = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    val card = decoder.decodeFromString<CharacterCard>(text)
    if (card.id != file.name.removeSuffix(".json")) throw CardOperationException("CARD_ID_CONFLICT")
    return snapshot(card.copy(imported = true), text, true)
  }

  private fun snapshot(card: CharacterCard, text: String, editable: Boolean): CardSnapshot {
    val revision = if (editable) card.storageRevision?.takeIf { it.isNotBlank() } else null
    val state = CardStorageState(
      revision ?: "legacy-" + cardHash("$editable:${card.id}:$text"),
      if (editable) card.storageState()?.pending else null,
      card.storageState()?.instanceId?.takeIf { editable && it.isNotBlank() }
        ?: "legacy-instance-" + cardHash("$editable:${card.id}:$text"),
    )
    return CardSnapshot(card.withStorage(state), state.revision, editable)
  }

  companion object {
    private const val ASSET_DIR = "roleplay/characters"
  }
}

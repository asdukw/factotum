package com.zoneu.app.agent

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/** 只存指纹、目标身份及提交阶段，不存下载地址或角色正文。 */
@Serializable
data class CardRequestReceipt(
  val request: String,
  val parameters: String,
  val targetId: String,
  val contentHash: String,
  val committed: Boolean = false,
  val protocol: Int = 1,
  val operation: String = "create",
  val expectedRevision: String = "",
  val appliedRevision: String = "",
  val unchanged: Boolean = false,
  val changes: CardChangeCounts = CardChangeCounts(),
  val targetInstance: String = "",
)

internal class CardRequestJournal(private val directory: File) {
  fun read(request: String): CardRequestReceipt? {
    val file = target(request)
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
    return file.openRead().bufferedReader().use { Json.decodeFromString<CardRequestReceipt>(it.readText()) }
  }

  fun write(receipt: CardRequestReceipt) {
    check(directory.isDirectory || directory.mkdirs())
    val file = target(receipt.request)
    val bytes = Json.encodeToString(CardRequestReceipt.serializer(), receipt).toByteArray(Charsets.UTF_8)
    val output = file.startWrite()
    try {
      output.write(bytes)
      file.finishWrite(output)
    } catch (error: java.io.IOException) {
      file.failWrite(output)
      throw error
    }
    if (read(receipt.request) != receipt) throw CardOperationException("CARD_RECEIPT_UNCONFIRMED")
  }

  private fun target(request: String) = AtomicFile(File(directory, "${cardHash(request)}.json"))
}

internal fun cardHash(text: String): String = MessageDigest.getInstance("SHA-256")
  .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun cardCanonical(value: JsonElement): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.toSortedMap().mapValues { cardCanonical(it.value) })
  is JsonArray -> JsonArray(value.map(::cardCanonical))
  else -> value
}

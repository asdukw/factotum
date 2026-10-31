package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class CardInputTest {
  private fun card(name: String) = """{"name":"$name","description":"人设"}"""

  private fun chunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use {
      it.writeInt(data.size)
      it.writeBytes(type)
      it.write(data)
      it.writeInt(CRC32().apply { update(type.toByteArray() + data) }.value.toInt())
    }
    return out.toByteArray()
  }

  private fun text(key: String, value: String): ByteArray =
    chunk("tEXt", ("$key\u0000" + Base64.getEncoder().encodeToString(value.toByteArray())).toByteArray())

  private fun png(vararg chunks: ByteArray): ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
      chunks.fold(ByteArray(0)) { a, b -> a + b } + chunk("IEND", ByteArray(0))

  private fun compressed(value: String): ByteArray {
    val out = ByteArrayOutputStream()
    DeflaterOutputStream(out).use { it.write(value.toByteArray()) }
    return chunk("iTXt", "ccv3\u0000\u0001\u0000\u0000\u0000".toByteArray() + out.toByteArray())
  }

  @Test
  fun v3WinsInBothPhysicalOrdersAndMixedTextTypes() {
    val old = text("chara", card("旧"))
    val modern = compressed(card("新"))
    listOf(png(old, modern), png(modern, old)).forEach {
      val result = CharacterImporter.fromBytes(it, "x")
      assertEquals("新", result.card.name)
      assertTrue(result.notes.any { note -> note.contains("ccv3") })
    }
  }

  @Test
  fun invalidV3FallsBackWithAnExplicitReport() {
    val result = CharacterImporter.fromBytes(png(text("ccv3", "{broken"), text("chara", card("旧"))), "x")
    assertEquals("旧", result.card.name)
    val malformed = CharacterImporter.fromBytes(png(text("ccv3", "{}"), text("chara", card("旧"))), "x")
    assertEquals("旧", malformed.card.name)
    assertTrue(malformed.card.compatibilityNotes().any { it.code == "png_fallback" })
    assertTrue(result.card.compatibilityNotes().any { it.code == "png_fallback" })
    assertThrows(CharacterImporter.ImportException::class.java) { CardInput.parse(png(text("ccv3", "bad"))) }
  }

  @Test
  fun compressedPayloadLargerThan64KiBIsReadCompletely() {
    val body = "长".repeat(90_000)
    val result = CharacterImporter.fromBytes(png(compressed("""{"description":"$body"}""")), "x")
    assertEquals(body, result.card.description)
  }

  @Test
  fun oversizedAndTruncatedInputsFailWithoutExposingContent() {
    assertThrows(CharacterImporter.ImportException::class.java) { CardInput.parse(ByteArray(CardInput.MAX_FILE + 1)) }
    assertThrows(CharacterImporter.ImportException::class.java) {
      CardInput.read(ByteArray(CardInput.MAX_FILE + 1).inputStream())
    }
    assertThrows(CharacterImporter.ImportException::class.java) {
      CardInput.parse(png(text("ccv3", card("私有"))).copyOf(20))
    }
    val error = assertThrows(CharacterImporter.ImportException::class.java) {
      CardInput.parse("{ PRIVATE_CONTENT".toByteArray())
    }
    assertFalse(error.message!!.contains("PRIVATE_CONTENT"))
    assertThrows(CharacterImporter.ImportException::class.java) {
      CardInput.parse(png(compressed("""{"description":"${"a".repeat(CardInput.MAX_JSON)}"}""")))
    }
  }
}

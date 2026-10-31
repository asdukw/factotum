package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldResolverTest {
  private val rule = WorldEntry(content = "synthetic shared content", constant = true)
  private val book = WorldBook("book", "rev", entries = listOf(WorldBookEntry("entry", rule = rule)))

  private fun card(id: String, content: String = "synthetic card content") = CharacterCard(
    id = id,
    name = id,
    world = listOf(rule.copy(content = content)),
  )

  @Test fun duplicateBookRunsOnceButRetainsEverySource() {
    val manifest = WorldManifest(
      "session",
      "manifest",
      listOf(
        WorldBookBinding("book", "rev", listOf("character:a"), epoch = "same"),
        WorldBookBinding("book", "rev", listOf("character:b", "session"), epoch = "same"),
      ),
    )
    val resolved = WorldResolver.resolve("session", listOf(card("a"), card("b")), manifest, listOf(book))
    assertEquals(3, resolved.size)
    assertEquals(listOf("character:a", "character:b", "session"), resolved.last().sources)
    assertEquals(3, resolved.map { it.key }.distinct().size)
  }

  @Test fun explicitDisableAndBookDisableBeatAllRoleSources() {
    val manifest = WorldManifest(
      "session",
      books = listOf(
        WorldBookBinding("book", "rev", listOf("character:a"), WorldOverride.ENABLE, "same"),
        WorldBookBinding("book", "rev", listOf("session"), WorldOverride.DISABLE, "same"),
      ),
    )
    assertFalse(WorldResolver.resolve("session", emptyList(), manifest, listOf(book)).single().enabled)
    val enabled = manifest.copy(books = manifest.books.take(1))
    assertFalse(WorldResolver.resolve("session", emptyList(), enabled, listOf(book.copy(enabled = false))).single().enabled)
  }

  @Test fun differentBooksWithSameContentRemainIndependentAndPinsAreImmutable() {
    val manifest = WorldManifest(
      "session",
      books = listOf(
        WorldBookBinding("book", "rev", epoch = "one"),
        WorldBookBinding("another", "rev", epoch = "two"),
      ),
    )
    val rows = WorldResolver.resolve("session", emptyList(), manifest, listOf(book, book.copy(id = "another")))
    assertEquals(2, rows.size)
    assertNotEquals(rows.first().key, rows.last().key)
    try {
      WorldResolver.resolve("session", emptyList(), manifest, listOf(book.copy(revision = "new")))
      error("missing pinned revision must fail")
    } catch (error: WorldBookException) {
      assertEquals("WORLD_PINNED_REVISION_MISSING", error.code)
    }
  }

  @Test fun legacyChatDoesNotAdoptRoleDefaultsAndLiveCardWorldUsesFullDigest() {
    val initial = WorldResolver.resolve("session", listOf(card("a", "x".repeat(80) + "a")), null, listOf(book))
    val edited = WorldResolver.resolve("session", listOf(card("a", "x".repeat(80) + "b")), null, listOf(book))
    assertEquals(1, initial.size)
    assertTrue(initial.single().bookId.startsWith("card:"))
    assertNotEquals(initial.single().key, edited.single().key)
  }

  @Test fun manifestCannotReadAnotherSession() {
    try {
      WorldResolver.resolve("session", emptyList(), WorldManifest("other"), emptyList())
      error("cross-session manifest must fail")
    } catch (error: WorldBookException) {
      assertEquals("WORLD_MANIFEST_SESSION_MISMATCH", error.code)
    }
  }
}

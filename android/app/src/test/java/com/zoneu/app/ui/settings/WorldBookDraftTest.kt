package com.zoneu.app.ui.settings

import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookEntry
import com.zoneu.app.agent.WorldBookException
import com.zoneu.app.agent.WorldEntry
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookDraftTest {
  private val original = WorldBook(
    id = "book-id",
    revision = "rev-1",
    name = "废弃车站",
    description = "站台介绍",
    tags = listOf("都市", "怪谈"),
    category = "场景",
    entries = listOf(
      WorldBookEntry(id = "entry-1", rule = WorldEntry(content = "三号站台", keys = listOf("站台", "月台"))),
    ),
  )

  @Test fun `untouched draft writes nothing`() {
    assertSame(original, WorldBookDraft(original).build())
  }

  @Test fun `draft applies only the changed header field`() {
    val built = WorldBookDraft(original, name = "废弃车站·改").build()
    assertEquals("废弃车站·改", built.name)
    assertEquals(original.description, built.description)
    assertEquals(original.tags, built.tags)
    assertEquals(original.category, built.category)
    assertEquals(original.enabled, built.enabled)
    assertEquals(original.entries, built.entries)
  }

  @Test fun `tags text field keeps one tag per line and drops blanks`() {
    val built = WorldBookDraft(original, tags = "甲\n\n  \n乙\n").build()
    assertEquals(listOf("甲", "乙"), built.tags)
  }

  @Test fun `enabled toggle is a header change`() {
    val built = WorldBookDraft(original, enabled = false).build()
    assertFalse(built.enabled)
    assertEquals(original.name, built.name)
  }

  @Test fun `entry form hides identity and joins array fields`() {
    val form = WorldEntryForm(original.entries.single())
    assertFalse("entry_id" in form.values)
    assertFalse("unsupported" in form.values)
    assertEquals("站台\n月台", form.values.getValue("keys"))
    assertEquals("三号站台", form.values.getValue("content"))
  }

  @Test fun `entry form without changes produces an empty patch`() {
    val form = WorldEntryForm(original.entries.single())
    assertTrue(form.patch().isEmpty())
    assertSame(original, worldEntryEdit(original, form, added = false))
  }

  @Test fun `entry form patches typed rule fields through the shared validator`() {
    val form = WorldEntryForm(original.entries.single())
      .changed("constant", "true")
      .changed("depth", "2")
      .changed("keys", "站台\n月台\n候车室")
    val built = worldEntryEdit(original, form, added = false)
    val rule = built.entries.single().rule
    assertTrue(rule.constant)
    assertEquals(2, rule.depth)
    assertEquals(listOf("站台", "月台", "候车室"), rule.keys)
    assertEquals("三号站台", rule.content)
  }

  @Test fun `invalid boolean text fails with a world code`() {
    val form = WorldEntryForm(original.entries.single()).changed("constant", "也许")
    val error = assertThrows(WorldBookException::class.java) { form.patch() }
    assertEquals("WORLD_INVALID_FIELDS", error.code)
  }

  @Test fun `adding an entry keeps every public field`() {
    val fresh = WorldBookEntry(id = "new-entry", rule = WorldEntry(content = "售票厅", keys = listOf("售票")))
    val form = WorldEntryForm(fresh).changed("content", "售票厅与候车室")
    val built = worldEntryEdit(original, form, added = true)
    assertEquals(2, built.entries.size)
    val added = built.entries.last()
    assertEquals("售票厅与候车室", added.rule.content)
    assertEquals(listOf("售票"), added.rule.keys)
  }

  @Test fun `entry form patch serializes arrays as json arrays`() {
    val patch = WorldEntryForm(original.entries.single()).changed("keys", "甲\n乙").patch()
    assertEquals(listOf("甲", "乙"), patch.getValue("keys").jsonArray.map { it.jsonPrimitive.content })
    assertFalse("content" in patch)
  }
}

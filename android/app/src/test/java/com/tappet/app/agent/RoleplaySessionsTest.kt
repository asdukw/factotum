package com.tappet.app.agent

import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.session.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆分组：每张卡都出现、收藏排前面、删掉的卡留个「已删除的角色」。
 *
 * 这几条都是侧栏能不能用的前提 —— 收藏之后如果没排到前面，用户会以为没收藏上。
 */
class RoleplaySessionsTest {
  private fun card(id: String, name: String = id) = CharacterCard(id = id, name = name)

  private fun session(id: String, characterId: String?, at: Long, model: String? = null) =
    ChatSession(
      id = id,
      title = id,
      kind = if (characterId == null) SessionKind.Chat else SessionKind.Roleplay,
      createdAt = at,
      updatedAt = at,
      characterId = characterId,
      model = model,
    )

  @Test
  fun everyCardAppearsEvenWithoutSessions() {
    val groups = groupRoleplaySessions(emptyList(), listOf(card("a"), card("b")))

    assertEquals(listOf("a", "b"), groups.map { it.characterId })
    assertTrue(groups.all { it.isEmpty })
  }

  @Test
  fun pinnedCardsComeFirstInPinnedOrder() {
    val groups =
      groupRoleplaySessions(
        sessions = emptyList(),
        cards = listOf(card("a"), card("b"), card("c")),
        pinned = listOf("c", "a"),
      )

    // 收藏按「最近收藏在前」，没收藏的保持库里原来的顺序（b 仍在最后）。
    assertEquals(listOf("c", "a", "b"), groups.map { it.characterId })
    assertEquals(listOf(true, true, false), groups.map { it.pinned })
  }

  @Test
  fun unpinnedKeepLibraryOrder() {
    val groups = groupRoleplaySessions(emptyList(), listOf(card("b"), card("a"), card("c")))

    assertEquals(listOf("b", "a", "c"), groups.map { it.characterId })
  }

  @Test
  fun orphanedSessionsSurviveCardDeletion() {
    val groups =
      groupRoleplaySessions(
        sessions = listOf(session("s1", "gone", 10), session("s2", "live", 20)),
        cards = listOf(card("live")),
      )

    assertEquals(2, groups.size)
    assertEquals("live", groups[0].characterId)
    assertEquals("已删除的角色", groups[1].name)
    assertEquals(listOf("s1"), groups[1].sessions.map { it.id })
  }

  @Test
  fun normalSessionsNeverEnterRoleplayGroups() {
    val groups = groupRoleplaySessions(listOf(session("chat", null, 10)), listOf(card("a")))

    assertTrue(groups.single().isEmpty)
  }

  @Test
  fun sessionsSortNewestFirst() {
    val groups =
      groupRoleplaySessions(
        sessions = listOf(session("old", "a", 10), session("new", "a", 30), session("mid", "a", 20)),
        cards = listOf(card("a")),
      )

    assertEquals(listOf("new", "mid", "old"), groups.single().sessions.map { it.id })
    assertEquals("new", groups.single().latest?.id)
  }

  @Test
  fun sessionRemembersItsModel() {
    val groups =
      groupRoleplaySessions(
        sessions = listOf(session("s", "a", 10, model = "deepseek-flash")),
        cards = listOf(card("a")),
      )

    val group = groups.single()
    assertEquals("deepseek-flash", group.latest?.model)
    assertEquals("a", group.latest?.characterId)
    assertFalse(group.isEmpty)
  }
}

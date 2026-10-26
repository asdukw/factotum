package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酒馆 prompt 编排的规则单测。
 *
 * 这里钉的是**位置**，不是措辞：深度注入、后置指令、重写时的历史形状，一旦错位，模型读到的
 * 就是另一段话，而在真机上只表现为「感觉怪怪的」，靠肉眼看不出来。
 */
class TavernPromptTest {
  private fun card(
    world: List<WorldEntry> = emptyList(),
    description: String = "你是校纪纠察队的副队长，金丝边眼镜，说话短。",
    personality: String = "清冷、克制，偶尔露出一点在意。",
    scenario: String = "夜里十一点的旧教学楼走廊。",
    firstMes: String = "……你也留下来了。",
  ): CharacterCard =
    CharacterCard(
      id = "test-card",
      name = "沈清弦",
      description = description,
      personality = personality,
      scenario = scenario,
      firstMes = firstMes,
      world = world,
    )

  private fun turns(vararg pairs: Pair<String, String>): List<TavernMessage> =
    pairs.map { (role, text) -> TavernMessage(role, text) }

  @Test
  fun firstMessageIsTheSystemBlockWithCardSections() {
    val messages = TavernPrompt.build(card(), memory = "", turns = turns("user" to "你好"))

    assertEquals(TavernMessage.SYSTEM, messages.first().role)
    val system = messages.first().content
    assertTrue(system.contains("沈清弦"))
    assertTrue(system.contains("【身份与外貌】"))
    assertTrue(system.contains("【性格与说话方式】"))
    assertTrue(system.contains("【当前场景】"))
    // 第一轮才带开场白：它只是「第一句怎么说」，不是设定
    assertTrue(system.contains("【开场白】"))
  }

  @Test
  fun openingLineDisappearsAfterFirstTurn() {
    val messages =
      TavernPrompt.build(
        card(),
        memory = "",
        turns = turns("user" to "你好", "assistant" to "嗯。", "user" to "还在吗"),
      )

    assertFalse(messages.first().content.contains("【开场白】"))
  }

  @Test
  fun memoryGoesIntoTheSystemBlock() {
    val messages = TavernPrompt.build(card(), memory = "沈清弦 答应过周三见面。", turns = turns("user" to "你好"))

    assertTrue(messages.first().content.contains("【你记得的事】"))
    assertTrue(messages.first().content.contains("答应过周三见面"))
  }

  @Test
  fun constantEntriesStayInSystemAndNeverCountAsDepthHits() {
    val c =
      card(
        world =
          listOf(
            WorldEntry(keys = emptyList(), content = "这座学校夜里十点熄灯。", constant = true),
            WorldEntry(keys = listOf("口令"), content = "今日口令是七号霜降。", depth = 4),
          ),
      )
    // 用户消息里同时命中「口令」，但常驻条目仍然只在系统块里出现一次
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "口令是什么"))

    assertEquals(1, messages.count { it.role == TavernMessage.SYSTEM && it.content.contains("这座学校夜里十点熄灯") })
    assertTrue(messages.first().content.contains("【世界设定·常驻】"))
  }

  @Test
  fun hitEntryIsInjectedAtDepthInsideTheHistory() {
    val c = card(world = listOf(WorldEntry(keys = listOf("口令"), content = "今日口令是七号霜降。", depth = 2)))
    val messages =
      TavernPrompt.build(
        c,
        memory = "",
        turns =
          turns(
            "user" to "今天好累",
            "assistant" to "……那先坐会儿。",
            "user" to "口令是什么",
          ),
      )

    // 深度 2 = 倒数第二条消息之前 —— 插在「那句用户话」之前，不是整段历史的最后
    val index = messages.indexOfFirst { it.content.contains("七号霜降") }
    assertTrue("命中条目应该被插进历史中间，实际位置 $index / ${messages.size}", index in 1..messages.size - 2)
    assertEquals(TavernMessage.SYSTEM, messages[index].role)
    assertTrue(messages[index].content.startsWith(LoreInject.MARK_OPEN))
    // 最后一条仍然是规则块（后置指令），用户那句话在它前面
    assertTrue(messages.last().content.contains("【扮演规则】"))
    assertEquals("口令是什么", messages[messages.size - 2].content)
  }

  @Test
  fun deeperEntriesLandEarlierThanShallowerOnes() {
    val c =
      card(
        world =
          listOf(
            WorldEntry(keys = listOf("雨"), content = "A-远处的雨", depth = 6),
            WorldEntry(keys = listOf("雨"), content = "B-近处的雨", depth = 1),
          ),
      )
    val messages =
      TavernPrompt.build(
        c,
        memory = "",
        turns =
          turns(
            "user" to "一",
            "assistant" to "二",
            "user" to "三",
            "assistant" to "四",
            "user" to "外面在下雨",
          ),
      )

    val far = messages.indexOfFirst { it.content.contains("A-远处的雨") }
    val near = messages.indexOfFirst { it.content.contains("B-近处的雨") }
    assertTrue("远的（depth 6）应排在近的（depth 1）前面：far=$far near=$near", far < near)
    // 末尾依次是「命中条目 → 这一轮用户话 → 规则块」，所以 depth 1 落在 size-3
    assertEquals("depth 1 应紧贴最末尾那条用户消息之前", messages.size - 3, near)
  }

  @Test
  fun keywordLookBackFindsTermsFromEarlierUserTurns() {
    val c = card(world = listOf(WorldEntry(keys = listOf("校徽"), content = "校徽是银色的。")))
    val messages =
      TavernPrompt.build(
        c,
        memory = "",
        turns =
          turns(
            "user" to "你校徽歪了",
            "assistant" to "……我扶一下。",
            "user" to "她呢",
          ),
      )

    assertTrue(messages.any { it.content.contains("校徽是银色的") })
  }

  /**
   * 真卡（cardbuilder 下载的 v3 卡）常把同义词塞进一个 key 字符串：`"第一学府 · 学府 · 纠察队"`。
   * 这正是当初在设备上「发了关键词却什么都没带」的原因 —— 整串 contains 永远不命中。
   */
  @Test
  fun synonymsPackedIntoOneKeyStillMatch() {
    val c = card(world = listOf(WorldEntry(keys = listOf("第一学府 · 学府 · 纠察队 · 校规"), content = "纠察队的制服是深黑的。")))
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "纠察队在哪"))

    assertTrue(messages.any { it.content.contains("纠察队的制服是深黑的") })
  }

  @Test
  fun singleLatinLettersAreNotKeysButSingleCjkCharactersAre() {
    // 单个拉丁字母会把几乎每句话都命中，不算关键词
    val latin = card(world = listOf(WorldEntry(keys = listOf("a"), content = "英文单字母不该命中。")))
    assertFalse(TavernPrompt.build(latin, "", turns("user" to "abc")).any { it.content.contains("英文单字母不该命中") })

    // 单个汉字是卡里常见的触发写法（「雨」），要认
    val cjk = card(world = listOf(WorldEntry(keys = listOf("雨"), content = "外面在下雨。")))
    assertTrue(TavernPrompt.build(cjk, "", turns("user" to "雨停了")).any { it.content.contains("外面在下雨") })
  }

  @Test
  fun unknownEntriesAreNotCarried() {
    val c = card(world = listOf(WorldEntry(keys = listOf("食堂"), content = "食堂八点关门。")))
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "你好"))

    assertFalse(messages.any { it.content.contains("食堂八点关门") })
  }

  @Test
  fun regenerateSeesHistoryWithoutTheDroppedReply() {
    // 调用方在重写时已经把上一轮助手回复从历史里剔掉；编排这一侧看到的就该是「这句还没被回答」
    val messages =
      TavernPrompt.build(
        card(),
        memory = "",
        turns = turns("user" to "你还记得吗", "assistant" to "记得。", "user" to "说说看"),
      )

    assertEquals("说说看", messages[messages.size - 2].content)
    assertEquals(TavernMessage.USER, messages[messages.size - 2].role)
  }

  @Test
  fun noRawDoubleBracesSurvive() {
    val c = card(description = "{{char}} 是{{user}}的同学，代号 {{random}}。")
    val messages = TavernPrompt.build(c, memory = "{{user}} 提过 {{char}}。", turns = turns("user" to "你好"))

    val all = messages.joinToString("\n") { it.content }
    assertFalse(all.contains("{{"))
    assertFalse(all.contains("}}"))
  }

  @Test
  fun entriesPlacedBeforeOrAfterTheCardGoIntoTheSystemBlockNotTheChat() {
    val c =
      card(
        world =
          listOf(
            WorldEntry(keys = listOf("校规"), content = "校规第一条：夜里不许留在教学楼。", position = WorldEntry.POSITION_BEFORE),
            WorldEntry(keys = listOf("校规"), content = "纠察队可以破例。", position = WorldEntry.POSITION_AFTER),
          ),
      )
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "校规是什么"))

    val system = messages.first().content
    assertTrue("人设之前的条目要进系统消息", system.contains("校规第一条"))
    assertTrue("人设之后的条目也要进系统消息", system.contains("纠察队可以破例"))
    // 而且只能出现一次：再按深度插一份就是重复注入
    assertEquals(1, messages.count { it.content.contains("校规第一条") })
    assertEquals(1, messages.count { it.content.contains("纠察队可以破例") })
  }

  @Test
  fun depthEntriesUseTheRoleFromTheCard() {
    val c =
      card(
        world =
          listOf(
            WorldEntry(keys = listOf("雨"), content = "外面在下雨。", role = WorldEntry.ROLE_USER),
            WorldEntry(keys = listOf("雨"), content = "雨声很轻。", role = WorldEntry.ROLE_ASSISTANT),
          ),
      )
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "下雨了吗"))

    assertTrue(messages.any { it.role == TavernMessage.USER && it.content.contains("外面在下雨") })
    assertTrue(messages.any { it.role == TavernMessage.ASSISTANT && it.content.contains("雨声很轻") })
  }

  @Test
  fun presetWithoutMarkersStillGetsTheBlocks() {
    val c =
      card(world = listOf(WorldEntry(keys = listOf("校规"), content = "校规第一条：夜里不许留在教学楼。", position = WorldEntry.POSITION_BEFORE)))
    val bare = TavernPreset(id = "bare", name = "无 marker", system = "你扮演{{char}}。", postHistory = "")
    val messages = TavernPrompt.build(c, memory = "", turns = turns("user" to "校规是什么"), preset = bare)

    assertTrue("模板没写 {{world_before}} 时不能静默丢掉这条设定", messages.first().content.contains("校规第一条"))
  }
}

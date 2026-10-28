package com.zoneu.app.agent

/**
 * 群聊：把几张卡拼成「一场戏」。
 *
 * 做法不是给 prompt 加一层新结构，而是合成一张**临时的卡**：人设按角色分段拼起来、
 * 世界书合并、名字写成「A / B」。这样编排（深度插入、预算、预设、宏、变量）一行都不用改，
 * 出问题的可能性就只有这一个文件。
 *
 * 代价也说清楚：合成卡的 `{{char}}` 是「A / B」，所以卡正文里写「{{char}}会……」的角色
 * 会变成「A / B 会……」。群聊里唯一的权威是 [rule] 那段名单，人设段落各自带【名字】标题，
 * 模型据此区分谁是谁。
 */
object GroupCast {
  /** 一局最多几个人。再多系统提示会被各自的设定挤爆，模型也开始张冠李戴。 */
  const val MAX = 4

  fun merge(cards: List<CharacterCard>): CharacterCard {
    val cast = cards.take(MAX)
    val head = cast.first()
    return head.copy(
      name = cast.joinToString(" / ") { it.name },
      tagline = "群聊",
      description = cast.joinToString("\n\n") { card -> "【${card.name}】\n${card.description.trim()}" },
      personality = cast.joinToString("\n\n") { card -> "【${card.name}】${card.personality.trim()}" },
      scenario = cast.joinToString("\n\n") { card -> "【${card.name}】${card.scenario.trim()}" },
      // 开场白在群聊里没有意义（谁先说？），交给用户第一句话定调
      firstMes = "",
      alternateGreetings = emptyList(),
      world = cast.flatMap { it.world },
    )
  }

  /**
   * 群聊规则，作为这一轮的最后一条指令。
   *
   * 三句话都在防同一类失败：模型把几个人写成一段对话（你一句我一句挤在同一条回复里）、
   * 或者用旁白代替说话、或者替用户说话。给名字前缀是唯一的归属手段 —— 我们自己不去解析
   * 「谁说的」，因为解析错会让下一轮的上下文全乱。
   */
  fun rule(cards: List<CharacterCard>): String {
    val names = cards.take(MAX).joinToString("、") { it.name }
    return "（群聊）当前在场：$names。这一轮**只让其中一个人说话**，用「名字：」开头，说完就停。" +
      "不要替别人接着说，不要替用户说话，也不要写「两人相视一笑」这种旁白式的多人动作。"
  }
}

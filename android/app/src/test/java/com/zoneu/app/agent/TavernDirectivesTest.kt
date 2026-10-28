package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** 「替我写」回来的那一行怎么收拾。模型不听话的方式就那么几种，逐个钉住。 */
class TavernDirectivesTest {
  @Test
  fun `strips wrapping ascii quotes`() {
    assertEquals("你回来了", TavernDirectives.asUserLine("\"你回来了\""))
  }

  @Test
  fun `strips wrapping curly quotes`() {
    assertEquals("你回来了", TavernDirectives.asUserLine("“你回来了”"))
  }

  @Test
  fun `strips a speaker prefix`() {
    assertEquals("我先去睡了", TavernDirectives.asUserLine("用户：我先去睡了"))
  }

  @Test
  fun `keeps quotes in the middle of the line`() {
    assertEquals("她说了句“晚安”就走了", TavernDirectives.asUserLine("她说了句“晚安”就走了"))
  }

  @Test
  fun `strips leading and trailing markdown emphasis`() {
    assertEquals("嗯", TavernDirectives.asUserLine("**嗯**"))
  }
}

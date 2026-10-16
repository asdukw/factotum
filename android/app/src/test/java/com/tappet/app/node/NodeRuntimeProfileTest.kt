package com.tappet.app.node

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `NodeRuntime.PROVIDER` 与 phone profile 里的 provider 键必须逐字相同。
 *
 * 对不上时 dsh 报的是 `no adapter registered for provider "xxx"` —— 看着像运行时缺插件，
 * 其实是改名时漏了一处。这条测试就是为那次漏改加的：真机上踩过一次（provider 从
 * `opencode-zen` 改成 `tappet` 时，profile 改了、这个常量没改）。
 *
 * 找不到 profile 就**跳过**而不是通过：源码包（非完整仓库检出）里可能没有 tools/。
 * 跳过在报告里看得见，不会伪装成绿。
 */
class NodeRuntimeProfileTest {
  private val profile = File("../../tools/dsh-phone/profile/cordis.patch.yml")

  @Test
  fun `provider id matches the profile provider key`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    assertTrue(
      "profile 里没有 provider 键「${NodeRuntime.PROVIDER}:」—— NodeRuntime.PROVIDER 与 profile 必须一起改",
      text.contains("${NodeRuntime.PROVIDER}:"),
    )
    assertTrue(
      "profile 的 agent-default-model.provider 不是「${NodeRuntime.PROVIDER}」——" +
        "provider 键与默认模型指向的 provider 必须同名",
      text.contains("provider: ${NodeRuntime.PROVIDER}"),
    )
  }
}

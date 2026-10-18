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
 * 同一个文件里还钉住**模型清单是动态注入的**：路由键 `tappet` 不是 pi-ai 目录里的 provider，
 * 静态列表之外的 id 会以 UNKNOWN_MODEL 失败。清单由 NodeRuntime 按当前选中的模型写进
 * `TAPPET_LLM_MODELS`，profile 里只留一条 fallback。
 *
 * 找不到 profile 就**跳过**而不是通过：源码包（非完整检出）里可能没有 tools/。
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

  @Test
  fun `the gmail connector is wired the same in all three places`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    // 插件的名字要在**三处**逐字相同：profile 的 insert、装配脚本的 Package、插件自己的
    // package.json。对不上时的表现是运行时少一组工具（或整棵 cordis 根 mount 失败），
    // 而报错里只有一句「找不到模块」—— 与 PROVIDER 那次漏改同一类，所以同样钉住。
    val name = "dsh-plugin-gmail"
    assertTrue("profile 里没有插入 $name", profile.readText().contains("name: '$name'"))

    val bundler = File("../../tools/bundle-dsh.ps1")
    assumeTrue("仓库里没有装配脚本（不是完整检出），跳过", bundler.isFile)
    assertTrue("bundle-dsh.ps1 的插件清单里没有 $name", bundler.readText().contains(name))

    val manifest = File("../../tools/dsh-phone/plugins/gmail/package.json")
    assertTrue("插件源码缺失：$manifest", manifest.isFile)
    assertTrue(
      "插件的 package.json 里 name 不是 $name",
      manifest.readText().contains("\"name\": \"$name\""),
    )
  }

  @Test
  fun `profile reads the dynamic model list injected by the host`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    assertTrue(
      "profile 的 models 必须读 TAPPET_LLM_MODELS（NodeRuntime 按选中模型注入）；" +
        "静态列表无法覆盖自定义提供商与手填模型",
      text.contains("TAPPET_LLM_MODELS"),
    )
    assertTrue(
      "profile 的 models fallback 缺默认模型 —— PC 侧校验与无 env 的直接运行会没有模型可用",
      text.contains("deepseek-v4.1-flash"),
    )
  }
}

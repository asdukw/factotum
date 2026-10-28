package com.zoneu.app.node

import com.zoneu.app.agent.LlmPreset
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `NodeRuntime.PROVIDER` 与 phone profile 里的 provider 键必须逐字相同。
 *
 * 对不上时 dsh 报的是 `no adapter registered for provider "xxx"` —— 看着像运行时缺插件，
 * 其实是改名时漏了一处。这条测试就是为那次漏改加的：真机上踩过一次（provider 从
 * `opencode-zen` 改成 `zoneu` 时，profile 改了、这个常量没改）。
 *
 * 同一个文件里还钉住**模型清单是动态注入的**：路由键 `zoneu` 不是 pi-ai 目录里的 provider，
 * 静态列表之外的 id 会以 UNKNOWN_MODEL 失败。清单由 NodeRuntime 按当前选中的模型写进
 * `ZONEU_LLM_MODELS`，profile 里只留一条 fallback。
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
  fun `the approval seam is wired in all three places and the policy is ask`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    // dsh-base 按 DSH_PERMISSION_MODE 把策略读成 never（danger-full-access），所以手机必须在
    // profile 里显式覆盖回 ask —— 否则所有写操作的 `ask` 都会被确定性拒绝，确认面板永远不出现。
    assertTrue(
      "profile 没有把 approval.policy 覆盖成 ask",
      text.contains("- id: approval\n  config:\n    policy: ask"),
    )

    val name = "dsh-plugin-approval"
    assertTrue("profile 里没有插入 $name", text.contains("name: '$name'"))

    val bundler = File("../../tools/bundle-dsh.ps1")
    assumeTrue("仓库里没有装配脚本（不是完整检出），跳过", bundler.isFile)
    assertTrue("bundle-dsh.ps1 的插件清单里没有 $name", bundler.readText().contains(name))

    val manifest = File("../../tools/dsh-phone/plugins/approval/package.json")
    assertTrue("插件源码缺失：$manifest", manifest.isFile)
    assertTrue(
      "插件的 package.json 里 name 不是 $name",
      manifest.readText().contains("\"name\": \"$name\""),
    )
  }

  @Test
  fun `the built-in web search provider is wired and reads the injected endpoint`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    // 联网搜索用的是 dsh 自带的 `web-search-deepseek`（Anthropic 兼容 Messages + 原生
    // web_search 服务器工具），不是本仓库另写的插件。它一旦被禁用，`web_search` 工具还会
    // 挂在模型面前，但每次调用都回 WEB_PROVIDER_UNAVAILABLE —— 看着像「搜索坏了」。
    assertTrue(
      "profile 不允许再禁用 web-search-deepseek（联网搜索走的是它）",
      !text.contains("- id: web-search-deepseek\n  disabled: true"),
    )
    assertTrue(
      "profile 里没有 web-search-deepseek 的配置段",
      text.contains("- id: web-search-deepseek\n  config:"),
    )
    // 端点 / 模型 / 密钥来源都必须由宿主注入：搜索与聊天是两套协议，不能从 ZONEU_LLM_API 推。
    for (name in listOf("ZONEU_WEB_SEARCH_BASE_URL", "ZONEU_WEB_SEARCH_MODEL", "ZONEU_WEB_SEARCH_KEY_ENV")) {
      assertTrue("profile 的搜索 provider 没读 $name", text.contains(name))
    }

    // **工具行必须在宿主层重新启用**：dsh-web-app（本 profile 的第二个 bundle）把 dsh-base 的
    // 宿主级 tool-web 行禁用了，改成「按 agent preset 组合」。照它的样子放进 preset-phone 的
    // plugins 里**不行** —— 组合结果里有那一行，但工具进不了模型的工具表，而且日志里一个错都
    // 没有（2026-10-18 真机踩过）。所以在宿主层 `disabled: false` 重新启用。
    assertTrue(
      "profile 没有在宿主层重新启用 tool-web —— web_search / web_fetch 不会出现在模型面前",
      text.contains("- id: tool-web\n  disabled: false"),
    )
  }

  @Test
  fun `the user-question answerer is wired the same in all three places`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    // 与 gmail 那条同一类：插件名要在 profile 的 insert、装配脚本的 Package、插件自己的
    // package.json 三处逐字相同。对不上时运行时少一组能力，报错只有一句「找不到模块」。
    //
    // 额外钉一条**这个插件特有的**约束：它必须能排在 `dsh-api-remotes` 前面（那个包会把
    // `user-questions/request` 推进没人消费的 remote 队列，请求就永远不落地）。注册顺序在
    // 插件里靠 `{ prepend: true }`，这里只能钉「profile 里有这一行」—— 真正的顺序由
    // 插件的单测守（tools/dsh-phone/plugins/user-questions/test/smoke.mjs）。
    val name = "dsh-plugin-user-questions"
    assertTrue("profile 里没有插入 $name", profile.readText().contains("name: '$name'"))
    assertTrue(
      "profile 没有启用 tool-ask-user —— 没有它模型根本不会提问（answerer 也就用不上）",
      profile.readText().contains("name: '@deepseek-ai/dsh-tool-ask-user'"),
    )

    val bundler = File("../../tools/bundle-dsh.ps1")
    assumeTrue("仓库里没有装配脚本（不是完整检出），跳过", bundler.isFile)
    assertTrue("bundle-dsh.ps1 的插件清单里没有 $name", bundler.readText().contains(name))

    val manifest = File("../../tools/dsh-phone/plugins/user-questions/package.json")
    assertTrue("插件源码缺失：$manifest", manifest.isFile)
    assertTrue(
      "插件的 package.json 里 name 不是 $name",
      manifest.readText().contains("\"name\": \"$name\""),
    )
  }

  @Test
  fun `long-term memory is wired`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    // dsh 没有独立的「记忆」插件：长期记忆 = 指令文件（AGENTS.md 那一套）。
    // 两条行缺一不可 —— agent-instructions 负责**读**进上下文，tool-fs 负责让模型**写**。
    // 两条都同样被 dsh-web-app 在宿主层禁掉，且都必须在宿主层重新启用（放进 preset 不管用）。
    assertTrue(
      "profile 没有在宿主层启用 agent-instructions —— 记忆文件读不进上下文",
      text.contains("- id: agent-instructions\n  disabled: false"),
    )
    assertTrue(
      "profile 没有启用 tool-fs —— 模型没有写记忆文件的手段",
      text.contains("- id: tool-fs\n  disabled: false"),
    )
    assertTrue(
      "agent-instructions 必须有 maxBytes（默认没有兜底值，缺了整棵根 mount 不起来）",
      text.contains("maxBytes:"),
    )
  }

  @Test
  fun `search endpoints are well formed`() {
    // 基址不含 /messages：dsh 的搜索 provider 自己追加。写成带 /messages 的基址会得到
    // `.../messages/messages`，报出来是一个 404，看不出是配置问题。
    for (preset in LlmPreset.entries) {
      val search = preset.endpoint.search ?: continue
      assertTrue("${preset.id} 的搜索基址为空", search.baseUrl.isNotBlank())
      assertTrue("${preset.id} 的搜索模型为空", search.model.isNotBlank())
      assertTrue(
        "${preset.id} 的搜索基址不该以 /messages 结尾（provider 会自己追加）",
        !search.baseUrl.trimEnd('/').endsWith("/messages"),
      )
    }
    // 真机上唯一验证过会真的搜索的组合。它被改掉时这条会红 —— 想换就先去实测再改这里。
    val zen = LlmPreset.OpenCodeGo.endpoint.search
    assertTrue("OpenCode Go 必须配好搜索端点（已验证）", zen != null)
    assertTrue("OpenCode Go 的搜索端点应指向 zen", zen!!.baseUrl.contains("opencode.ai"))
  }

  @Test
  fun `profile reads the dynamic model list injected by the host`() {
    assumeTrue("仓库里没有 profile 文件（不是完整检出），跳过", profile.isFile)
    val text = profile.readText()

    assertTrue(
      "profile 的 models 必须读 ZONEU_LLM_MODELS（NodeRuntime 按选中模型注入）；" +
        "静态列表无法覆盖自定义提供商与手填模型",
      text.contains("ZONEU_LLM_MODELS"),
    )
    assertTrue(
      "profile 的 models fallback 缺默认模型 —— PC 侧校验与无 env 的直接运行会没有模型可用",
      text.contains("deepseek-v4.1-flash"),
    )
  }
}

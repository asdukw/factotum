package com.tappet.app.agent

/**
 * 极简模板渲染：`{{变量}}` 替换 + `{{#变量}}…{{/变量}}` 条件块。
 *
 * 为什么不引一个模板引擎：这套东西的输入是用户手写的几百字，需求只有「填值」与「空就整段不要」两件事。
 * 多一层依赖换来的语法（循环、过滤器）在 prompt 里没有用处，而模板引擎的转义规则会与卡里的
 * `{{char}}` 打架 —— 卡正文本来就带这种花括号。
 *
 * 规则：
 * - `{{name}}` → 变量值；没定义的变量原样保留（用户写错了能看见，不会被悄悄吞掉）
 * - `{{#name}}…{{/name}}` → 变量非空才保留块内容；未定义的变量按空处理（块不出现）
 * - 条件块可嵌套；同一模板里多处引用同一变量都生效
 * - 渲染完之后**剩余的花括号转全角**：卡正文里常带 `{{random}}` 这类我们没实现的宏，
 *   留着会让模型把它当指令去演，全角至少读起来是引文（与 [CharacterStore.renderText] 同一条规矩）
 */
internal object TavernTemplate {
  // 右花括号也要转义：Android 用的是 ICU 正则，裸 `}` 会直接 PatternSyntaxException
  // （JVM 单测能过、真机一进页面就崩 —— 这类差异只有装到设备上才看得见）。
  private val VARIABLE = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""")
  private val BLOCK =
    Regex("""\{\{#\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}(.*?)\{\{/\s*\1\s*\}\}""", RegexOption.DOT_MATCHES_ALL)

  fun render(template: String, values: Map<String, String>): String {
    val expanded = expandBlocks(template, values, depth = 0)
    val substituted = VARIABLE.replace(expanded) { match -> values[match.groupValues[1]] ?: match.value }
    return substituted.replace("{{", "｛｛").replace("}}", "｝｝")
  }

  /** 条件块由内向外展开：先消掉最里层，直到没有可匹配的。 */
  private fun expandBlocks(template: String, values: Map<String, String>, depth: Int): String {
    if (depth > MAX_DEPTH || !template.contains("{{#")) return template
    var changed = false
    val next =
      BLOCK.replace(template) { match ->
        changed = true
        val key = match.groupValues[1]
        val body = match.groupValues[2]
        val value = values[key]
        if (value.isNullOrBlank()) "" else expandBlocks(body, values, depth + 1)
      }
    return if (changed) expandBlocks(next, values, depth + 1) else next
  }

  /** 条件块最多展开这么多层。用户把块写歪了（漏了 `{{/x}}`）时不能让它把主线程转死。 */
  private const val MAX_DEPTH = 8
}

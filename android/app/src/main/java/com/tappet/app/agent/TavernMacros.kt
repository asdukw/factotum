package com.tappet.app.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 会话级的宏变量（对应 ST 的 chat 作用域）。
 *
 * 为什么可变而不是纯 map：`{{setvar}}` 的语义就是「读一个、写一个、按出现顺序生效」——
 * 中途有个地方写进去了，后面同一段文本里的 `{{getvar}}` 就要拿到新值。用一个可变表按左到右
 * 扫一遍最贴近作者的预期，也最不容易出反直觉的结果。
 *
 * [dirty] 给调用方判「这一轮有没有写操作」，没写就不必回写 SharedPreferences。
 */
class TavernVars(initial: Map<String, String> = emptyMap()) {
  private val values = LinkedHashMap(initial)

  var dirty = false
    private set

  fun get(name: String): String? = values[name]

  fun set(name: String, value: String) {
    if (values[name] == value) return
    values[name] = value
    dirty = true
  }

  /** 数值加减；非数字按 0 处理（作者把它当计数器用，字符串内容没有意义）。 */
  fun add(name: String, delta: Int) = set(name, ((values[name]?.toIntOrNull() ?: 0) + delta).toString())

  fun snapshot(): Map<String, String> = LinkedHashMap(values)

  /** 界面上展示用：按写入顺序，方便作者与用户对上号。 */
  fun entries(): List<Pair<String, String>> = values.map { it.key to it.value }
}

/**
 * 卡与预设里那套 `{{...}}` 宏。
 *
 * 支持的是 ST 里**真的会改变输出**的那几个：随机、掷骰、时间、变量读写、换行。
 * 不做 `{{getglobalvar}}` / `{{banned}}` / 正则替换这类依赖 ST 全局状态的宏 —— 我们的作用域
 * 只有「这个会话」，写不了的宏宁可原样留着（会被 [TavernTemplate] 转成全角），也不假装支持。
 *
 * 顺序规则：**左到右一次扫描**。这样 `{{setvar::mood::开心}}{{getvar::mood}}` 得到「开心」，
 * 与作者在卡里从上往下读的顺序一致。扫描完再跑一轮，处理「变量值本身是宏」的情况（最多 [ROUNDS] 轮）。
 */
object TavernMacros {
  /**
   * 单个宏：`{{名字}}`、`{{名字::参数::参数}}`，以及 ST 里那种只用一个冒号的 `{{roll:1d6}}`。
   *
   * 三种写法都得认：卡作者按 ST 文档写单冒号，我们自己的旧文档与部分卡写双冒号。
   * 只认一种的后果是「掷骰永远不出数字」，而模型会照样把 `{{roll:2d6}}` 当正文演下去。
   */
  private val MACRO = Regex("""\{\{([a-zA-Z_][a-zA-Z0-9_]*)(:[^{}]*)?\}\}""")

  /** 掷骰：`1d6`、`2d10+3`、`d20`。 */
  private val DICE = Regex("""^\s*(\d{1,2})?[dD](\d{1,4})\s*([+-]\s*\d{1,3})?\s*$""")

  /** 变量值里还能再展开几轮。防的是 `{{setvar::a::{{getvar::a}}` 这种自己套自己。 */
  private const val ROUNDS = 3

  /**
   * 渲染一段文本里的宏。
   *
   * [now] 与 [random] 可以注入：单测要钉住 `{{time}}` 与 `{{random::x::y}}` 的输出，
   * 而真实调用用默认值。**纯函数**（除了写进 [vars]），不碰 Context。
   */
  fun render(text: String, vars: TavernVars, now: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
    if (!text.contains("{{")) return text
    var current = text
    repeat(ROUNDS) {
      val next = MACRO.replace(current) { match -> resolve(match.groupValues[1], args(match.groupValues[2]), vars, now, random) }
      if (next == current) return next
      current = next
    }
    return current
  }

  /**
   * `::a::b::c` → `[a, b, c]`；`roll:1d6` 这种单冒号 → `[1d6]`。
   *
   * 先剥掉开头的分隔符（`::` 或 `:`），再按 `::` 切：这样 `{{random:晴::雨}}` 这种混写
   * 也能得到两个参数，而不是把第二个参数连冒号一起塞进第一个。
   */
  private fun args(raw: String): List<String> {
    if (raw.isEmpty()) return emptyList()
    val body = if (raw.startsWith("::")) raw.substring(2) else raw.substring(1)
    return body.split("::")
  }

  /**
   * 认不出的宏原样返回：调用方（[TavernTemplate]）最后会把残留的花括号转全角。
   * 静默吞掉的话，作者永远不知道自己写的宏没生效。
   */
  private fun resolve(name: String, args: List<String>, vars: TavernVars, now: Long, random: Random): String {
    // 先问变量类宏：它们是「写操作」，与「生成一段文本」是两件事，混在一个 when 里
    // 既超复杂度阈值，也让人以为 `setvar` 会往正文里放东西。
    variableMacro(name, args, vars)?.let { return it }
    return when (name.lowercase()) {
      "random" -> random(args, random)
      "pick" -> args.filter { it.isNotBlank() }.let { pool -> if (pool.isEmpty()) "0" else pool[random.nextInt(pool.size)] }
      "roll" -> roll(args.firstOrNull().orEmpty(), random)
      "time" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
      "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
      "newline" -> "\n"
      else -> "{{$name${args.joinToString("") { "::$it" }}}}"
    }
  }

  /** 变量类宏；不是变量宏时返回 null（交给 [resolve] 继续判断）。 */
  private fun variableMacro(name: String, args: List<String>, vars: TavernVars): String? =
    when (name.lowercase()) {
      "setvar" -> writeSet(args, vars)
      "addvar" -> writeAdd(args, vars)
      "incvar" -> writeInc(args, vars, 1)
      "decvar" -> writeInc(args, vars, -1)
      "getvar" -> args.firstOrNull()?.let { vars.get(it) }.orEmpty()
      else -> null
    }

  /**
   * `{{random}}` 不带参数时是 0–1 的小数（ST 的行为）；
   * 带参数时等概率取一个 —— 两个参数时也**按份数取**，不做区间随机：卡里写的
   * `{{random::a::b}}` 意思是「二选一」，不是「a 到 b 之间随便取个值」。
   */
  private fun random(args: List<String>, random: Random): String {
    val pool = args.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
    return when {
      pool.isEmpty() -> String.format(Locale.US, "%.2f", random.nextDouble())
      else -> pool[random.nextInt(pool.size)]
    }
  }

  /** 掷骰；格式不对就当 0（作者的 `{{roll:心情}}` 之类不该把整段 prompt 弄崩）。 */
  private fun roll(spec: String, random: Random): String {
    val match = DICE.find(spec) ?: return "0"
    val count = (match.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 20)
    val sides = match.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: 6
    val bonus = match.groupValues[3].replace(" ", "").toIntOrNull() ?: 0
    val sum = (1..count).sumOf { random.nextInt(1, sides + 1) } + bonus
    return sum.toString()
  }

  /** `{{setvar::名字::值}}` 渲染成空串：它是写操作，不该在正文里留下痕迹。 */
  private fun writeSet(args: List<String>, vars: TavernVars): String {
    val name = args.getOrNull(0)?.trim().orEmpty()
    if (name.isNotEmpty()) vars.set(name, args.drop(1).joinToString("::"))
    return ""
  }

  private fun writeAdd(args: List<String>, vars: TavernVars): String {
    val name = args.getOrNull(0)?.trim().orEmpty()
    if (name.isEmpty()) return ""
    vars.add(name, args.getOrNull(1)?.trim()?.toIntOrNull() ?: 1)
    return ""
  }

  private fun writeInc(args: List<String>, vars: TavernVars, step: Int): String {
    val name = args.firstOrNull()?.trim().orEmpty()
    if (name.isNotEmpty()) vars.add(name, step)
    return ""
  }
}

package com.tappet.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 世界书的递归扫描：层数、以及两条掐断开关。
 *
 * 用一条链来钉：学院 → 校规 → 夜游。三层都提得到，才能分清「一层停」与「继续追」；
 * 只写两条的话，`depth = 2` 和 `depth = 1` 会得出同一结果，测试就白写了。
 */
class WorldScanRecursionTest {
  private val academy = entry(listOf("学院"), "学院里有校规。", order = 1)
  private val rules = entry(listOf("校规"), "校规第一条：不许夜游。", order = 2)
  private val nightOut = entry(listOf("夜游"), "夜游会被退学。", order = 3)

  private fun entry(
    keys: List<String>,
    content: String,
    order: Int = 0,
    prevent: Boolean = false,
    exclude: Boolean = false,
  ) = WorldEntry(
    keys = keys,
    content = content,
    order = order,
    preventRecursion = prevent,
    excludeRecursion = exclude,
  )

  private fun scan(vararg entries: WorldEntry, depth: Int = 1, text: String = "说说学院"): List<String> =
    WorldScan.select(entries.toList(), text, Random(1), limits = WorldScan.Limits(depth = depth)).map { it.content }

  @Test
  fun `depth zero keeps only the direct hit`() {
    assertEquals(listOf(academy.content), scan(academy, rules, nightOut, depth = 0))
  }

  @Test
  fun `one layer is the default and stops there`() {
    assertEquals(listOf(academy.content, rules.content), scan(academy, rules, nightOut, depth = 1))
    assertEquals("不传也应该是一层", listOf(academy.content, rules.content), scan(academy, rules, nightOut))
  }

  @Test
  fun `two layers follows the chain one step further`() {
    assertEquals(listOf(academy.content, rules.content, nightOut.content), scan(academy, rules, nightOut, depth = 2))
  }

  @Test
  fun `prevent recursion stops an entry from pulling others in`() {
    val silent = academy.copy(preventRecursion = true)
    assertEquals("它自己还在，但牵不出校规", listOf(silent.content), scan(silent, rules, nightOut, depth = 2))
  }

  @Test
  fun `exclude recursion keeps an entry out of the chain`() {
    val guarded = rules.copy(excludeRecursion = true)
    assertEquals("校规被保护，链条断在这里", listOf(academy.content), scan(academy, guarded, nightOut, depth = 2))
  }

  @Test
  fun `a prevented entry also stops deeper rounds`() {
    // 校规被带进来之后不再往外牵：夜游不该出现
    val quiet = rules.copy(preventRecursion = true)
    assertEquals(listOf(academy.content, quiet.content), scan(academy, quiet, nightOut, depth = 3))
  }

  @Test
  fun `the excluded word still keeps an entry eligible by keyword`() {
    // excludeRecursion 只挡「被牵着来」，用户自己提到它时照样出现
    val guarded = rules.copy(excludeRecursion = true)
    assertEquals(listOf(guarded.content), scan(guarded, depth = 2, text = "校规怎么写的"))
  }

  @Test
  fun `entries are never repeated across rounds`() {
    // 三条互相提：学院↔校规、校规↔夜游、夜游又提到学院
    val looped = listOf(academy, rules, nightOut.copy(content = "夜游会被退学，学院有记录。"))
    val contents =
      WorldScan.select(looped, "说说学院", Random(1), limits = WorldScan.Limits(depth = 3)).map { it.content }
    assertEquals(contents.size, contents.toSet().size)
  }
}

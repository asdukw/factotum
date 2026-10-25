package com.tappet.app.agent

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 预设的存取。
 *
 * 与 [CharacterStore] 同一套规矩：内置的那一套在代码里（[TavernPreset.DEFAULT]），用户的写在
 * `filesDir/tavern/presets/<id>.json` —— 跟着 App 走、卸载即清，不进 git。
 *
 * 「当前用哪套」是一个 id，存在普通 prefs 里：它不是密钥，也不需要和文件同一次写成功。
 * 选中一套已删掉的预设时退回默认 —— 不能让界面显示「选中」而实际用的是别的。
 */
object TavernPresetStore {
  private const val PREFS = "tappet_tavern_presets"
  private const val KEY_SELECTED = "selected_id"
  private const val DIR = "tavern/presets"

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** 全部可选预设：内置的排最前，其余按名字。单个文件坏掉只丢那一套，不连累别的。 */
  fun presets(context: Context): List<TavernPreset> {
    val custom =
      runCatching {
        dir(context)
          .listFiles { file -> file.isFile && file.name.endsWith(".json") }
          .orEmpty()
          .mapNotNull { file -> runCatching { json.decodeFromString<TavernPreset>(file.readText()) }.getOrNull() }
          .sortedBy { it.name }
      }
        .getOrDefault(emptyList())
    return listOf(TavernPreset.DEFAULT) + custom
  }

  fun selectedId(context: Context): String = prefs(context).getString(KEY_SELECTED, null) ?: TavernPreset.DEFAULT_ID

  fun selected(context: Context): TavernPreset =
    presets(context).firstOrNull { it.id == selectedId(context) } ?: TavernPreset.DEFAULT

  fun select(context: Context, id: String) {
    prefs(context).edit().putString(KEY_SELECTED, id).apply()
  }

  /** 存一套（新建或覆盖）。内置的那套不落盘：它由代码决定，改了要能靠「恢复默认」回来。 */
  fun save(context: Context, preset: TavernPreset): Boolean {
    if (preset.builtIn) return false
    return runCatching {
      File(dir(context), "${preset.id}.json").writeText(json.encodeToString(TavernPreset.serializer(), preset))
      true
    }
      .getOrDefault(false)
  }

  fun delete(context: Context, id: String): Boolean {
    if (id == TavernPreset.DEFAULT_ID) return false
    val removed = runCatching { File(dir(context), "$id.json").delete() }.getOrDefault(false)
    // 删掉的正好是选中的那套：退回默认，否则界面写着选中而实际用的是默认。
    if (removed && selectedId(context) == id) select(context, TavernPreset.DEFAULT_ID)
    return removed
  }

  /** 新预设 id。带时间戳便于在文件系统里辨认，也避免同一毫秒内复制两次撞车。 */
  fun newId(): String = "custom.${System.currentTimeMillis()}"
}

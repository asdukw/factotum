package com.zoneu.app.agent

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
  private const val PREFS = "zoneu_tavern_presets"
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

  /**
   * 某一轮该用哪套：按卡指定的优先，没指定就跟全局。
   *
   * 为什么要有这一层：一套「冷酷军装」的写法和一套「轻小说吐槽」的写法，往往不是同一张卡的
   * 需求。全局一份预设意味着每换一张卡都要手动切一次，而忘了切就会得到一张口吻不对的卡 ——
   * 那种错误用户只会归因成「模型不行」。绑定的预设被删掉时退回全局，绝不留下一个悬空 id。
   */
  fun forCard(context: Context, cardId: String?): TavernPreset {
    val bound = cardId?.let { id -> prefs(context).getString(cardKey(id), null) } ?: return selected(context)
    return presets(context).firstOrNull { it.id == bound } ?: selected(context)
  }

  /** Read the selected/bound preset without creating the preset directory or writing state. */
  fun peekForCard(context: Context, cardId: String?): TavernPreset {
    val selectedId = prefs(context).getString(KEY_SELECTED, null) ?: TavernPreset.DEFAULT_ID
    val bound = cardId?.let { prefs(context).getString(cardKey(it), null) }
    val files = File(context.filesDir, DIR).listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
    val custom = files.mapNotNull { file -> runCatching { json.decodeFromString<TavernPreset>(file.readText()) }.getOrNull() }
    val available = listOf(TavernPreset.DEFAULT) + custom
    val selected = available.firstOrNull { it.id == selectedId } ?: TavernPreset.DEFAULT
    return if (bound == null) selected else available.firstOrNull { it.id == bound } ?: selected
  }

  /** 这张卡绑了哪套；null = 跟随全局。 */
  fun boundId(context: Context, cardId: String): String? = prefs(context).getString(cardKey(cardId), null)

  /** 绑定（[presetId] 为 null 即解绑，回到跟随全局）。 */
  fun bindCard(context: Context, cardId: String, presetId: String?) {
    val editor = prefs(context).edit()
    if (presetId == null) editor.remove(cardKey(cardId)) else editor.putString(cardKey(cardId), presetId)
    editor.apply()
  }

  private fun cardKey(cardId: String) = "card:$cardId"

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

package com.zoneu.app.agent

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * 角色卡的收藏（置顶）列表。
 *
 * 用 SharedPreferences 存一份 id 数组，而不是新开一张 Room 表：这张表只有
 * 「id + 顺序」两列，为它再走一次数据库迁移（v3→v4）风险高、收益低；而集合读写
 * 本来就是偏好级别的操作。顺序即置顶顺序，最近收藏的排前面。
 */
object CharacterPins {
  private const val PREFS = "roleplay_pins"
  private const val KEY = "pinned_ids"

  fun pinned(context: Context): List<String> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }
      .getOrDefault(emptyList())
  }

  fun isPinned(context: Context, id: String): Boolean = id in pinned(context)

  /** 收藏 / 取消收藏，返回操作后的状态（true = 现在是收藏）。 */
  fun toggle(context: Context, id: String): Boolean {
    val current = pinned(context)
    val next = if (id in current) current - id else listOf(id) + current
    prefs(context).edit { putString(KEY, JSONArray(next).toString()) }
    return id in next
  }

  /** 卡被删掉时顺手清掉收藏，否则侧栏会一直挂一个不存在的 id。 */
  fun forget(context: Context, id: String) {
    val current = pinned(context)
    if (id !in current) return
    prefs(context).edit { putString(KEY, JSONArray(current - id).toString()) }
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

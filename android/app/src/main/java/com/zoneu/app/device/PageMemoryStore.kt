package com.zoneu.app.device

import android.content.Context
import androidx.core.content.edit

/**
 * 页面记忆的落盘层。
 *
 * 只存三样东西：结构指纹、访问次数、**上一次成功动作的类型与坐标**。
 * 页面正文（文字 / 描述 / 截图）一律不进来 —— 这是 `docs/android-accessibility.md` §2 的隐私边界，
 * 也是这层敢落盘的原因：即便文件被读走，也还原不出屏幕内容。
 *
 * 跨会话保留是刻意的：页面缓存的价值就在「上次怎么进的这页」，
 * 只活在内存里的话，重启一次就退化成没有。
 */
class PageMemoryStore(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun get(fingerprint: String): PageVisit? {
    val raw = prefs.getString(fingerprint, null) ?: return null
    val parts = raw.split('|', limit = 3)
    if (parts.size != 3) return null
    val visits = parts[0].toIntOrNull() ?: return null
    val lastSeen = parts[2].toLongOrNull() ?: return null
    return PageVisit(visits, parts[1].takeIf { it.isNotBlank() }, lastSeen)
  }

  /** 记一次访问，返回**含本次**的访问记录。 */
  fun visit(fingerprint: String, now: Long = System.currentTimeMillis()): PageVisit {
    val previous = get(fingerprint)
    val visit = PageVisit((previous?.visits ?: 0) + 1, previous?.lastAction, now)
    put(fingerprint, visit)
    return visit
  }

  /** 记一次成功动作。调用方保证 [action] 里没有正文（只有类型与坐标）。 */
  fun recordAction(fingerprint: String, action: String) {
    val previous = get(fingerprint) ?: PageVisit(0, null, System.currentTimeMillis())
    put(fingerprint, previous.copy(lastAction = action))
  }

  private fun put(fingerprint: String, visit: PageVisit) {
    val value = "${visit.visits}|${visit.lastAction.orEmpty()}|${visit.lastSeen}"
    prefs.edit { putString(fingerprint, value) }
    evictIfNeeded()
  }

  /** 缓存无上限会让偏好文件随使用无限长；页面结构反正会变，旧条目留着也没用。 */
  private fun evictIfNeeded() {
    val all = prefs.all
    if (all.size <= MAX_PAGES) return
    val stale =
      all.entries
        .mapNotNull { (key, value) ->
          val seen = (value as? String)?.split('|', limit = 3)?.getOrNull(2)?.toLongOrNull()
          if (seen == null) key to Long.MIN_VALUE else key to seen
        }
        .sortedBy { it.second }
        .take(all.size - MAX_PAGES)
        .map { it.first }
    prefs.edit { stale.forEach { remove(it) } }
  }

  companion object {
    /** 存储身份之外的新偏好文件：只影响页面记忆，改名/丢失都不破坏安装身份。 */
    const val PREFS_NAME = "zoneu_page_memory"

    const val MAX_PAGES = 64
  }
}

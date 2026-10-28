package com.zoneu.app.agent

/**
 * 同一句提问下多份回答之间的切换。
 *
 * 只做「挪一位、到头绕回」这一件事，因为它最容易写错又最不容易看出来：
 * 手指左右划到底时，用户以为「翻到头了」，其实是索引越界被夹住；反过来绕回第一份
 * 才符合「候选」的心智模型（和 ST 的 swipe 一致）。
 *
 * 数据层存的是「哪一份被选中」（`message.variant_selected`），这里只算目标位置 ——
 * 存索引会在删掉一份之后指向空气。
 */
object Variants {
  /** 从 [current] 挪 [delta] 份，越界时绕回另一端。空组返回 0。 */
  fun step(size: Int, current: Int, delta: Int): Int {
    if (size <= 0) return 0
    return ((current + delta) % size + size) % size
  }

  /** 「2/3」这种指示；只有一份时不显示（它不是候选，就是唯一答案）。 */
  fun label(index: Int, size: Int): String? = if (size > 1) "${index + 1}/$size" else null

  /** 当前选中的是哪一份；一份都没选中（脏数据）时退回第一份。 */
  fun currentIndex(indices: List<Int>, selected: Int?): Int =
    if (selected != null && indices.contains(selected)) indices.indexOf(selected) else 0
}

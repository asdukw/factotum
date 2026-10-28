package com.zoneu.app.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * 世界书的**跨轮记忆**：sticky（粘住 N 轮）与 cooldown（冷却 N 轮）都要知道「这条上次什么时候激活过」。
 *
 * 这是 [WorldScan] 第一次不再是纯函数 —— 于是把记忆抽成接口：编排逻辑仍然可单测（传一个假的
 * 实现进去），持久化只在真机上跑。这比把 `Context` 塞进 WorldScan 好：那会让「插在哪一行」
 * 这类最容易改错的东西重新变成只能手点验证。
 *
 * 记的是「轮次」(turn)，不是时间：用户可能隔一天再回来，按时间算冷却会全部过期，
 * 而他要的是「接下来这几轮别又冒出来」。
 */
interface WorldTracking {
  /** 上次因关键词命中而激活的轮次；从没激活过返回 null。 */
  fun lastActivated(key: String): Int?

  /** 记录这一轮激活了它。 */
  fun record(key: String, turn: Int)

  object NONE : WorldTracking {
    override fun lastActivated(key: String): Int? = null

    override fun record(key: String, turn: Int) = Unit
  }

  companion object {
    /**
     * 条目的稳定标识。
     *
     * 没有用下标：删掉一条设定之后所有下标都会前移，跟踪记录会错位到别的条目上，
     * 表现为「粘住的变成了不相干的那条」。用关键词 + 正文前缀的哈希，改这条的正文就会重置
     * 它的粘性/冷却 —— 那是可以接受的（改了内容本来就等于换了一条），错位不行。
     */
    fun key(entry: WorldEntry): String =
      (entry.keys.joinToString(",") + "#" + entry.content.take(48)).hashCode().toString()
  }
}

/**
 * 把激活记忆存在 SharedPreferences 里，按会话隔离。
 *
 * 用 SharedPreferences 而不是加一张表：这是少量整数、每轮读写几次，为它做一次 Room 迁移
 * 不划算；会话被删掉后留下的孤儿键也只是几十字节。
 */
class PrefsWorldTracking(context: Context, private val sessionId: String) : WorldTracking {
  private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override fun lastActivated(key: String): Int? = prefs.getInt(actKey(key), -1).takeIf { it >= 0 }

  override fun record(key: String, turn: Int) {
    prefs.edit().putInt(actKey(key), turn).apply()
  }

  private fun actKey(key: String) = "act:$sessionId:$key"

  companion object {
    private const val PREFS = "zoneu_world_tracking"
    private const val TURN_PREFIX = "turn:"

    /**
     * 这一轮的序号，自增后返回。
     *
     * 为什么不用「历史里有几条消息」：用户点「从此处重来」会让历史变短，轮次就会倒退，
     * 于是刚冷却完的条目又满足冷却条件、粘住的条目重新开始计时 —— 表现是「再问一遍它又出来了」。
     * 单独一个只增不减的计数器没有这个问题。
     */
    fun nextTurn(context: Context, sessionId: String): Int {
      val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val key = TURN_PREFIX + sessionId
      val next = prefs.getInt(key, 0) + 1
      prefs.edit().putInt(key, next).apply()
      return next
    }
  }
}

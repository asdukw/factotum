package com.tappet.app.agent

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 会话变量的存续。
 *
 * 与 [WorldTracking] 同一个理由：这份数据是「几十个短字符串、每轮读写一次」，为它加一张 Room 表
 * 和一次迁移不划算。会话被删掉之后遗留的键只有几百字节。
 *
 * 抽成接口是为了 [TavernTurns] 的单测能塞内存实现 —— 真的实现要 Context，而拼 prompt 的逻辑
 * 不该为了读一个变量就去碰 Android 框架。
 */
interface VarsStore {
  fun load(sessionId: String): Map<String, String>

  fun save(sessionId: String, values: Map<String, String>)

  companion object {
    /** 什么都不存：单测与非酒馆路径用。 */
    val NONE: VarsStore =
      object : VarsStore {
        override fun load(sessionId: String): Map<String, String> = emptyMap()

        override fun save(sessionId: String, values: Map<String, String>) = Unit
      }
  }
}

/** 变量存在 SharedPreferences 里，一个会话一个 JSON 串。 */
class PrefsVarsStore(context: Context) : VarsStore {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private val json = Json { ignoreUnknownKeys = true }

  override fun load(sessionId: String): Map<String, String> {
    val raw = prefs.getString(key(sessionId), null) ?: return emptyMap()
    // 解析失败当空表：宁可这一轮没有变量，也不能让一次脏数据把整轮对话卡死。
    return runCatching {
      val root = json.parseToJsonElement(raw).jsonObject
      root.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.content?.let { name to it } }.toMap()
    }
      .getOrDefault(emptyMap())
  }

  override fun save(sessionId: String, values: Map<String, String>) {
    if (values.isEmpty()) {
      prefs.edit().remove(key(sessionId)).apply()
      return
    }
    val payload = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
    prefs.edit().putString(key(sessionId), payload).apply()
  }

  private fun key(sessionId: String) = "vars:$sessionId"

  private companion object {
    const val PREFS = "tappet_tavern_vars"
  }
}

package com.zoneu.app.agent

import android.content.Context
import java.io.File

/**
 * 每个角色自己的一份记忆。
 *
 * 为什么要单独存：现在所有会话共用一份宿主记忆（`AGENTS.md`），扮演一个角色时记下的事，
 * 换个角色还在 —— 那既出戏也越界（A 角色不该知道 B 角色的秘密）。这里给每张卡一份，按 id 落盘，
 * 注入到它自己的系统提示里。
 *
 * 内容由用户写，不由模型自动写：没有「会自己长出来的记忆」这种好事 —— 自动总结要额外的模型调用，
 * 而凭空生成的回忆一旦写错，会一直错下去，而且用户看不见它是什么时候写进去的。
 */
object CharacterMemory {
  private const val DIR = "roleplay/memory"

  private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

  private fun file(context: Context, characterId: String): File = File(dir(context), "$characterId.md")

  /** 读这段记忆；没有就返回空串（不是 null —— 调用方一律当「没写」处理）。 */
  fun notes(context: Context, characterId: String): String =
    runCatching { file(context, characterId).takeIf { it.isFile }?.readText().orEmpty().trim() }
      .getOrDefault("")

  /** 写记忆；空串等于删掉（不让一份空文件留在盘上）。 */
  fun save(context: Context, characterId: String, notes: String): Boolean =
    runCatching {
      val text = notes.trim()
      val target = file(context, characterId)
      if (text.isEmpty()) {
        check(!target.exists() || target.delete()) { "记忆清除失败" }
      } else {
        target.writeText(text)
      }
      true
    }
      .getOrDefault(false)

  /** 删卡时顺手把记忆一起删掉，否则同名新卡会继承上一张的回忆。 */
  fun forget(context: Context, characterId: String): Boolean =
    runCatching { file(context, characterId).delete() }.getOrDefault(false)
}

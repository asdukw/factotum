package com.factotum.app.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 桥请求体里那几个字段的读取。
 *
 * 单独一个文件是为了让 [DeviceBridgeServer] 别继续长胖（detekt 对 object 里的函数数有上限），
 * 而且这几段是**纯解析**、与桥的生命周期无关，本来就不该混在监听循环里。
 *
 * 一律「取不到就 null」：桥不认识某字段时宁可让动作报「参数不全」，也不要给个默认值硬跑。
 */
internal fun JsonObject.string(key: String): String? =
  this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

internal fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()

internal fun JsonObject.int(key: String): Int? =
  this[key]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

internal fun JsonObject.boolean(key: String): Boolean? =
  this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

/** 视觉路径的公共入参：帧 id + 图像像素 bbox。缺一个就整条动作不成立。 */
internal class ScreenshotBox(val screenshotId: String, val x1: Int, val y1: Int, val x2: Int, val y2: Int)

internal fun JsonObject.frameBox(): ScreenshotBox? {
  val screenshotId = string("screenshot_id") ?: return null
  val x1 = int("x1") ?: return null
  val y1 = int("y1") ?: return null
  val x2 = int("x2") ?: return null
  val y2 = int("y2") ?: return null
  return ScreenshotBox(screenshotId, x1, y1, x2, y2)
}

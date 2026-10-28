package com.zoneu.app.data.db

import java.security.SecureRandom

/**
 * UUIDv7：前 48 位是毫秒时间戳，其余随机。
 *
 * 用 v7 而不是随机 v4，是为了让主键天然按时间接近插入顺序 —— SQLite 的
 * 主键 B 树不会因为随机插入而频繁分裂。选它而不是自增号，是因为将来
 * 要与 Python 管线生成的 `zoneu.db` 合并，自增号必然冲突。
 */
internal object Uuids {
  private const val HEX = "0123456789abcdef"
  private val random = SecureRandom()

  fun v7(nowMillis: Long = System.currentTimeMillis()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    bytes[0] = (nowMillis ushr 40).toByte()
    bytes[1] = (nowMillis ushr 32).toByte()
    bytes[2] = (nowMillis ushr 24).toByte()
    bytes[3] = (nowMillis ushr 16).toByte()
    bytes[4] = (nowMillis ushr 8).toByte()
    bytes[5] = nowMillis.toByte()
    // 版本号 7、变体 10xx
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    val out = StringBuilder(36)
    for (index in bytes.indices) {
      if (index == 4 || index == 6 || index == 8 || index == 10) out.append('-')
      val value = bytes[index].toInt()
      out.append(HEX[(value shr 4) and 0x0F]).append(HEX[value and 0x0F])
    }
    return out.toString()
  }
}

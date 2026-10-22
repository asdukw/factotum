package com.tappet.app.connector

/**
 * 附件文件名清洗。MediaStore 的 `DISPLAY_NAME` 不是路径，但仍然要挡住：
 *
 * - 路径分隔符与 Windows 保留字符 —— 有些文件管理器 / 分享目标会按路径处理；
 * - 控制字符与换行 —— 防止在系统 UI 或日志里伪造多行；
 * - 空名与超长名。
 *
 * 纯 Kotlin，JVM 单测覆盖（见 `AttachmentFileNameTest`）。
 */
internal object AttachmentFileName {
  private const val MAX_LENGTH = 120

  private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

  fun sanitize(raw: String?): String {
    val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned =
      buildString {
        for (character in base) {
          if (character.isISOControl() || character in FORBIDDEN) append('_') else append(character)
        }
      }
        .trim('.', ' ')
        .take(MAX_LENGTH)
    return cleaned.ifBlank { "attachment" }
  }
}

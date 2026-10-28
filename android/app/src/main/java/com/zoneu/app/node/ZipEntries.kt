package com.zoneu.app.node

import java.io.File

/**
 * 解析归档条目，并拒绝逃出 [root] 的路径（Zip Slip）。
 *
 * 归档来自 App 自己的 assets，但解包代码面对的是一条**不可信**的路径字符串：
 * 一个 `../` 条目就足以把文件写到 `filesDir` 之外。先 canonicalize 再比前缀，
 * 任何最终落到根目录外的路径都会被拒绝。
 *
 * 不用 `require()`：CodeQL 的 Zip Slip 查询认的是「canonicalPath + startsWith + throw」
 * 这条形状；换成 require() 会让它重新报 java/zipslip。
 */
@Suppress("UseRequire")
internal fun zipEntryTarget(root: File, entryName: String): File {
  val canonicalRoot = root.canonicalPath
  val canonicalTarget = File(canonicalRoot, entryName).canonicalPath
  if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
    throw IllegalArgumentException("归档条目逃出了目标目录：$entryName")
  }
  return File(canonicalTarget)
}

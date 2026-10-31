package com.zoneu.app.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.CardExporter
import com.zoneu.app.agent.CardInput
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.WorldBookIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 卡片 / 世界书的出口与入口。
 *
 * 全部走系统文件选择器（SAF）：不申请存储权限，拿到的是单次可用的 `content://` Uri。
 * 导出写 v3 卡（含世界书与备用开场白），也可以只导世界书那一截 —— 备份到别的工具时
 * 只要世界书的场合更多。
 *
 * 导入世界书只对用户自己导入的卡开放：内置卡是随包发布的，改了就与包里的版本不一致，
 * 而且没有「恢复内置版本」的入口。
 */
@Composable
internal fun CardTransferRow(
  card: CharacterCard,
  onCardChanged: (CharacterCard) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  fun export(uri: Uri?, text: String) {
    val target = uri ?: return
    scope.launch {
      val ok = writeText(context, target, text)
      toast(context, if (ok) "已导出" else "导出失败：写不进这个位置")
    }
  }

  val exportCard =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      export(uri, CardExporter.toV3(card))
    }
  val exportWorld =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      export(uri, WorldBookIo.exportText(card))
    }
  val exportCharx =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
      val target = uri ?: return@rememberLauncherForActivityResult
      scope.launch {
        val ok = writeBytes(context, target, CardExporter.toCharx(card))
        toast(context, if (ok) "已导出 CHARX（不含嵌入式资产）" else "导出失败：写不进这个位置")
      }
    }
  val importWorld =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val target = uri ?: return@rememberLauncherForActivityResult
      scope.launch {
        val text = readText(context, target)
        val saved = runCatching {
          withContext(Dispatchers.IO) {
            CharacterStore.updateImported(context, card.id, card.storageRevision.orEmpty()) { current ->
              WorldBookIo.appendText(current, text ?: error("读不到文件，或文件超过 32 MiB 上限"))
            }
          }
        }.getOrElse {
          toast(context, "世界书导入未确认：卡片可能已改变，请重新读取。")
          return@launch
        }
        onCardChanged(saved.card)
        val added = saved.card.world.size - card.world.size
        val message = if (saved.revision == card.storageRevision) {
          "这份世界书已导入，未修改现有条目"
        } else {
          "新增 $added 条运行设定；兼容报告见角色详情"
        }
        toast(context, message)
      }
    }

  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    TextButton(onClick = { exportCard.launch(CardExporter.fileName(card)) }) { Text("导出卡片") }
    TextButton(onClick = { exportCharx.launch(CardExporter.charxFileName(card)) }) { Text("导出 CHARX") }
    TextButton(onClick = { exportWorld.launch(CardExporter.fileName(card).replace(".json", "-世界书.json")) }) {
      Text("导出世界书")
    }
    if (card.imported) {
      TextButton(
        onClick = {
          importWorld.launch(
            arrayOf("application/json", "text/plain", "application/octet-stream"),
          )
        },
      ) {
        Text("导入世界书")
      }
    }
  }
}

/** 写一段文本到 SAF 给的 Uri；失败返回 false（不要抛给用户看栈）。 */
private suspend fun writeText(context: Context, uri: Uri, text: String): Boolean =
  withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        ?: return@runCatching false
      true
    }
      .getOrDefault(false)
  }

private suspend fun writeBytes(context: Context, uri: Uri, bytes: ByteArray): Boolean =
  withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        ?: return@runCatching false
      true
    }
      .getOrDefault(false)
  }

/** 读 SAF 文件；读不出来返回 null。 */
private suspend fun readText(context: Context, uri: Uri): String? =
  withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { CardInput.read(it) } }
      .getOrNull()
      ?.let { String(it, Charsets.UTF_8) }
  }

private fun toast(context: Context, message: String) {
  Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

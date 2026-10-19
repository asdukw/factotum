package com.tappet.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tappet.app.update.UpdateDownloader
import java.util.Locale

/**
 * 下载进度条 + 一行「45% · 52.1 MB / 116.5 MB」。
 *
 * 提醒弹窗与 设置 → 版本更新 共用这一个 —— 进度在两处显示成两个样子是很容易发生的事，
 * 而「同一个数字在两处对不上」比「哪里都看不到」更让人不信任。
 *
 * 总长未知时退回**不确定进度条**（M3 的动画条），而不是画一个卡在 0% 的假进度：
 * 服务器没给 `Content-Length` 是真实存在的情况，那时能如实说的只有「已下载多少」。
 */
@Composable
internal fun UpdateProgressBar(progress: UpdateDownloader.Progress, modifier: Modifier = Modifier) {
  Column(modifier) {
    val shape = RoundedCornerShape(2.dp)
    if (progress.known) {
      LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(shape),
      )
    } else {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(shape))
    }
    Spacer(Modifier.height(6.dp))
    Text(
      text = progressLabel(progress),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 进度文案。抽成纯函数是为了能离开设备验证（见 UpdateProgressTest）。 */
internal fun progressLabel(progress: UpdateDownloader.Progress): String =
  if (progress.known) {
    "${progress.percent}% · ${formatBytes(progress.done)} / ${formatBytes(progress.total)}"
  } else {
    // 总长未知：说「已下载多少」而不是编一个百分比出来。
    "已下载 ${formatBytes(progress.done)}"
  }

/**
 * 把字节数说成人话。
 *
 * 显式用 [Locale.US]：默认区域会把小数点写成逗号（`52,1 MB`），而「MB」本身已经是英文，
 * 混着来只会更奇怪；测试也要一个不随机器区域变的结果。
 */
internal fun formatBytes(bytes: Long): String =
  when {
    bytes >= GIB -> String.format(Locale.US, "%.1f GB", bytes / GIB.toDouble())
    bytes >= MIB -> String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
    bytes >= KIB -> String.format(Locale.US, "%.0f KB", bytes / KIB.toDouble())
    else -> "$bytes B"
  }

private const val KIB = 1024L
private const val MIB = 1024L * 1024
private const val GIB = 1024L * 1024 * 1024

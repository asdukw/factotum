package com.tappet.app.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tappet.app.agent.appVersionName
import com.tappet.app.update.AppUpdater
import com.tappet.app.update.UpdatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「发现新版本」的提醒。每次**进程启动**检查一次，发现比当前新、且用户没跳过这个版本时弹出来。
 *
 * 三个刻意的选择：
 * 1. **跳过记的是版本号，不是一个布尔**（见 [UpdatePreferences]）：跳过 0.7.0 不代表以后别再提醒，
 *    0.8.0 出来照样会问。
 * 2. **失败静默**：没网、API 限流、解析失败一律不弹。更新是锦上添花，不该因为查不到就打扰用户 ——
 *    想看状态可以去 设置 → 版本更新，那里会如实说「检查失败（原因）」。
 * 3. **点外面 / 按返回 = 这次先不处理**，但**不算跳过**（不写记录），下次启动还会问。
 *    只有明确点「跳过」才写进跳过列表。
 *
 * 下载与校验全部复用 [AppUpdater]：SHA-256 + 签名证书比对不过就**不会**给安装入口。
 */
@Composable
fun UpdateReminder() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var failure by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    val result = withContext(Dispatchers.IO) { AppUpdater.check(appVersionName()) }
    if (result is AppUpdater.UpdateCheck.Available &&
      !UpdatePreferences.isSkipped(context, result.release.version)
    ) {
      release = result.release
    }
  }

  val current = release ?: return

  fun startInstall(selected: AppUpdater.Release) {
    if (!AppUpdater.canInstall(context)) {
      failure = "需要允许「安装未知应用」——去 设置 → 版本更新 里授权后再试"
      return
    }
    downloading = true
    failure = null
    scope.launch {
      val outcome =
        withContext(Dispatchers.IO) {
          runCatching { AppUpdater.download(context, selected) { _, _ -> } }
        }
      downloading = false
      outcome
        .onSuccess { apk ->
          runCatching { context.startActivity(AppUpdater.installIntent(context, apk)) }
            .onFailure { failure = "打不开系统安装器（${it.javaClass.simpleName}）" }
            .onSuccess {
              // 装上了当前版本之后，比它旧的跳过记录就没意义了。
              UpdatePreferences.pruneAtOrBelow(context, selected.version)
              release = null
            }
        }
        .onFailure { failure = it.message ?: it.javaClass.simpleName }
    }
  }

  AlertDialog(
    onDismissRequest = { if (!downloading) release = null },
    title = { Text("发现新版本 ${current.version}") },
    text = {
      Text(
        when {
          failure != null -> "更新没完成：$failure"
          downloading -> "正在下载并校验…（签名或哈希对不上会自动中止）"
          else -> "当前版本 ${appVersionName()}。可以在应用内下载并安装；也可以先去 设置 → 版本更新 看详情。"
        },
      )
    },
    confirmButton = {
      TextButton(enabled = !downloading, onClick = { startInstall(current) }) {
        Text(if (downloading) "下载中…" else "更新")
      }
    },
    dismissButton = {
      TextButton(
        enabled = !downloading,
        onClick = {
          UpdatePreferences.skip(context, current.version)
          release = null
        },
      ) {
        // 「跳过」说的是**这个版本**，不是以后都别提醒 —— 文案要把这点说清楚。
        Text("跳过此版本")
      }
    },
  )
}

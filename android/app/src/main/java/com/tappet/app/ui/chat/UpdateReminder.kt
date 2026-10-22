package com.tappet.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tappet.app.BuildConfig
import com.tappet.app.agent.appVersionName
import com.tappet.app.ui.components.UpdateProgressBar
import com.tappet.app.update.UpdateManager
import com.tappet.app.update.UpdatePreferences

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
 * 状态与下载都由 [UpdateManager] 持有，所以**关掉这个弹窗不会中断下载**（以前会）：
 * 弹窗只是个视图，「后台继续」就是把它关掉。
 */
@Composable
fun UpdateReminder() {
  val context = LocalContext.current
  val state by UpdateManager.state.collectAsStateWithLifecycle()
  // 弹过一次就不再弹：状态是全局的，用户在设置页点检查也会走到 Available，
  // 那时不该再弹一次模态框打断他。
  var noticed by remember { mutableStateOf(false) }

  // debug 包与 release 签名不同：下载下来的 release APK 必然装不上。
  // 自动检查只会每次启动都弹一个无法完成的「有新版」，所以只在 release 里查。
  LaunchedEffect(Unit) {
    if (!BuildConfig.DEBUG) UpdateManager.check(context)
  }

  LaunchedEffect(state) {
    val current = state
    if (!noticed &&
      current is UpdateManager.State.Available &&
      !UpdatePreferences.isSkipped(context, current.release.version)
    ) {
      noticed = true
    }
  }

  if (!noticed) return

  AlertDialog(
    onDismissRequest = { noticed = false },
    title = { Text("发现新版本") },
    text = {
      Column {
        Text(dialogText(state))
        val downloading = state as? UpdateManager.State.Downloading
        if (downloading != null) {
          Spacer(Modifier.height(14.dp))
          UpdateProgressBar(downloading.progress)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(context, state, close = { noticed = false }) }) {
        Text(confirmLabel(state))
      }
    },
    dismissButton = {
      TextButton(
        onClick = {
          val current = state
          if (current is UpdateManager.State.Available) {
            UpdatePreferences.skip(context, current.release.version)
          }
          noticed = false
        },
      ) {
        // 「跳过」说的是**这个版本**，不是以后都别提醒 —— 文案要把这点说清楚。
        // 下载中时这个按钮是「后台继续」：关掉弹窗，下载照跑。
        // 缺授权时是「稍后」：用户还没表态要不要这个版本，不该替他记成「跳过」。
        Text(
          when (state) {
            is UpdateManager.State.Downloading -> "后台继续"
            is UpdateManager.State.NeedsPermission -> "稍后"
            else -> "跳过此版本"
          },
        )
      }
    },
  )
}

private fun dialogText(state: UpdateManager.State): String =
  when (state) {
    is UpdateManager.State.Available ->
      "新版本 ${state.release.version}（当前 ${appVersionName()}）。可以在应用内下载并安装；" +
        "也可以先去 设置 → 版本更新 看详情。"

    is UpdateManager.State.Downloading -> "正在下载，签名或哈希对不上会自动中止。"

    is UpdateManager.State.Ready -> "已下载并校验通过，交给系统安装器完成安装。"

    is UpdateManager.State.NeedsPermission ->
      "还需要允许「安装未知应用」才能装 —— 点「去设置」打开系统开关，回来再点一次「更新」，" +
        "已经下载的部分会接着下。"

    is UpdateManager.State.Failed -> "更新没完成：${state.reason}"

    else -> "新版本已就绪。"
  }

private fun confirmLabel(state: UpdateManager.State): String =
  when (state) {
    is UpdateManager.State.Available -> "更新"
    is UpdateManager.State.Downloading -> "取消"
    is UpdateManager.State.Ready -> "安装"
    is UpdateManager.State.NeedsPermission -> "去设置"
    is UpdateManager.State.Failed -> "重试"
    else -> "好"
  }

private fun onConfirm(context: android.content.Context, state: UpdateManager.State, close: () -> Unit) {
  when (state) {
    is UpdateManager.State.Available -> UpdateManager.download(context)

    is UpdateManager.State.Downloading -> UpdateManager.cancel()

    is UpdateManager.State.Ready -> UpdateManager.install(context)

    // 缺授权时 download() 会把用户送到系统设置页；授完权再点一次就是接着下。
    is UpdateManager.State.NeedsPermission -> UpdateManager.download(context)

    is UpdateManager.State.Failed -> UpdateManager.retry(context)

    else -> close()
  }
}

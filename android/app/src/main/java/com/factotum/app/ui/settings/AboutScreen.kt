package com.factotum.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.factotum.app.BuildConfig
import com.factotum.app.agent.appVersionName
import com.factotum.app.ui.components.UpdateProgressBar
import com.factotum.app.update.UpdateManager

/**
 * 关于（设置 → 关于）：版本 + 应用内更新。
 *
 * **只在用户点的时候联网**（与连接器「不后台轮询」同一条原则）：进这一页不发起任何请求，
 * 点「检查更新」才查 GitHub API。
 *
 * 状态与下载都在 [UpdateManager] 里，这里只是它的一个视图 —— 所以离开这一页**不会**中断
 * 下载，回来时进度条还在原处。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "关于", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
      ) {
        AboutSection()
      }
    }
  }
}

@Composable
private fun AboutSection() {
  val context = LocalContext.current
  val state by UpdateManager.state.collectAsStateWithLifecycle()
  val downloading = state as? UpdateManager.State.Downloading

  SettingsGroup {
    if (BuildConfig.DEBUG) {
      // debug 包的签名与 release 不同，下载下来的 release APK 装不上；
      // 这里只说明，不提供按钮 —— 更新与安装走 adb，release 验证在别的设备/CI 上做。
      SettingsRow(
        glyph = GlyphKind.Info,
        title = "版本更新",
        subtitle = "debug 包：请用 adb 安装更新（签名与 release 不同）",
        enabled = false,
      )
    } else {
      SettingsRow(
        glyph = GlyphKind.Info,
        title = "版本更新",
        subtitle = updateSubtitle(state),
        onClick = { onUpdateRowClick(context, state) },
      ) {
        StatusBadge(updateBadge(state), updateTone(state))
      }
      if (downloading != null) {
        // 进度条单独占一行：塞进 SettingsRow 的 trailing 槽会把它挤成一小截，
        // 而这一行的重点此刻就是进度。
        UpdateProgressBar(
          progress = downloading.progress,
          modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 14.dp),
        )
      }
    }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Info,
      title = "Factotum",
      subtitle = "版本 ${appVersionName()}",
      enabled = false,
    )
  }
}

/**
 * 更新那一行点下去做什么。
 *
 * 每一条都对应一个**明确的下一步**，没有「再点一次试试」：
 * 可更新→下载（接着上次的断点）、下载中→取消、已下载→安装、缺授权→去系统设置、
 * 失败→接着下（版本已知时）或重查（版本未知时）。
 */
private fun onUpdateRowClick(context: Context, state: UpdateManager.State) {
  when (state) {
    is UpdateManager.State.Available -> UpdateManager.download(context)

    is UpdateManager.State.Downloading -> UpdateManager.cancel()

    is UpdateManager.State.Ready -> UpdateManager.install(context)

    // 缺授权时 download() 自己会把用户送到系统设置页；授完权再点一次就是接着下。
    is UpdateManager.State.NeedsPermission -> UpdateManager.download(context)

    is UpdateManager.State.Failed -> UpdateManager.retry(context)

    else -> UpdateManager.check(context)
  }
}

private fun updateSubtitle(state: UpdateManager.State): String =
  when (state) {
    UpdateManager.State.Idle -> "点这里检查新版本"

    UpdateManager.State.Checking -> "正在检查…"

    is UpdateManager.State.UpToDate -> "已是最新（${state.version}）"

    is UpdateManager.State.Available -> "发现新版本 ${state.release.version} —— 点这里下载并安装"

    is UpdateManager.State.Downloading -> "正在下载 —— 点这里取消（已下载的部分会留着）"

    is UpdateManager.State.Ready -> "已下载 ${state.version} 且校验通过 —— 点这里安装"

    is UpdateManager.State.NeedsPermission -> "需要允许「安装未知应用」才能安装 —— 点这里去设置"

    is UpdateManager.State.Failed ->
      if (state.release != null) {
        "下载没完成（${state.reason}）—— 点这里接着下"
      } else {
        "检查失败（${state.reason}）—— 点这里重试"
      }
  }

private fun updateBadge(state: UpdateManager.State): String =
  when (state) {
    is UpdateManager.State.Available -> "可更新"
    is UpdateManager.State.Downloading -> "取消"
    is UpdateManager.State.Ready -> "安装"
    is UpdateManager.State.NeedsPermission -> "待授权"
    is UpdateManager.State.Failed -> "失败"
    else -> "检查"
  }

private fun updateTone(state: UpdateManager.State): BadgeTone =
  when (state) {
    is UpdateManager.State.Available, is UpdateManager.State.Ready -> BadgeTone.Enabled
    is UpdateManager.State.Downloading -> BadgeTone.Info
    is UpdateManager.State.NeedsPermission, is UpdateManager.State.Failed -> BadgeTone.Disabled
    else -> BadgeTone.Muted
  }

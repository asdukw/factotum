package com.tappet.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.BuildConfig
import com.tappet.app.accessibility.OverlaySettings
import com.tappet.app.agent.appVersionName
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.ui.components.UpdateProgressBar
import com.tappet.app.update.UpdateManager

/**
 * 设置首页：分组卡片 + 语义徽章，模型部分之外的项目先以「即将推出」占位。
 *
 * 存在的理由不是「多个配置项」，而是 **发布物里不能有密钥**：密钥只有一个来源 ——
 * 提供商详情页，存进 Keystore 封装的 SecretVault。构建期注入那条路已经删掉，
 * 包里不可能有编译进去的密钥。
 *
 * 这一页只做导航与状态摘要。真正的端点 / 模型 / 密钥编辑在 [ProviderDetailScreen]；
 * 这里读不到密钥本体，只读「有没有」。
 */
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenConnectors: () -> Unit,
  onOpenScheduledTasks: () -> Unit,
  onOpenVoice: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // context 必须在外面取：viewModel {} 的工厂 lambda 不是 composable 作用域，
  // 在里面读 LocalContext.current 编译不过。
  val appContext = LocalContext.current.applicationContext
  val viewModel: SettingsViewModel = viewModel { SettingsViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  // 从提供商详情页返回时重新读一次：这条路径没有自动的 prefs 观察，重进页面最直接。
  LaunchedEffect(Unit) { viewModel.reload() }

  SettingsScreen(
    state = state,
    onBack = onBack,
    onOpenProviders = onOpenProviders,
    onOpenConnectors = onOpenConnectors,
    onOpenScheduledTasks = onOpenScheduledTasks,
    onOpenVoice = onOpenVoice,
    modifier = modifier,
  )
}

@Composable
internal fun SettingsScreen(
  state: SettingsViewModel.UiState,
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenConnectors: () -> Unit,
  onOpenScheduledTasks: () -> Unit,
  onOpenVoice: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var accessibilityEnabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }

  // 用户去系统设置里开完无障碍回来，这里要立刻变成「已开启」。
  LifecycleResumeEffect(Unit) {
    accessibilityEnabled = AccessibilityStatus.isEnabled(context)
    onPauseOrDispose { }
  }

  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "设置", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
      ) {
        GeneralSection(autoRead = state.voiceAutoRead, onOpenVoice = onOpenVoice)
        SectionLabel("模型与服务")
        ModelServicesSection(state = state, onOpenProviders = onOpenProviders)
        SectionLabel("执行层")
        ExecutionSection(
          accessibilityEnabled = accessibilityEnabled,
          onOpenAccessibility = { context.startActivity(AccessibilityStatus.settingsIntent()) },
          onOpenScheduledTasks = onOpenScheduledTasks,
        )
        SectionLabel("连接器")
        ConnectorSection(state = state, onOpenConnectors = onOpenConnectors)
        SectionLabel("关于")
        AboutSection()
      }
    }
  }
}

@Composable
private fun GeneralSection(autoRead: Boolean, onOpenVoice: () -> Unit) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Person,
      title = "助手",
      subtitle = "设置个性化助手（智能体）",
      enabled = false,
    ) {
      StatusBadge("即将推出", BadgeTone.Muted)
    }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Box,
      title = "扩展管理",
      subtitle = "管理提示词注入、技能等扩展",
      enabled = false,
    ) {
      StatusBadge("即将推出", BadgeTone.Muted)
    }
    SettingsDivider()
    // 朗读的开关在这里，不在聊天页：聊天页那个喇叭是「这一次读给我听」，
    // 而「每次都读」是个偏好 —— 两件事放两个地方，各自都只有一种含义。
    SettingsRow(
      glyph = GlyphKind.Speaker,
      title = "语音",
      subtitle =
        if (autoRead) {
          "回复完自动朗读"
        } else {
          "点回复下方的喇叭朗读"
        },
      onClick = onOpenVoice,
    )
  }
}

@Composable
private fun ModelServicesSection(
  state: SettingsViewModel.UiState,
  onOpenProviders: () -> Unit,
) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "默认模型和提示词",
      subtitle = "设置各个功能的默认模型",
      enabled = false,
    ) {
      StatusBadge("即将推出", BadgeTone.Muted)
    }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Chip,
      title = "提供商",
      subtitle = providerSubtitle(state),
      onClick = onOpenProviders,
    ) {
      ChevronRight()
    }
    SettingsDivider()
    ComingSoonRow(GlyphKind.Globe, "搜索服务", "设置搜索服务")
    SettingsDivider()
    ComingSoonRow(GlyphKind.Mic, "语音服务", "配置语音合成与语音识别服务提供商")
    SettingsDivider()
    ComingSoonRow(GlyphKind.Hub, "MCP", "配置 MCP 服务器")
    SettingsDivider()
    ComingSoonRow(GlyphKind.Server, "Web 服务器", "允许你通过网页访问 Tappet")
  }
}

@Composable
private fun ComingSoonRow(glyph: GlyphKind, title: String, subtitle: String) {
  SettingsRow(glyph = glyph, title = title, subtitle = subtitle, enabled = false) {
    StatusBadge("即将推出", BadgeTone.Muted)
  }
}

@Composable
private fun ExecutionSection(
  accessibilityEnabled: Boolean,
  onOpenAccessibility: () -> Unit,
  onOpenScheduledTasks: () -> Unit,
) {
  val context = LocalContext.current
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Accessibility,
      title = "无障碍",
      subtitle =
        if (accessibilityEnabled) {
          "已开启 —— 读屏 / 点击 / 截图可用"
        } else {
          "未开启 —— 任务只能聊天，动不了手机；点这里去系统设置"
        },
      onClick = onOpenAccessibility,
    ) {
      StatusBadge(
        text = if (accessibilityEnabled) "已开启" else "未开启",
        tone = if (accessibilityEnabled) BadgeTone.Enabled else BadgeTone.Disabled,
      )
    }
    SettingsDivider()
    // 悬浮球开关：关掉之后，任务在别的 App 里跑时也不会出现那个球。
    // 写偏好后立刻通知正在跑的无障碍服务（OverlaySettings.setEnabled），不需要等服务重连。
    var overlayEnabled by remember { mutableStateOf(OverlaySettings.isEnabled(context)) }
    SettingsRow(
      glyph = GlyphKind.Server,
      title = "任务悬浮球",
      subtitle =
        if (overlayEnabled) {
          "离开 Tappet 执行任务时显示"
        } else {
          "已关闭 —— 任务在执行中不会有任何屏幕提示"
        },
      onClick = {
        val next = !overlayEnabled
        overlayEnabled = next
        OverlaySettings.setEnabled(context, next)
      },
    ) {
      Switch(
        checked = overlayEnabled,
        onCheckedChange = { next ->
          overlayEnabled = next
          OverlaySettings.setEnabled(context, next)
        },
      )
    }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Server,
      title = "定时任务",
      subtitle = "每天到点让 agent 跑一句指令",
      onClick = onOpenScheduledTasks,
    )
    SettingsDivider()
    // Linux 环境（Termux 桥）**功能已在代码里，但还没开发完，所以先按「即将推出」占位** ——
    // 与上面「助手 / 扩展管理」同一套处理：`enabled = false`、不给 onClick，用户点不进去。
    // 入口关掉不等于把功能删掉：`TermuxBridgeScreen` 与导航路由都还在，开发完把这段换回
    // 真入口即可（`git log -S "即将推出" -- SettingsScreen.kt` 能找到这一处）。
    SettingsRow(
      glyph = GlyphKind.Server,
      title = "Linux 环境",
      subtitle = "agent 可在其中的 Debian 里执行命令（开发中）",
      enabled = false,
    ) {
      StatusBadge("即将推出", BadgeTone.Muted)
    }
  }
}

/**
 * 连接器分组。连接器是**另一类数据源**：它不经过无障碍、不依赖屏幕上有什么，
 * 所以它与「执行层」并列，而不是塞进模型那一组（见 docs/email-connector.md §0）。
 *
 * 首页只留**一行入口**通向列表页，不在这里逐个列连接器 —— 每接一个就多一行的话，
 * 这一组会先于功能长成一棵树。
 */
@Composable
private fun ConnectorSection(state: SettingsViewModel.UiState, onOpenConnectors: () -> Unit) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Mail,
      title = "连接器",
      subtitle =
        when {
          state.gmailAccount != null -> "Gmail：${state.gmailAccount}"
          state.gmailConnected -> "Gmail 已连接"
          else -> "Gmail、Outlook 等云端数据源（只读）"
        },
      onClick = onOpenConnectors,
    ) {
      ChevronRight()
    }
  }
}

/**
 * 关于 + 应用内更新。
 *
 * **只在用户点的时候联网**（与连接器「不后台轮询」同一条原则）：进这一页不发起任何请求，
 * 点「检查更新」才查 GitHub API。
 *
 * 状态与下载都在 [UpdateManager] 里，这里只是它的一个视图 —— 所以离开这一页**不会**中断
 * 下载，回来时进度条还在原处。
 */
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
      title = "关于",
      subtitle = "Tappet ${appVersionName()}",
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

private fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "未启用 —— 选一个提供商并填写 API Key"
  val keyHint = if (state.configured) "" else " · 缺 API Key"
  val model = state.providerModel.ifBlank { "未选择模型" }
  return "$name · $model$keyHint"
}

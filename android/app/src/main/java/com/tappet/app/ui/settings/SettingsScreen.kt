package com.tappet.app.ui.settings

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.accessibility.OverlaySettings
import com.tappet.app.agent.appVersionName
import com.tappet.app.device.AccessibilityStatus
import com.tappet.app.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        GeneralSection()
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
private fun GeneralSection() {
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
 * 更新链路上的两条保证由 [AppUpdater] 负责，这里只负责把结果如实说出来：
 * 下载的包要过 SHA-256 与**签名证书比对**；任何一步不过就不给安装入口。
 */
@Composable
private fun AboutSection() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

  fun startCheck() {
    if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading) return
    state = UpdateUiState.Checking
    scope.launch {
      val result = withContext(Dispatchers.IO) { AppUpdater.check(appVersionName()) }
      state =
        when (result) {
          is AppUpdater.UpdateCheck.UpToDate -> UpdateUiState.UpToDate(result.current)
          is AppUpdater.UpdateCheck.Available -> UpdateUiState.Available(result.release)
          is AppUpdater.UpdateCheck.Failed -> UpdateUiState.Failed(result.reason)
        }
    }
  }

  fun startInstall(release: AppUpdater.Release) {
    if (!AppUpdater.canInstall(context)) {
      state = UpdateUiState.NeedsPermission
      return
    }
    state = UpdateUiState.Downloading(0)
    scope.launch {
      val outcome =
        withContext(Dispatchers.IO) {
          runCatching {
            AppUpdater.download(context, release) { done, total ->
              state = UpdateUiState.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
            }
          }
        }
      outcome
        .onSuccess { apk ->
          runCatching { context.startActivity(AppUpdater.installIntent(context, apk)) }
            .onFailure { state = UpdateUiState.Failed(it.javaClass.simpleName) }
            .onSuccess { state = UpdateUiState.UpToDate(appVersionName()) }
        }
        .onFailure { state = UpdateUiState.Failed(it.message ?: it.javaClass.simpleName) }
    }
  }

  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Info,
      title = "版本更新",
      subtitle = updateSubtitle(state),
      onClick = {
        when (val current = state) {
          is UpdateUiState.Available -> startInstall(current.release)

          UpdateUiState.NeedsPermission ->
            runCatching { context.startActivity(AppUpdater.unknownSourcesIntent(context)) }
              .onFailure { state = UpdateUiState.Failed("NO_SETTINGS") }

          else -> startCheck()
        }
      },
    ) {
      StatusBadge(updateBadge(state), updateTone(state))
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

/** 更新入口的四种可操作状态 + 三种终态。UI 只读它，不做判断。 */
private sealed interface UpdateUiState {
  data object Idle : UpdateUiState

  data object Checking : UpdateUiState

  data class UpToDate(val version: String) : UpdateUiState

  data class Available(val release: AppUpdater.Release) : UpdateUiState

  data class Downloading(val percent: Int) : UpdateUiState

  data object NeedsPermission : UpdateUiState

  data class Failed(val reason: String) : UpdateUiState
}

private fun updateSubtitle(state: UpdateUiState): String =
  when (state) {
    UpdateUiState.Idle -> "点这里检查新版本"
    UpdateUiState.Checking -> "正在检查…"
    is UpdateUiState.UpToDate -> "已是最新（${state.version}）"
    is UpdateUiState.Available -> "发现新版本 ${state.release.version} —— 点这里下载并安装"
    is UpdateUiState.Downloading -> "正在下载… ${state.percent}%"
    UpdateUiState.NeedsPermission -> "需要允许「安装未知应用」才能安装 —— 点这里去设置"
    is UpdateUiState.Failed -> "检查失败（${state.reason}）—— 点这里重试"
  }

private fun updateBadge(state: UpdateUiState): String =
  when (state) {
    is UpdateUiState.Available -> "可更新"
    UpdateUiState.NeedsPermission -> "待授权"
    is UpdateUiState.Failed -> "失败"
    else -> "检查"
  }

private fun updateTone(state: UpdateUiState): BadgeTone =
  when (state) {
    is UpdateUiState.Available -> BadgeTone.Enabled
    UpdateUiState.NeedsPermission, is UpdateUiState.Failed -> BadgeTone.Disabled
    else -> BadgeTone.Muted
  }

private fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "未启用 —— 选一个提供商并填写 API Key"
  val keyHint = if (state.configured) "" else " · 缺 API Key"
  val model = state.providerModel.ifBlank { "未选择模型" }
  return "$name · $model$keyHint"
}

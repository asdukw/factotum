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
import com.tappet.app.agent.appVersionName
import com.tappet.app.device.AccessibilityStatus

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
    modifier = modifier,
  )
}

@Composable
internal fun SettingsScreen(
  state: SettingsViewModel.UiState,
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  onOpenConnectors: () -> Unit,
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
) {
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

@Composable
private fun AboutSection() {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Info,
      title = "关于",
      subtitle = "Tappet ${appVersionName()}",
      enabled = false,
    )
  }
}

private fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "未启用 —— 选一个提供商并填写 API Key"
  val keyHint = if (state.configured) "" else " · 缺 API Key"
  val model = state.providerModel.ifBlank { "未选择模型" }
  return "$name · $model$keyHint"
}

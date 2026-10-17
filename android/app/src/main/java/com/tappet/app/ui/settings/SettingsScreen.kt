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
    modifier = modifier,
  )
}

@Composable
internal fun SettingsScreen(
  state: SettingsViewModel.UiState,
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var accessibilityEnabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }
  var showKeyInfo by remember { mutableStateOf(false) }

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
        SectionLabel("存储与关于")
        StorageSection(onShowKeyInfo = { showKeyInfo = true })
      }
    }
  }

  if (showKeyInfo) {
    KeyStorageDialog(onDismiss = { showKeyInfo = false })
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
private fun ExecutionSection(accessibilityEnabled: Boolean, onOpenAccessibility: () -> Unit) {
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
  }
}

@Composable
private fun StorageSection(onShowKeyInfo: () -> Unit) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Key,
      title = "密钥与存储",
      subtitle = "Keystore 封装 · 不进日志 · 卸载即丢失",
      onClick = onShowKeyInfo,
    ) {
      ChevronRight()
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

@Composable
private fun KeyStorageDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("密钥怎么存") },
    text = {
      Text(
        text =
          "每个提供商的 API Key 都用 Android Keystore 里的 AES 密钥封装后再落盘。" +
            "那把密钥是硬件封装、不可导出的，所以密文换台手机就解不开，卸载重装也会丢 —— " +
            "都需要重新填一次。它不进云备份，不进日志，也不回填到界面上。\n\n" +
            "配置只在下次启动 App 时生效：内嵌 Node 的环境变量与 dsh profile 只在启动时读一次，" +
            "这里不做热重载（重拉 Node 进程会把整个 App 带崩）。",
        style = MaterialTheme.typography.bodySmall,
      )
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("知道了") }
    },
  )
}

private fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "未启用 —— 选一个提供商并填写 API Key"
  val keyHint = if (state.configured) "" else " · 缺 API Key"
  return "$name · ${state.providerModel}$keyHint"
}

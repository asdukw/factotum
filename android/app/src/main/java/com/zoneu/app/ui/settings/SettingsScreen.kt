package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import com.zoneu.app.agent.ApprovalPolicy
import com.zoneu.app.agent.ApprovalSettings
import com.zoneu.app.agent.appVersionName
import com.zoneu.app.device.AccessibilityStatus

/**
 * 设置首页：**只放分组入口**，具体项在各自的分页里 ——
 * 模型与服务 / 执行层 / 连接器 / 语音 / 定时任务 / 关于。首页不再平铺开关与配置项：
 * 每多一个功能，平铺那版就多一行，很快会长成一棵没有层级的清单。
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
  onOpenModelServices: () -> Unit,
  onOpenExecution: () -> Unit,
  onOpenConnectors: () -> Unit,
  onOpenVoice: () -> Unit,
  onOpenScheduledTasks: () -> Unit,
  onOpenAbout: () -> Unit,
  onOpenCharacters: () -> Unit = {},
  onOpenTavernPresets: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  // context 必须在外面取：viewModel {} 的工厂 lambda 不是 composable 作用域，
  // 在里面读 LocalContext.current 编译不过。
  val appContext = LocalContext.current.applicationContext
  val viewModel: SettingsViewModel = viewModel { SettingsViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  // 从分页返回时重新读一次：摘要里的提供商 / 连接器状态没有自动的 prefs 观察，
  // 重进页面最直接。
  LaunchedEffect(Unit) { viewModel.reload() }

  SettingsScreen(
    state = state,
    onBack = onBack,
    onOpenModelServices = onOpenModelServices,
    onOpenExecution = onOpenExecution,
    onOpenConnectors = onOpenConnectors,
    onOpenVoice = onOpenVoice,
    onOpenScheduledTasks = onOpenScheduledTasks,
    onOpenAbout = onOpenAbout,
    onOpenCharacters = onOpenCharacters,
    onOpenTavernPresets = onOpenTavernPresets,
    modifier = modifier,
  )
}

@Composable
internal fun SettingsScreen(
  state: SettingsViewModel.UiState,
  onBack: () -> Unit,
  onOpenModelServices: () -> Unit,
  onOpenExecution: () -> Unit,
  onOpenConnectors: () -> Unit,
  onOpenVoice: () -> Unit,
  onOpenScheduledTasks: () -> Unit,
  onOpenAbout: () -> Unit,
  onOpenCharacters: () -> Unit = {},
  onOpenTavernPresets: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var accessibilityEnabled by remember { mutableStateOf(AccessibilityStatus.isEnabled(context)) }
  var allowAllWrites by remember {
    mutableStateOf(ApprovalSettings.policy(context) == ApprovalPolicy.ALLOW_ALL)
  }

  // 用户去系统设置里开完无障碍、或从执行层改了审批策略回来，摘要要立刻变。
  LifecycleResumeEffect(Unit) {
    accessibilityEnabled = AccessibilityStatus.isEnabled(context)
    allowAllWrites = ApprovalSettings.policy(context) == ApprovalPolicy.ALLOW_ALL
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
        SettingsGroup {
          SettingsRow(
            glyph = GlyphKind.Sparkles,
            title = "模型与服务",
            subtitle = providerSubtitle(state),
            onClick = onOpenModelServices,
          ) {
            ChevronRight()
          }
          SettingsDivider()
          SettingsRow(
            glyph = GlyphKind.Accessibility,
            title = "执行层",
            subtitle = executionSubtitle(accessibilityEnabled, allowAllWrites),
            onClick = onOpenExecution,
          ) {
            ChevronRight()
          }
          SettingsDivider()
          SettingsRow(
            glyph = GlyphKind.Mail,
            title = "连接器",
            subtitle = connectorSubtitle(state),
            onClick = onOpenConnectors,
          ) {
            ChevronRight()
          }
          SettingsDivider()
          SettingsRow(
            glyph = GlyphKind.Speaker,
            title = "语音",
            subtitle = if (state.voiceAutoRead) "回复完自动朗读" else "点回复下方的喇叭朗读",
            onClick = onOpenVoice,
          ) {
            ChevronRight()
          }
        }
        ScheduledTasksSection(onOpenScheduledTasks)
        EntertainmentSection(onOpenCharacters, onOpenTavernPresets)
        SectionLabel("关于")
        SettingsGroup {
          SettingsRow(
            glyph = GlyphKind.Info,
            title = "关于",
            subtitle = "zoneu ${appVersionName()}",
            onClick = onOpenAbout,
          ) {
            ChevronRight()
          }
        }
      }
    }
  }
}

/** 执行层摘要：两个最常被问到的状态，具体开关都在分页里。 */
private fun executionSubtitle(accessibilityEnabled: Boolean, allowAllWrites: Boolean): String {
  val accessibility = if (accessibilityEnabled) "无障碍已开启" else "无障碍未开启"
  val approval = if (allowAllWrites) "写操作全部允许" else "写操作每次确认"
  return "$accessibility · $approval"
}

private fun connectorSubtitle(state: SettingsViewModel.UiState): String =
  when {
    state.gmailAccount != null -> "Gmail：${state.gmailAccount}"
    state.gmailConnected -> "Gmail 已连接"
    else -> "Gmail 等云端数据源（只读）"
  }

/** 定时任务入口。任务由对话创建和修改，设置首页只负责导航（`docs/scheduled-tasks.md`）。 */
@Composable
private fun ScheduledTasksSection(onOpen: () -> Unit) {
  SectionLabel("任务")
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Clock,
      title = "定时任务",
      subtitle = "查看排期、下次触发时间与上次结果",
      onClick = onOpen,
    ) {
      ChevronRight()
    }
  }
}

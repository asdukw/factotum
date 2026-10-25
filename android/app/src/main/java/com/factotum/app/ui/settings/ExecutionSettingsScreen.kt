package com.factotum.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.factotum.app.accessibility.OverlaySettings
import com.factotum.app.agent.ApprovalPolicy
import com.factotum.app.agent.ApprovalSettings
import com.factotum.app.device.AccessibilityStatus

/**
 * 执行层（设置 → 执行层）：无障碍、写操作审批、任务悬浮球。
 *
 * 这一页只放**影响 agent 怎么动手机**的开关；模型与数据源在各自的分页里。
 * 定时任务不在这里 —— 它由对话语义化创建（`docs/scheduled-tasks.md`）。
 */
@Composable
fun ExecutionSettingsScreen(
  onBack: () -> Unit,
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
      SettingsHeader(title = "执行层", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
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
            onClick = { context.startActivity(AccessibilityStatus.settingsIntent()) },
          ) {
            StatusBadge(
              text = if (accessibilityEnabled) "已开启" else "未开启",
              tone = if (accessibilityEnabled) BadgeTone.Enabled else BadgeTone.Disabled,
            )
          }
          SettingsDivider()
          ApprovalPolicyRow()
          SettingsDivider()
          // 悬浮球开关：关掉之后，任务在别的 App 里跑时也不会出现那个球。
          // 写偏好后立刻通知正在跑的无障碍服务（OverlaySettings.setEnabled），不需要等服务重连。
          var overlayEnabled by remember { mutableStateOf(OverlaySettings.isEnabled(context)) }
          SettingsRow(
            glyph = GlyphKind.Server,
            title = "任务悬浮球",
            subtitle =
              if (overlayEnabled) {
                "离开 Factotum 执行任务时显示"
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
        }
      }
    }
  }
}

/**
 * 写操作审批：默认每次询问。打开后桥直接回 allowed-once，日历 / 闹钟 / 邮件的写入不再弹
 * 确认面板 —— 请求仍走 dsh 的审批 seam 与审计，变的只是谁来回答（`docs/approvals.md` §2）。
 *
 * 单独一个 composable 是为了让 [ExecutionSettingsScreen] 不越过 detekt 的 80 行上限；
 * 状态只在这个开关里，与无障碍 / 悬浮球的开关互不影响。
 */
@Composable
private fun ApprovalPolicyRow() {
  val context = LocalContext.current
  var allowAllWrites by remember {
    mutableStateOf(ApprovalSettings.policy(context) == ApprovalPolicy.ALLOW_ALL)
  }
  val toggle = {
    val next = !allowAllWrites
    allowAllWrites = next
    ApprovalSettings.setAllowAllWrites(context, next)
  }
  SettingsRow(
    glyph = GlyphKind.Check,
    title = "写操作审批",
    subtitle =
      if (allowAllWrites) {
        "全部允许 —— 日历 / 闹钟 / 邮件写入不再确认"
      } else {
        "每次写入都在聊天里确认"
      },
    onClick = { toggle() },
  ) {
    Switch(checked = allowAllWrites, onCheckedChange = { toggle() })
  }
}

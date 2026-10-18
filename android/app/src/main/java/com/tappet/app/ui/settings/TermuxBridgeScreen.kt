package com.tappet.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.TermuxBridgeLauncher

/**
 * Linux 环境（Termux 桥）设置页。
 *
 * 这里配的是「手机上那台 Linux」的入口：Termux 里跑着 proot Debian，桥把
 * 「执行一条命令并拿回 stdout」暴露在 127.0.0.1 上，agent 用 `run_command` 打过去。
 *
 * **token 不在这里填** —— 由 Tappet 生成，经 Termux 的 RUN_COMMAND 送过去。用户要做的只有：
 * 装好 Termux（跑一次 `tools/termux/setup.sh`）、点一次授权、点一次启用。
 * 授权那一步绕不过去，也不该绕：它等于「允许 Tappet 在 Termux 里执行命令」。
 */
@Composable
fun TermuxBridgeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: TermuxBridgeViewModel = viewModel { TermuxBridgeViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  // 权限弹窗只能从界面发起。ColorOS 会拦 adb 的 pm grant，所以这一步必须由用户点。
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      viewModel.onPermissionResult(granted)
    }

  LaunchedEffect(Unit) { viewModel.refresh() }
  LaunchedEffect(state.status) {
    if (state.status == TermuxBridgeViewModel.Status.NO_PERMISSION && !state.permissionGranted) {
      permissionLauncher.launch(TermuxBridgeLauncher.RUN_COMMAND_PERMISSION)
    }
  }

  TermuxBridgeScreen(
    state = state,
    onBack = onBack,
    onUrlChange = viewModel::onUrlChange,
    onPrimaryAction = viewModel::onPrimaryAction,
    onDisable = viewModel::disable,
    modifier = modifier,
  )
}

@Composable
internal fun TermuxBridgeScreen(
  state: TermuxBridgeViewModel.UiState,
  onBack: () -> Unit,
  onUrlChange: (String) -> Unit,
  onPrimaryAction: () -> Unit,
  onDisable: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "Linux 环境", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        InfoBanner(
          "在手机上装一套真的 Linux（Termux + Debian），agent 就能在里面直接执行命令 —— " +
            "读写文件、跑 git / python / npm。装配脚本在仓库的 tools/termux/setup.sh，" +
            "跑完之后回到这里点「启用」即可，token 由 Tappet 自己生成并送过去。",
        )

        SettingsGroup {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text("状态", style = MaterialTheme.typography.titleSmall)
              StatusBadge(
                text = if (state.enabled) "已启用" else "未启用",
                tone = if (state.enabled) BadgeTone.Enabled else BadgeTone.Muted,
              )
            }
            Text(
              text = state.status.message.ifEmpty { statusHint(state) },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = state.urlInput,
              onValueChange = onUrlChange,
              label = { Text("桥地址") },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
              modifier = Modifier.fillMaxWidth(),
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
              if (state.enabled) {
                TextButton(onClick = onDisable) { Text("停用") }
              }
              Button(onClick = onPrimaryAction, enabled = state.actionEnabled) {
                Text(state.actionLabel)
              }
            }
          }
        }

        Text(
          text =
            if (state.enabled) {
              "已启用。**重启 App 后生效** —— token 与地址只在 Node 启动时读一次。"
            } else {
              "未启用：没有 token 时 run_command 不会出现在工具表里。"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun statusHint(state: TermuxBridgeViewModel.UiState): String =
  when {
    !state.termuxInstalled -> "手机上没有 Termux。"
    !state.permissionGranted -> "已装 Termux，还需要授权一次。"
    else -> "点「启用」把桥拉起来。"
  }

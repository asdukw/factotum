@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Gmail 连接器（设置 → 连接器 → Gmail）。
 *
 * 这一页只做三件事：填客户端 id、走一次授权、看连的是哪个账号。
 * **它读不到任何凭据** —— refresh token 进 Keystore 封装的 SecretVault 后就不再有出口，
 * 设置页只显示「有没有」。边界与隐私闸门见 `docs/email-connector.md`。
 *
 * 与 Outlook 那一页最大的差别：**这里没有码**。Google 的设备码流程不支持 Gmail 权限，
 * 走的是回环重定向（`http://127.0.0.1:<随机端口>`），所以流程是
 * 「点连接 → 浏览器 → 点同意 → 自动跳回」，用户只需要点两下。
 */
@Composable
fun GmailConnectorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  // Nav3 1.0.1 缺配套的 SavedState 装饰器（装了会启动即崩），所以用 Activity 作用域的 VM。
  // 只有一个连接器，key 固定即可。
  val viewModel: GmailConnectorViewModel =
    viewModel(key = "connector-gmail") { GmailConnectorViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { viewModel.reload() }
  DisposableEffect(viewModel) { onDispose { viewModel.cancelConnect() } }

  GmailConnectorScreen(
    state = state,
    onBack = onBack,
    onClientIdChange = viewModel::onClientIdChange,
    onClientSecretChange = viewModel::onClientSecretChange,
    onConnect = viewModel::connect,
    onCancelConnect = viewModel::cancelConnect,
    onDisconnect = viewModel::disconnect,
    modifier = modifier,
  )
}

@Composable
internal fun GmailConnectorScreen(
  state: GmailConnectorViewModel.UiState,
  onBack: () -> Unit,
  onClientIdChange: (String) -> Unit,
  onClientSecretChange: (String) -> Unit,
  onConnect: () -> Unit,
  onCancelConnect: () -> Unit,
  onDisconnect: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
      SettingsHeader(title = "Gmail", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        ConnectionCard(state = state, onDisconnect = onDisconnect)
        ClientIdSection(
          state = state,
          onClientIdChange = onClientIdChange,
          onClientSecretChange = onClientSecretChange,
          onConnect = onConnect,
          onCancelConnect = onCancelConnect,
        )
        SectionLabel("模型能做什么")
        CapabilityCard()
      }
    }
  }
}

@Composable
private fun ConnectionCard(
  state: GmailConnectorViewModel.UiState,
  onDisconnect: () -> Unit,
) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Mail,
      title = state.account ?: if (state.connected) "已连接" else "未连接",
      subtitle =
        when {
          state.awaitingConsent -> "请在浏览器里完成授权…"
          state.connected -> "权限 gmail.modify；读邮件与审批后写入 / 发送"
          else -> "连接后才能读取邮件；zoneu 不会拿到你的密码"
        },
    ) {
      StatusBadge(
        text = if (state.connected) "已连接" else "未连接",
        tone = if (state.connected) BadgeTone.Enabled else BadgeTone.Muted,
      )
    }
    if (state.connected || state.account != null) {
      SettingsDivider()
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        OutlinedButton(onClick = onDisconnect) { Text("断开连接") }
      }
    }
  }
}

@Composable
private fun ClientIdSection(
  state: GmailConnectorViewModel.UiState,
  onClientIdChange: (String) -> Unit,
  onClientSecretChange: (String) -> Unit,
  onConnect: () -> Unit,
  onCancelConnect: () -> Unit,
) {
  // 包里预置了客户端 ID 时，**注册指引与输入框默认都不出现** —— 用户要做的只有点「连接」。
  // 想换用自己的应用注册，点最后那行小字才摊开。
  // 用 remember 记住「用户主动摊开过」：否则他一清空输入框（预置值又顶上来、
  // clientIdFromDefault 变回 true）这一段的显示状态会跳回去，输入框在打字中途消失。
  var override by remember(state.clientIdFromDefault) { mutableStateOf(false) }
  val showField = !state.clientIdFromDefault || override

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (showField) {
      SectionLabel("应用注册")
      if (state.clientIdFromDefault) {
        InfoBanner(text = "已使用 zoneu 预置的客户端 ID。想换成自己的应用注册，就改下面的输入框。")
      } else {
        InfoBanner(
          text =
            "在 Google Cloud 控制台新建项目并启用 Gmail API，配好 OAuth 同意屏幕（External，" +
              "测试模式下把自己加进测试用户），再到「凭据」新建一个「桌面应用」类型的 " +
              "OAuth 客户端 ID 和配套客户端密码；授权同时使用 PKCE。",
        )
      }
      OutlinedTextField(
        value = state.clientId,
        onValueChange = onClientIdChange,
        label = { Text(if (state.clientIdFromDefault) "客户端 ID（预置）" else "OAuth 客户端 ID") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        // 客户端 id 形如 xxx.apps.googleusercontent.com：不要让输入法联想，也不要首字母大写。
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.clientSecret,
        onValueChange = onClientSecretChange,
        label = { Text(if (state.clientSecretFromDefault) "客户端密码（预置）" else "客户端密码") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text =
          "客户端密码在 Google 那边标着「密码」，但对桌面应用它是公开标识（Google 原话：桌面应用保不住秘密），" +
            "真正的保护是 PKCE。不过令牌端点**要求必须发** —— 少了它会报 client_secret is missing。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    if (state.awaitingConsent) {
      AwaitingConsentBlock(onCancel = onCancelConnect)
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onConnect, enabled = !state.connecting && state.clientId.isNotBlank()) {
          if (state.connecting) {
            LoadingIndicator(modifier = Modifier.size(18.dp))
          } else {
            Text(if (state.connected) "重新连接" else "连接")
          }
        }
      }
    }
    // 错误**必须贴着动作**：离触发点远了用户就看不到（Outlook 那一版就踩过这个坑）。
    if (state.error != null) {
      Text(
        text = state.error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    // 逃生门：预置的那个 client id 挂了（项目被停、配额爆了、想用自己的），
    // 用户还能自己注册一个填进来。默认不显示，免得把「点一下就完事」变成「先读一段文档」。
    if (state.clientIdFromDefault && !override) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { override = true }) { Text("使用我自己的 OAuth 客户端 ID") }
      }
    }
  }
}

/** 等用户在那个页面上点「同意」。这里没有码可抄 —— 跳转是自动的。 */
@Composable
private fun AwaitingConsentBlock(onCancel: () -> Unit) {
  SettingsGroup {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = "已打开浏览器，请登录并点「同意」。授权完成后会自动跳回这里。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("取消") }
      }
    }
  }
}

/** 能力面写死在界面上：用户应该一眼看出这是**只读**的，而不是去读源码或文档。 */
@Composable
private fun CapabilityCard() {
  SettingsGroup {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      CapabilityLine("mail_list", "列出最近的邮件（主题 / 发件人 / 时间 / 有无附件）")
      CapabilityLine("mail_search", "按关键词检索（支持 Gmail 的 from: / subject: / 时间范围）")
      CapabilityLine("mail_read", "读某一封的正文（截断后给模型）")
      Spacer(Modifier.height(6.dp))
      Text(
        text =
          "目前没有发送、删除、标记已读等写操作 —— 工具面是纯只读的。" +
            "但授权时要的是 gmail.modify（含读 / 写 / 发）：Google 对已安装应用不支持增量授权，" +
            "以后再加写操作就得让所有已连接的用户重走一遍同意页，所以一次要齐。" +
            "邮件正文只出现在给模型的工具结果里，不落日志、不落盘，本机不保存邮箱副本。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CapabilityLine(name: String, description: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = name,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(end = 10.dp),
    )
    Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.connector.GMAIL_CONNECTOR_ID

/**
 * 连接器列表（设置 → 连接器）。
 *
 * 为什么要有这一层而不是把 Gmail 直接摆在设置首页：连接器是**一类**能力（云端数据源），
 * 现在有 Gmail、后面有 Outlook / Notion —— 列表页是那个「后面」的落点。
 * 首页上只留一行入口，免得每接一个就多一行。
 */
@Composable
fun ConnectorsScreen(
  onBack: () -> Unit,
  onOpenGmail: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  // Nav3 1.0.1 缺配套的 SavedState 装饰器（装了会启动即崩），所以用 Activity 作用域的 VM。
  val viewModel: ConnectorsViewModel = viewModel { ConnectorsViewModel(appContext) }
  val entries by viewModel.entries.collectAsStateWithLifecycle()

  // 从 Gmail 详情页授权完返回时，徽章要立刻变 —— 重进这一页就重读一次。
  LaunchedEffect(Unit) { viewModel.reload() }

  ConnectorsScreen(entries = entries, onBack = onBack, onOpenGmail = onOpenGmail, modifier = modifier)
}

@Composable
internal fun ConnectorsScreen(
  entries: List<ConnectorEntry>,
  onBack: () -> Unit,
  onOpenGmail: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "连接器", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        SettingsGroup {
          entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            ConnectorRow(entry = entry, onOpenGmail = onOpenGmail)
          }
        }
        Text(
          text =
            "连接器让模型直接读你云端账号里的数据（不经过无障碍）。" +
              "授权只在本机保存一个 refresh token，由 Keystore 封装；本机不保存邮箱副本。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )
      }
    }
  }
}

@Composable
private fun ConnectorRow(entry: ConnectorEntry, onOpenGmail: () -> Unit) {
  // 只有已实现的连接器可点；点进一个空页面比不可点更让人困惑。
  val onClick = if (entry.id == GMAIL_CONNECTOR_ID) onOpenGmail else null
  SettingsRow(
    glyph = GlyphKind.Mail,
    // 账号放**副标题**：邮箱比连接器名长得多，放标题会被折成两行，
    // 看起来像排版坏了（真机上就是这个效果）。副标题本来就会省略号截断。
    title = entry.title,
    subtitle = entry.account ?: entry.subtitle,
    onClick = onClick,
  ) {
    StatusBadge(
      text = if (entry.connected) "已连接" else "未连接",
      tone = if (entry.connected) BadgeTone.Enabled else BadgeTone.Muted,
    )
  }
}

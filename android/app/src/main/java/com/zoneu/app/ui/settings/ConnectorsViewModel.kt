package com.zoneu.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.zoneu.app.connector.ConnectorStore
import com.zoneu.app.connector.GMAIL_CONNECTOR_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接器列表的一行。**用数据而不是写死的 UI** —— 加一个连接器（Notion、Outlook……）
 * 是往 [ConnectorsViewModel.entries] 里加一条，不是再抄一遍行布局。
 */
internal data class ConnectorEntry(
  val id: String,
  val title: String,
  val subtitle: String,
  val connected: Boolean,
  /** 已连接时显示在标题上的账号；未连接为 null。 */
  val account: String?,
)

/**
 * 连接器列表（设置 → 连接器）。
 *
 * 连接器是**另一类数据源**：不经过无障碍、直接从用户的云端账号读数据，所以它与「执行层」
 * 并列而不是塞进模型那一组（见 `docs/email-connector.md` §0）。
 *
 * 这里只读「有没有授权」，**读不到任何凭据** —— 凭据在 Keystore 封装的 SecretVault 里，
 * 没有出口。这一页会进截图，所以它上面永远不该出现令牌。
 */
internal class ConnectorsViewModel(private val appContext: Context) : ViewModel() {
  private val _entries = MutableStateFlow(entries())
  val entries: StateFlow<List<ConnectorEntry>> = _entries.asStateFlow()

  /** 从 Gmail 详情页返回时重进这一页会调它 —— 授权结果要立刻反映到徽章上。 */
  fun reload() {
    _entries.value = entries()
  }

  private fun entries(): List<ConnectorEntry> {
    val gmail = ConnectorStore.status(appContext, GMAIL_CONNECTOR_ID)
    return listOf(
      ConnectorEntry(
        id = GMAIL_CONNECTOR_ID,
        title = "Gmail",
        subtitle = if (gmail.connected) "已连接 · 只读工具" else "列出、检索、读取邮件（只读）",
        connected = gmail.connected,
        account = gmail.account,
      ),
    )
  }
}

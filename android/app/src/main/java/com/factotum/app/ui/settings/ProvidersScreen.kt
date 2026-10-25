package com.factotum.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.factotum.app.agent.LlmPreset
import com.factotum.app.agent.ProviderSettings

/**
 * 提供商列表（参考图的第二屏）：搜索框 + 卡片列表 + 三点菜单。
 *
 * 卡片上的「N 个模型」是**运行时目录里声明过的模型数**，不是厂商的模型总数 ——
 * 自定义提供商手填一个目录外的 id 时算 1 个，因为那一个能真的用。
 */
@Composable
fun ProvidersScreen(
  onBack: () -> Unit,
  onOpenProvider: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: ProvidersViewModel = viewModel { ProvidersViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(Unit) { viewModel.reload() }
  // 错误用 Snackbar 呈现：卡片可能滚在屏幕外，内联在列表顶部的话用户看不到。
  LaunchedEffect(state.error) {
    val message = state.error ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.clearError()
  }

  ProvidersScreen(
    state = state,
    onBack = onBack,
    onQueryChange = viewModel::onQueryChange,
    onToggleEnabled = viewModel::toggleEnabled,
    onOpenProvider = onOpenProvider,
    snackbarHostState = snackbarHostState,
    modifier = modifier,
  )
}

@Composable
internal fun ProvidersScreen(
  state: ProvidersViewModel.UiState,
  onBack: () -> Unit,
  onQueryChange: (String) -> Unit,
  onToggleEnabled: (LlmPreset) -> Unit,
  onOpenProvider: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
          BackCircle(onBack)
        }
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp)
              .padding(bottom = 24.dp),
        ) {
          BigTitle(
            title = "提供商",
            subtitle = "配置 AI 提供商：端点、密钥、要用的模型。",
          )
          OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = { Text("搜索提供商") },
            leadingIcon = {
              Glyph(
                kind = GlyphKind.Search,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            },
          )
          Spacer(Modifier.height(12.dp))

          state.visible.forEach { provider ->
            ProviderCard(
              settings = provider,
              onOpen = { onOpenProvider(provider.preset.id) },
              onToggleEnabled = { onToggleEnabled(provider.preset) },
            )
            Spacer(Modifier.height(10.dp))
          }

          if (state.visible.isEmpty()) {
            Text(
              text = "没有匹配的提供商",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(8.dp),
            )
          }

          Text(
            text =
              "同一时间只启用一个提供商：内嵌运行时每个进程只连一个端点。" +
                "启用另一家会自动停用当前这家。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
          )
        }
      }
      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
      )
    }
  }
}

@Composable
private fun ProviderBadges(settings: ProviderSettings) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    StatusBadge(
      text = if (settings.enabled) "启用" else "禁用",
      tone = if (settings.enabled) BadgeTone.Enabled else BadgeTone.Disabled,
    )
    StatusBadge(text = "${settings.modelCount} 个模型", tone = BadgeTone.Info)
    if (!settings.hasKey) {
      StatusBadge(text = "缺 Key", tone = BadgeTone.Disabled)
    }
    if (settings.verified) {
      StatusBadge(text = "已验证", tone = BadgeTone.Enabled)
    }
  }
}

@Composable
private fun ProviderCard(
  settings: ProviderSettings,
  onOpen: () -> Unit,
  onToggleEnabled: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }

  Surface(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onOpen),
    shape = RoundedCornerShape(20.dp),
    color = settingsCardColor(),
  ) {
    Row(
      modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      ProviderAvatar(preset = settings.preset, name = settings.name)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
          text = settings.name,
          style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
          fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = settings.preset.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        ProviderBadges(settings)
      }
      Spacer(Modifier.width(4.dp))
      Box {
        Box(
          modifier =
            Modifier.size(44.dp)
              .clip(CircleShape)
              .clickable { menuOpen = true }
              .semantics { contentDescription = "更多操作" },
          contentAlignment = Alignment.Center,
        ) {
          Glyph(
            kind = GlyphKind.More,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("编辑") },
            onClick = {
              menuOpen = false
              onOpen()
            },
          )
          DropdownMenuItem(
            text = { Text(if (settings.enabled) "停用" else "启用") },
            onClick = {
              menuOpen = false
              onToggleEnabled()
            },
          )
        }
      }
    }
  }
}

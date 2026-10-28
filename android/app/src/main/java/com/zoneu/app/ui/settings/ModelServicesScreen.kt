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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 模型与服务（设置 → 模型与服务）：目前只有提供商入口。
 *
 * 单独一页而不是把「提供商」平铺到设置首页：这一组还会长出搜索服务、语音服务等配置，
 * 首页只留分组，细节沉到这里。
 */
@Composable
fun ModelServicesScreen(
  onBack: () -> Unit,
  onOpenProviders: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: SettingsViewModel = viewModel { SettingsViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  // 从提供商列表 / 详情页返回时刷新摘要（有没有启用、缺不缺 API Key）。
  LaunchedEffect(Unit) { viewModel.reload() }

  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "模型与服务", onBack = onBack)
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
            title = "提供商",
            subtitle = providerSubtitle(state),
            onClick = onOpenProviders,
          ) {
            ChevronRight()
          }
        }
      }
    }
  }
}

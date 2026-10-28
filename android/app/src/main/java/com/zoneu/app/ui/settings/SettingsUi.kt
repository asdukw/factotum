package com.zoneu.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.ui.components.AppBanner
import com.zoneu.app.ui.components.ProviderModelIcon

/**
 * 设置页共用的小部件：分组卡片、行、状态徽章、提供商头像、顶栏、底部页签。
 *
 * 视觉对齐参考图：浅灰页面 + 白色圆角分组卡片 + 线稿图标 + 语义色徽章。
 * 颜色不用 Material You 动态取色（全局主题已经这么定了），保证品牌色不漂移。
 */
@Composable
internal fun settingsPageColor(): Color =
  if (isSystemInDarkTheme()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer

@Composable
internal fun settingsCardColor(): Color =
  if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

@Composable
internal fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp),
  )
}

@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = settingsCardColor(),
  ) {
    Column { content() }
  }
}

@Composable
internal fun SettingsDivider() {
  HorizontalDivider(
    modifier = Modifier.padding(start = 56.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
  )
}

/**
 * 一行设置。[onClick] 为空或 [enabled] 为假时不可点；[trailing] 放状态徽章或箭头。
 * 图标与文案对不齐是这类页面最常见的视觉 bug —— 图标固定 24dp、标题 16sp，行高统一 64dp 起。
 */
@Composable
internal fun SettingsRow(
  glyph: GlyphKind,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val clickable = onClick != null && enabled
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Glyph(
      kind = glyph,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
    )
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        color =
          if (enabled) {
            MaterialTheme.colorScheme.onSurface
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
          },
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(12.dp))
    trailing()
  }
}

@Composable
internal fun ChevronRight() {
  Glyph(
    kind = GlyphKind.Back,
    modifier = Modifier.size(18.dp).rotate(180f),
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
  )
}

internal enum class BadgeTone {
  Enabled,
  Disabled,
  Info,
  Muted,
}

/** 语义色徽章：绿=启用、琥珀=禁用、蓝=数量/信息、灰=未支持。深浅色各一套，保证对比度。 */
@Composable
internal fun StatusBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
  val dark = isSystemInDarkTheme()
  val (background, foreground) =
    when (tone) {
      BadgeTone.Enabled ->
        if (dark) Color(0xFF1B3A2A) to Color(0xFF86E0AC) else Color(0xFFDDF3E4) to Color(0xFF176B3A)

      BadgeTone.Disabled ->
        if (dark) Color(0xFF3B2F1A) to Color(0xFFF2C77E) else Color(0xFFFCEFD9) to Color(0xFF8A5A12)

      BadgeTone.Info ->
        if (dark) Color(0xFF1C2B42) to Color(0xFFA6C8FF) else Color(0xFFDDEBFF) to Color(0xFF1D4F91)

      BadgeTone.Muted ->
        if (dark) Color(0xFF262932) to Color(0xFF9BA1AC) else Color(0xFFECEEF2) to Color(0xFF5C6470)
    }
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = foreground,
    modifier =
      modifier
        .clip(RoundedCornerShape(50))
        .background(background)
        .padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

/** 提供商头像：品牌色圆 + 字母（参考图用 logo；这里用字母，免得为几个图标引资源包）。 */
@Composable
internal fun ProviderAvatar(
  preset: LlmPreset,
  name: String,
  modifier: Modifier = Modifier,
  size: Int = 44,
) {
  ProviderModelIcon(
    preset = preset,
    name = name,
    modifier = modifier,
    size = size,
    shape = CircleShape,
  )
}

@Composable
internal fun BackCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .size(44.dp)
        .shadow(1.dp, CircleShape)
        .clip(CircleShape)
        .background(settingsCardColor())
        .clickable(onClick = onClick)
        .semantics { contentDescription = "返回" },
    contentAlignment = Alignment.Center,
  ) {
    Glyph(GlyphKind.Back, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
internal fun SettingsHeader(
  title: String,
  onBack: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BackCircle(onBack)
    Spacer(Modifier.width(16.dp))
    Text(
      text = title,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    actions()
  }
}

@Composable
internal fun BigTitle(title: String, subtitle: String? = null) {
  // 不在这里加水平内边距：调用方（ProvidersScreen 的滚动列）已经有了 16dp，
  // 再加一层会让标题比搜索框和卡片多缩进 16dp。
  Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.SemiBold,
    )
    if (subtitle != null) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier) {
  AppBanner(text = text, tone = AppBanner.Tone.Info, modifier = modifier)
}

/** 提供商详情页的三个页签。 */
internal enum class DetailTab(val label: String, val glyph: GlyphKind) {
  Config("配置", GlyphKind.Sliders),
  Models("模型", GlyphKind.Box),
  Advanced("高级设置", GlyphKind.Cog),
}

/** 底部页签条：选中的一项带一层淡色药丸底，与参考图一致。 */
@Composable
internal fun DetailTabBar(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(24.dp))
        .background(settingsCardColor())
        .padding(6.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    DetailTab.entries.forEach { tab ->
      val active = tab == selected
      Column(
        modifier =
          Modifier.weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(
              if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = if (isSystemInDarkTheme()) 0.22f else 0.10f)
              } else {
                Color.Transparent
              },
            )
            .clickable { onSelect(tab) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Glyph(
          kind = tab.glyph,
          modifier = Modifier.size(21.dp),
          tint =
            if (active) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
          text = tab.label,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (active) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }
  }
}

/** 提供商摘要：设置首页与「模型与服务」页共用，两处不能说出不同的状态。 */
internal fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "未启用 —— 选一个提供商并填写 API Key"
  val keyHint = if (state.configured) "" else " · 缺 API Key"
  val model = state.providerModel.ifBlank { "未选择模型" }
  return "$name · $model$keyHint"
}

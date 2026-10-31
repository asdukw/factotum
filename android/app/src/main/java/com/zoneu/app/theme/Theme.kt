package com.zoneu.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme =
  lightColorScheme(
    primary = ZoneuBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9FF),
    onPrimaryContainer = Color(0xFF14224F),
    secondaryContainer = LightUserBubble,
    onSecondaryContainer = LightUserBubbleText,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8FA),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFEEF0F3),
    surfaceContainerHighest = Color(0xFFE9EBEF),
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = ZoneuBlueLight,
    onPrimary = Color(0xFF10142E),
    primaryContainer = Color(0xFF2A3149),
    onPrimaryContainer = Color(0xFFE7EAF6),
    secondaryContainer = DarkUserBubble,
    onSecondaryContainer = DarkUserBubbleText,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = Color(0xFF0D0E12),
    surfaceContainerLow = Color(0xFF141519),
    surfaceContainer = Color(0xFF1B1D23),
    surfaceContainerHigh = Color(0xFF22242B),
    surfaceContainerHighest = Color(0xFF292C34),
  )

/** 圆角只有五档：卡片与输入区用 large，小控件用 small，别在页面里随手写 14/18/22。 */
private val ZoneuShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

/**
 * 当前是否深色，以**主题**为准而不是 `isSystemInDarkTheme()`。
 * 页面直接问系统，会在将来加主题切换时出现「主题是深色、页面按浅色画」的混搭。
 */
val LocalZoneuDarkTheme = staticCompositionLocalOf { false }

/** M3 的 colorScheme 没有成功/警告两档，而横幅要用它们区分「做成了」和「要注意」。 */
@Immutable
data class ZoneuColors(
  val success: Color,
  val warning: Color,
)

val LocalZoneuColors =
  staticCompositionLocalOf {
    ZoneuColors(success = LightSuccess, warning = LightWarning)
  }

val MaterialTheme.zoneuColors: ZoneuColors
  @Composable @ReadOnlyComposable
  get() = LocalZoneuColors.current

@Composable
fun ZoneuTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalZoneuDarkTheme provides darkTheme,
    LocalZoneuColors provides
      if (darkTheme) {
        ZoneuColors(success = DarkSuccess, warning = DarkWarning)
      } else {
        ZoneuColors(success = LightSuccess, warning = LightWarning)
      },
  ) {
    MaterialExpressiveTheme(
      colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
      motionScheme = MotionScheme.expressive(),
      typography = Typography,
      shapes = ZoneuShapes,
      content = content,
    )
  }
}

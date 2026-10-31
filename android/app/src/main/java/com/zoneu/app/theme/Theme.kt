package com.zoneu.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    outlineVariant = LightOutline,
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
    outlineVariant = DarkOutline,
    surfaceContainerLowest = Color(0xFF0D0E12),
    surfaceContainerLow = Color(0xFF141519),
    surfaceContainer = Color(0xFF1B1D23),
    surfaceContainerHigh = Color(0xFF22242B),
    surfaceContainerHighest = Color(0xFF292C34),
  )

@Composable
fun ZoneuTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialExpressiveTheme(
    colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
    motionScheme = MotionScheme.expressive(),
    typography = Typography,
    content = content,
  )
}

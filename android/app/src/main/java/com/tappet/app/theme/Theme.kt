package com.tappet.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme =
  lightColorScheme(
    primary = TappetBlue,
    onPrimary = Color.White,
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
    primary = TappetBlueLight,
    onPrimary = Color(0xFF10142E),
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
fun TappetTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme, typography = Typography, content = content)
}

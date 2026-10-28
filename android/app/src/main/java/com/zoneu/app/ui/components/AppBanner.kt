package com.zoneu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared semantic banner styles used for persistent app feedback. */
object AppBanner {
  enum class Tone {
    Info,
    Success,
    Warning,
    Error,
  }

  @Composable
  operator fun invoke(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
  ) {
    val color = MaterialTheme.colorScheme
    val (background, foreground) =
      when (tone) {
        Tone.Info -> color.primary.copy(alpha = 0.08f) to color.onSurfaceVariant
        Tone.Success -> Color(0xFFDDF3E4) to Color(0xFF176B3A)
        Tone.Warning -> Color(0xFFFCEFD9) to Color(0xFF8A5A12)
        Tone.Error -> color.errorContainer to color.onErrorContainer
      }
    Row(
      modifier =
        modifier
          .fillMaxWidth()
          .background(background, RoundedCornerShape(14.dp))
          .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      Text(text = text, style = MaterialTheme.typography.bodySmall, color = foreground)
    }
  }
}

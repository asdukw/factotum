package com.factotum.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.factotum.app.agent.LlmPreset

/**
 * 提供商品牌色与字母头像。设置页（圆形）与聊天页模型卡（圆角方形）共用同一套，
 * 免得两处各抄一份颜色表。
 */
internal fun providerBrandColor(preset: LlmPreset): Color =
  when (preset) {
    LlmPreset.OpenCodeGo -> Color(0xFF4F46E5)
    LlmPreset.OpenAi -> Color(0xFF059669)
    LlmPreset.Anthropic -> Color(0xFFD97706)
    LlmPreset.DeepSeek -> Color(0xFF2563EB)
    LlmPreset.OpenRouter -> Color(0xFF7C3AED)
    LlmPreset.Custom -> Color(0xFF64748B)
  }

internal fun providerMonogram(preset: LlmPreset, name: String): String =
  when (preset) {
    LlmPreset.OpenCodeGo -> "OC"
    LlmPreset.OpenAi -> "O"
    LlmPreset.Anthropic -> "A"
    LlmPreset.DeepSeek -> "D"
    LlmPreset.OpenRouter -> "R"
    LlmPreset.Custom -> name.trim().take(1).ifBlank { "+" }.uppercase()
  }

/** 品牌色底 + 字母。size 是整体边长，字号跟它缩放，24–44dp 都好看。 */
@Composable
internal fun ProviderModelIcon(
  preset: LlmPreset,
  name: String,
  modifier: Modifier = Modifier,
  size: Int = 40,
  shape: Shape = RoundedCornerShape(12.dp),
) {
  val monogram = providerMonogram(preset, name)
  Box(
    modifier = modifier.size(size.dp).clip(shape).background(providerBrandColor(preset)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = monogram,
      color = Color.White,
      fontSize = (size * if (monogram.length > 1) 0.30f else 0.40f).sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

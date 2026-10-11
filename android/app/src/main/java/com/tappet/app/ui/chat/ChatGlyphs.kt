package com.tappet.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 极简线条图标，全部用 Canvas 画：不引 material-icons 依赖，也和参考 App
 * （ChatGPT / DeepSeek）的细线条风格一致。线宽按画布尺寸缩放，22dp 下约 2dp。
 */
private fun strokeWidth(minDimension: Float) = minDimension * 0.09f

@Composable
internal fun MenuGlyph(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val left = size.width * 0.18f
    val right = size.width * 0.82f
    listOf(0.3f, 0.5f, 0.7f).forEach { y ->
      drawLine(tint, Offset(left, size.height * y), Offset(right, size.height * y), strokeWidth = w, cap = StrokeCap.Round)
    }
  }
}

@Composable
internal fun PlusGlyph(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val arm = size.minDimension * 0.32f
    drawLine(tint, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = w, cap = StrokeCap.Round)
  }
}

@Composable
internal fun ArrowUpGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val cx = size.width / 2f
    val top = size.height * 0.22f
    val bottom = size.height * 0.8f
    drawLine(tint, Offset(cx, bottom), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx - size.width * 0.22f, top + size.height * 0.22f), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx + size.width * 0.22f, top + size.height * 0.22f), Offset(cx, top), strokeWidth = w, cap = StrokeCap.Round)
  }
}

@Composable
internal fun StopGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
  Canvas(modifier) {
    val side = size.minDimension * 0.42f
    drawRoundRect(
      color = tint,
      topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
      size = Size(side, side),
      cornerRadius = CornerRadius(side * 0.18f),
    )
  }
}

@Composable
internal fun ChevronDownGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val cx = size.width / 2f
    val mid = size.height * 0.62f
    drawLine(tint, Offset(size.width * 0.28f, size.height * 0.4f), Offset(cx, mid), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(tint, Offset(cx, mid), Offset(size.width * 0.72f, size.height * 0.4f), strokeWidth = w, cap = StrokeCap.Round)
  }
}

@Composable
internal fun CopyGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val corner = CornerRadius(size.minDimension * 0.14f)
    drawRoundRect(
      color = tint,
      topLeft = Offset(size.width * 0.3f, size.height * 0.3f),
      size = Size(size.width * 0.5f, size.height * 0.5f),
      cornerRadius = corner,
      style = Stroke(width = w),
    )
    drawRoundRect(
      color = tint,
      topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
      size = Size(size.width * 0.5f, size.height * 0.5f),
      cornerRadius = corner,
      style = Stroke(width = w),
    )
  }
}

@Composable
internal fun CheckGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    drawLine(tint, Offset(size.width * 0.22f, size.height * 0.55f), Offset(size.width * 0.42f, size.height * 0.74f), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(tint, Offset(size.width * 0.42f, size.height * 0.74f), Offset(size.width * 0.8f, size.height * 0.28f), strokeWidth = w, cap = StrokeCap.Round)
  }
}

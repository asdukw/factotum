package com.factotum.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

/** 工具行的小图标：抽象成「斜柄 + 顶环」，小尺寸下也能认出是工具。 */
@Composable
internal fun ToolGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    drawLine(
      tint,
      Offset(size.width * 0.22f, size.height * 0.78f),
      Offset(size.width * 0.62f, size.height * 0.38f),
      strokeWidth = w,
      cap = StrokeCap.Round,
    )
    drawCircle(
      tint,
      radius = size.minDimension * 0.17f,
      center = Offset(size.width * 0.74f, size.height * 0.26f),
      style = Stroke(width = w),
    )
  }
}

/** 思考行的小图标：灯泡（圆 + 灯座两道线）。 */
@Composable
internal fun BulbGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    drawCircle(
      tint,
      radius = size.minDimension * 0.26f,
      center = Offset(size.width / 2f, size.height * 0.4f),
      style = Stroke(width = w),
    )
    drawLine(
      tint,
      Offset(size.width * 0.38f, size.height * 0.74f),
      Offset(size.width * 0.62f, size.height * 0.74f),
      strokeWidth = w,
      cap = StrokeCap.Round,
    )
    drawLine(
      tint,
      Offset(size.width * 0.43f, size.height * 0.86f),
      Offset(size.width * 0.57f, size.height * 0.86f),
      strokeWidth = w,
      cap = StrokeCap.Round,
    )
  }
}

/** 设置：齿轮（细线条，与抽屉其它图标同一套线宽）。 */
@Composable
internal fun CogGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val core = size.minDimension * 0.24f
    drawCircle(tint, core, Offset(cx, cy), style = Stroke(w))
    repeat(8) { index ->
      val angle = Math.PI / 4 * index
      val cosA = kotlin.math.cos(angle).toFloat()
      val sinA = kotlin.math.sin(angle).toFloat()
      val inner = size.minDimension * 0.33f
      val outer = size.minDimension * 0.46f
      drawLine(
        tint,
        Offset(cx + cosA * inner, cy + sinA * inner),
        Offset(cx + cosA * outer, cy + sinA * outer),
        strokeWidth = w,
        cap = StrokeCap.Round,
      )
    }
  }
}

/** 搜索：圆 + 手柄。 */
@Composable
internal fun SearchGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val radius = size.minDimension * 0.28f
    val cx = size.width * 0.44f
    val cy = size.height * 0.44f
    drawCircle(tint, radius, Offset(cx, cy), style = Stroke(w))
    drawLine(
      tint,
      Offset(cx + radius * 0.72f, cy + radius * 0.72f),
      Offset(size.width * 0.82f, size.height * 0.82f),
      strokeWidth = w,
      cap = StrokeCap.Round,
    )
  }
}

/** 朗读：喇叭（梯形箱体 + 两道声波）。 */
@Composable
internal fun SpeakerGlyph(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val box =
      Path().apply {
        moveTo(size.width * 0.12f, size.height * 0.36f)
        lineTo(size.width * 0.3f, size.height * 0.36f)
        lineTo(size.width * 0.52f, size.height * 0.16f)
        lineTo(size.width * 0.52f, size.height * 0.84f)
        lineTo(size.width * 0.3f, size.height * 0.64f)
        lineTo(size.width * 0.12f, size.height * 0.64f)
        close()
      }
    drawPath(box, tint, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // 两道弧线一近一远：只有一道时小尺寸下像个「旗子」。
    drawArc(
      color = tint,
      startAngle = -52f,
      sweepAngle = 104f,
      useCenter = false,
      topLeft = Offset(size.width * 0.5f, size.height * 0.32f),
      size = Size(size.width * 0.28f, size.height * 0.36f),
      style = Stroke(w, cap = StrokeCap.Round),
    )
    drawArc(
      color = tint,
      startAngle = -52f,
      sweepAngle = 104f,
      useCenter = false,
      topLeft = Offset(size.width * 0.5f, size.height * 0.16f),
      size = Size(size.width * 0.46f, size.height * 0.68f),
      style = Stroke(w, cap = StrokeCap.Round),
    )
  }
}

/** 麦克风：一个胶囊头 + 半圆底座 + 一根柄。语音输入的入口。 */
@Composable
internal fun MicGlyph(tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
  Canvas(modifier) {
    val w = strokeWidth(size.minDimension)
    val cx = size.width / 2f
    val headTop = size.height * 0.16f
    val headBottom = size.height * 0.58f
    val headWidth = size.width * 0.34f
    drawRoundRect(
      color = tint,
      topLeft = Offset(cx - headWidth / 2f, headTop),
      size = Size(headWidth, headBottom - headTop),
      cornerRadius = CornerRadius(headWidth / 2f),
      style = Stroke(width = w),
    )
    // 底座：一段开口向上的弧
    val arcSize = Size(size.width * 0.56f, size.height * 0.5f)
    drawArc(
      color = tint,
      startAngle = 0f,
      sweepAngle = 180f,
      useCenter = false,
      topLeft = Offset((size.width - arcSize.width) / 2f, size.height * 0.28f),
      size = arcSize,
      style = Stroke(width = w, cap = StrokeCap.Round),
    )
    drawLine(
      tint,
      Offset(cx, size.height * 0.78f),
      Offset(cx, size.height * 0.9f),
      strokeWidth = w,
      cap = StrokeCap.Round,
    )
  }
}

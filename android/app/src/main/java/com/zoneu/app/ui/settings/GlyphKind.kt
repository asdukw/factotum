package com.zoneu.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 设置页的线稿图标。
 *
 * 为什么不用 `material-icons-extended`：Material3 1.4 不再传递依赖 icon 包（BOM 里也没有），
 * 为一个设置页引一整包图标（数千个矢量）不划算。这里用 24×24 的画布手绘十来个 1.8dp 线稿，
 * 与参考图的细线风格一致，也免得多一个上游依赖。
 */
internal enum class GlyphKind {
  Back,
  Search,
  More,
  Check,
  Plus,
  Person,
  Box,
  Sparkles,
  Chip,
  Globe,
  Mic,
  Hub,
  Server,
  Mail,
  Accessibility,
  Key,
  Info,
  Eye,
  EyeOff,
  Sliders,
  Cog,
  Speaker,
  Clock,
}

@Composable
internal fun Glyph(
  kind: GlyphKind,
  modifier: Modifier = Modifier.size(22.dp),
  tint: Color = LocalContentColor.current,
) {
  Canvas(modifier) {
    val u = size.minDimension / VIEWPORT
    drawGlyph(kind, tint, u)
  }
}

private const val VIEWPORT = 24f
private const val LINE_WIDTH = 1.8f

private fun DrawScope.drawGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    GlyphKind.Back,
    GlyphKind.Search,
    GlyphKind.More,
    GlyphKind.Check,
    GlyphKind.Plus,
    -> drawBasicGlyph(kind, tint, u)

    GlyphKind.Person,
    GlyphKind.Accessibility,
    -> drawHumanGlyph(kind, tint, u)

    GlyphKind.Box,
    GlyphKind.Sparkles,
    -> drawShapeGlyph(kind, tint, u)

    GlyphKind.Chip,
    GlyphKind.Globe,
    GlyphKind.Mic,
    GlyphKind.Hub,
    GlyphKind.Server,
    GlyphKind.Mail,
    -> drawObjectGlyph(kind, tint, u)

    GlyphKind.Key,
    GlyphKind.Info,
    GlyphKind.Eye,
    GlyphKind.EyeOff,
    GlyphKind.Sliders,
    GlyphKind.Cog,
    GlyphKind.Speaker,
    GlyphKind.Clock,
    -> drawActionGlyph(kind, tint, u)
  }
}

private fun DrawScope.drawBasicGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    GlyphKind.Back -> {
      line(tint, u, 14.5f, 5f, 7.5f, 12f)
      line(tint, u, 7.5f, 12f, 14.5f, 19f)
    }

    GlyphKind.Search -> {
      circle(tint, u, 10.5f, 10.5f, 6f)
      line(tint, u, 15f, 15f, 20f, 20f)
    }

    GlyphKind.More -> {
      fillCircle(tint, u, 12f, 5.5f, 1.5f)
      fillCircle(tint, u, 12f, 12f, 1.5f)
      fillCircle(tint, u, 12f, 18.5f, 1.5f)
    }

    GlyphKind.Check -> {
      line(tint, u, 5f, 12.5f, 10f, 17.5f)
      line(tint, u, 10f, 17.5f, 19f, 6.5f)
    }

    GlyphKind.Plus -> {
      line(tint, u, 12f, 5f, 12f, 19f)
      line(tint, u, 5f, 12f, 19f, 12f)
    }

    else -> Unit
  }
}

private fun DrawScope.drawHumanGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    GlyphKind.Person -> {
      circle(tint, u, 12f, 8.5f, 3.5f)
      strokePath(tint, u) {
        moveTo(5.5f * u, 19.5f * u)
        quadraticTo(8f * u, 13.5f * u, 12f * u, 13.5f * u)
        quadraticTo(16f * u, 13.5f * u, 18.5f * u, 19.5f * u)
      }
    }

    GlyphKind.Accessibility -> {
      circle(tint, u, 12f, 4.8f, 2f)
      line(tint, u, 5f, 9f, 19f, 9f)
      line(tint, u, 12f, 7.5f, 12f, 15f)
      line(tint, u, 12f, 15f, 7.5f, 21f)
      line(tint, u, 12f, 15f, 16.5f, 21f)
    }

    else -> Unit
  }
}

private fun DrawScope.drawShapeGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    GlyphKind.Box -> {
      strokePath(tint, u) {
        moveTo(12f * u, 3f * u)
        lineTo(20f * u, 7.5f * u)
        lineTo(20f * u, 16.5f * u)
        lineTo(12f * u, 21f * u)
        lineTo(4f * u, 16.5f * u)
        lineTo(4f * u, 7.5f * u)
        close()
        moveTo(4f * u, 7.5f * u)
        lineTo(12f * u, 12f * u)
        lineTo(20f * u, 7.5f * u)
        moveTo(12f * u, 12f * u)
        lineTo(12f * u, 21f * u)
      }
    }

    GlyphKind.Sparkles -> {
      strokePath(tint, u) {
        moveTo(11f * u, 3f * u)
        lineTo(13f * u, 9.5f * u)
        lineTo(19.5f * u, 11.5f * u)
        lineTo(13f * u, 13.5f * u)
        lineTo(11f * u, 20f * u)
        lineTo(9f * u, 13.5f * u)
        lineTo(2.5f * u, 11.5f * u)
        lineTo(9f * u, 9.5f * u)
        close()
      }
      line(tint, u, 19f, 3.5f, 19f, 6.5f)
      line(tint, u, 17.5f, 5f, 20.5f, 5f)
    }

    else -> Unit
  }
}

private fun DrawScope.drawObjectGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    // 信封：一个圆角矩形 + 两条折线当封口。连接器那一组用它，比复用 Globe 好认
    // （Globe 已经被「搜索服务」占着，两行同样的图标在同一个设置页里会看着像重复项）。
    GlyphKind.Mail -> {
      roundRect(tint, u, 3f, 6f, 21f, 18f, 2f)
      line(tint, u, 3.5f, 7f, 12f, 13f)
      line(tint, u, 12f, 13f, 20.5f, 7f)
    }

    GlyphKind.Chip -> {
      roundRect(tint, u, 6f, 6f, 18f, 18f, 2.5f)
      roundRect(tint, u, 9.5f, 9.5f, 14.5f, 14.5f, 1.2f)
      listOf(9f, 15f).forEach { x ->
        line(tint, u, x, 3f, x, 6f)
        line(tint, u, x, 18f, x, 21f)
      }
      listOf(9f, 15f).forEach { y ->
        line(tint, u, 3f, y, 6f, y)
        line(tint, u, 18f, y, 21f, y)
      }
    }

    GlyphKind.Globe -> {
      circle(tint, u, 12f, 12f, 9f)
      drawOval(
        tint,
        topLeft = Offset(8f * u, 3f * u),
        size = Size(8f * u, 18f * u),
        style = Stroke(LINE_WIDTH * u),
      )
      line(tint, u, 3f, 12f, 21f, 12f)
    }

    GlyphKind.Mic -> {
      roundRect(tint, u, 9f, 3f, 15f, 13f, 3f)
      drawArc(
        tint,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(6f * u, 8f * u),
        size = Size(12f * u, 12f * u),
        style = Stroke(LINE_WIDTH * u, cap = StrokeCap.Round),
      )
      line(tint, u, 12f, 20f, 12f, 22f)
      line(tint, u, 9f, 22f, 15f, 22f)
    }

    GlyphKind.Hub -> {
      circle(tint, u, 12f, 12f, 2.5f)
      circle(tint, u, 5f, 5f, 2.2f)
      circle(tint, u, 19f, 5f, 2.2f)
      circle(tint, u, 19f, 19f, 2.2f)
      line(tint, u, 6.5f, 6.5f, 10f, 10f)
      line(tint, u, 17.5f, 6.5f, 14f, 10f)
      line(tint, u, 17.5f, 17.5f, 14f, 14f)
    }

    GlyphKind.Server -> {
      roundRect(tint, u, 4f, 5f, 20f, 10f, 2f)
      roundRect(tint, u, 4f, 14f, 20f, 19f, 2f)
      fillCircle(tint, u, 7.5f, 7.5f, 1.2f)
      fillCircle(tint, u, 7.5f, 16.5f, 1.2f)
    }

    else -> Unit
  }
}

private fun DrawScope.drawActionGlyph(kind: GlyphKind, tint: Color, u: Float) {
  when (kind) {
    GlyphKind.Key -> {
      circle(tint, u, 8f, 12f, 4.5f)
      line(tint, u, 12.5f, 12f, 21f, 12f)
      line(tint, u, 17f, 12f, 17f, 15.5f)
      line(tint, u, 20f, 12f, 20f, 15f)
    }

    GlyphKind.Info -> {
      circle(tint, u, 12f, 12f, 9f)
      line(tint, u, 12f, 11f, 12f, 17f)
      fillCircle(tint, u, 12f, 7.5f, 1.3f)
    }

    GlyphKind.Eye -> {
      strokePath(tint, u) {
        moveTo(3f * u, 12f * u)
        quadraticTo(7.5f * u, 5f * u, 12f * u, 5f * u)
        quadraticTo(16.5f * u, 5f * u, 21f * u, 12f * u)
        quadraticTo(16.5f * u, 19f * u, 12f * u, 19f * u)
        quadraticTo(7.5f * u, 19f * u, 3f * u, 12f * u)
        close()
      }
      circle(tint, u, 12f, 12f, 3f)
    }

    GlyphKind.EyeOff -> {
      strokePath(tint, u) {
        moveTo(3f * u, 12f * u)
        quadraticTo(7.5f * u, 5f * u, 12f * u, 5f * u)
        quadraticTo(16.5f * u, 5f * u, 21f * u, 12f * u)
        quadraticTo(16.5f * u, 19f * u, 12f * u, 19f * u)
        quadraticTo(7.5f * u, 19f * u, 3f * u, 12f * u)
        close()
      }
      circle(tint, u, 12f, 12f, 3f)
      line(tint, u, 4f, 4f, 20f, 20f)
    }

    GlyphKind.Sliders -> {
      listOf(6f, 12f, 18f).forEach { y -> line(tint, u, 4f, y, 20f, y) }
      fillCircle(tint, u, 9f, 6f, 2f)
      fillCircle(tint, u, 15f, 12f, 2f)
      fillCircle(tint, u, 8f, 18f, 2f)
    }

    GlyphKind.Speaker -> drawSpeakerGlyph(tint, u)

    // 时钟：表盘 + 时针 / 分针。定时任务那一行用它，比复用 Cog 更像「时间」。
    GlyphKind.Clock -> {
      circle(tint, u, 12f, 12f, 8.5f)
      line(tint, u, 12f, 12f, 12f, 7f)
      line(tint, u, 12f, 12f, 16f, 13.5f)
    }

    GlyphKind.Cog -> {
      circle(tint, u, 12f, 12f, 3.2f)
      repeat(8) { index ->
        val angle = index * (Math.PI / 4).toFloat()
        line(
          tint,
          u,
          12f + cos(angle) * 6f,
          12f + sin(angle) * 6f,
          12f + cos(angle) * 8.5f,
          12f + sin(angle) * 8.5f,
        )
      }
    }

    else -> Unit
  }
}

/** 喇叭：箱体 + 两道声波。与聊天页那个 16dp 的是同一形状，尺寸不同而已。 */
private fun DrawScope.drawSpeakerGlyph(tint: Color, u: Float) {
  strokePath(tint, u) {
    moveTo(3.5f * u, 9f * u)
    lineTo(7f * u, 9f * u)
    lineTo(11.5f * u, 4.5f * u)
    lineTo(11.5f * u, 19.5f * u)
    lineTo(7f * u, 15f * u)
    lineTo(3.5f * u, 15f * u)
    close()
  }
  // 两道弧线一近一远：只有一道时小尺寸下像个「旗子」。
  drawArc(
    tint,
    startAngle = -50f,
    sweepAngle = 100f,
    useCenter = false,
    topLeft = Offset(10f * u, 8.5f * u),
    size = Size(6f * u, 7f * u),
    style = Stroke(LINE_WIDTH * u, cap = StrokeCap.Round),
  )
  drawArc(
    tint,
    startAngle = -50f,
    sweepAngle = 100f,
    useCenter = false,
    topLeft = Offset(10f * u, 5f * u),
    size = Size(11f * u, 14f * u),
    style = Stroke(LINE_WIDTH * u, cap = StrokeCap.Round),
  )
}

private fun DrawScope.line(
  tint: Color,
  u: Float,
  x1: Float,
  y1: Float,
  x2: Float,
  y2: Float,
  width: Float = LINE_WIDTH,
) {
  drawLine(
    color = tint,
    start = Offset(x1 * u, y1 * u),
    end = Offset(x2 * u, y2 * u),
    strokeWidth = width * u,
    cap = StrokeCap.Round,
  )
}

private fun DrawScope.circle(tint: Color, u: Float, cx: Float, cy: Float, radius: Float) {
  drawCircle(
    color = tint,
    radius = radius * u,
    center = Offset(cx * u, cy * u),
    style = Stroke(LINE_WIDTH * u),
  )
}

private fun DrawScope.fillCircle(tint: Color, u: Float, cx: Float, cy: Float, radius: Float) {
  drawCircle(color = tint, radius = radius * u, center = Offset(cx * u, cy * u))
}

private fun DrawScope.roundRect(
  tint: Color,
  u: Float,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  radius: Float,
) {
  drawRoundRect(
    color = tint,
    topLeft = Offset(left * u, top * u),
    size = Size((right - left) * u, (bottom - top) * u),
    cornerRadius = CornerRadius(radius * u),
    style = Stroke(LINE_WIDTH * u),
  )
}

private fun DrawScope.strokePath(tint: Color, u: Float, build: Path.() -> Unit) {
  val path = Path().apply(build)
  drawPath(
    path = path,
    color = tint,
    style =
      Stroke(
        width = LINE_WIDTH * u,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
      ),
  )
}

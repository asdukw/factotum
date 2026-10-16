package com.tappet.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tappet.app.R
import com.tappet.app.progress.AgentProgressCenter
import com.tappet.app.progress.AgentProgressEntry
import com.tappet.app.progress.AgentProgressKind
import com.tappet.app.progress.AgentProgressState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 无障碍 overlay 上的「角色球 + 进度气泡」。
 *
 * 它只订阅进程内的 AgentProgressCenter，不解析页面，也不持有任务语义。使用
 * TYPE_ACCESSIBILITY_OVERLAY，因此不需要额外的悬浮窗权限，但要求用户已启用 Tappet
 * 无障碍服务。
 */
internal class AgentOverlayController(private val service: AccessibilityService) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  private val prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val params =
    WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT,
    )
      .apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(KEY_X, dp(8))
        y = prefs.getInt(KEY_Y, (service.resources.displayMetrics.heightPixels * 0.26f).toInt())
        title = "Tappet progress"
      }

  private var job: Job? = null
  private var root: LinearLayout? = null
  private var bubble: LinearLayout? = null
  private var status: TextView? = null
  private var entries: TextView? = null
  private var collapsed = false

  fun start() {
    if (job != null) return
    job =
      scope.launch {
        AgentProgressCenter.state.collectLatest { state ->
          if (state == null) hide() else render(state)
        }
      }
  }

  fun stop() {
    job?.cancel()
    job = null
    scope.cancel()
    hide()
  }

  fun onConfigurationChanged() {
    root?.post { clampAndApply() }
  }

  private fun render(state: AgentProgressState) {
    val view = ensureView()
    status?.text = if (state.running) "Tappet · 执行中" else "Tappet · 已完成"
    entries?.text = buildProgressText(state.entries)
    bubble?.visibility = if (collapsed) View.GONE else View.VISIBLE
    view.post { clampAndApply() }
  }

  private fun ensureView(): LinearLayout {
    root?.let { return it }
    val bubbleView =
      LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(10))
        background = roundedBackground(BUBBLE_COLOR, dp(17), STROKE_COLOR)
        elevation = dp(10).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
      }
    status =
      TextView(service).apply {
        setTextColor(HEADER_COLOR)
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
      }
    entries =
      TextView(service).apply {
        setTextColor(BODY_COLOR)
        textSize = 13f
        maxLines = 7
        ellipsize = android.text.TextUtils.TruncateAt.END
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
      }
    bubbleView.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    bubbleView.addView(
      entries,
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(5)
      },
    )

    val avatar =
      CircularImageView(service).apply {
        setImageResource(R.drawable.tappet_mascot)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setPadding(dp(3), dp(3), dp(3), dp(3))
        background = roundedBackground(Color.WHITE, dp(34), Color.TRANSPARENT)
        elevation = dp(10).toFloat()
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }

    val view =
      LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
        setPadding(dp(4), dp(5), dp(4), dp(5))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
      }
    view.addView(
      bubbleView,
      LinearLayout.LayoutParams(dp(246), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        rightMargin = dp(7)
      },
    )
    view.addView(avatar, LinearLayout.LayoutParams(dp(66), dp(66)))
    view.setOnTouchListener(makeDragListener(view))
    bubbleView.setOnTouchListener(makeDragListener(view))
    avatar.setOnTouchListener(makeDragListener(view))
    bubble = bubbleView
    root = view

    val added = runCatching { windowManager.addView(view, params) }
    if (added.isFailure) {
      Log.w(TAG, "overlay add failed: ${added.exceptionOrNull()?.javaClass?.simpleName}")
      bubble = null
      root = null
      return view
    }
    return view
  }

  private fun hide() {
    val view = root ?: return
    runCatching { windowManager.removeViewImmediate(view) }
    root = null
    bubble = null
    status = null
    entries = null
    collapsed = false
  }

  private fun buildProgressText(items: List<AgentProgressEntry>): CharSequence {
    val out = SpannableStringBuilder()
    items.takeLast(3).forEach { item ->
      if (out.isNotEmpty()) out.append('\n')
      val marker =
        when (item.kind) {
          AgentProgressKind.STEP -> "▸"
          AgentProgressKind.OUTPUT -> "✓"
          AgentProgressKind.ERROR -> "!"
        }
      val titleStart = out.length
      out.append(marker).append(' ').append(item.title)
      out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), titleStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      out.setSpan(
        ForegroundColorSpan(if (item.kind == AgentProgressKind.ERROR) ERROR_COLOR else TITLE_COLOR),
        titleStart,
        out.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
      item.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        out.append('\n').append("   ").append(detail)
      }
    }
    return out
  }

  private fun makeDragListener(view: View): View.OnTouchListener {
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    return View.OnTouchListener { _, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX
          downY = event.rawY
          startX = params.x
          startY = params.y
          true
        }

        MotionEvent.ACTION_MOVE -> {
          params.x = startX + (event.rawX - downX).toInt()
          params.y = startY + (event.rawY - downY).toInt()
          runCatching { windowManager.updateViewLayout(view, params) }
          true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          val moved = abs(event.rawX - downX) > dp(5) || abs(event.rawY - downY) > dp(5)
          if (!moved) toggleExpanded(view)
          clampAndApply()
          prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
          true
        }

        else -> false
      }
    }
  }

  private fun toggleExpanded(view: View) {
    collapsed = !collapsed
    bubble?.visibility = if (collapsed) View.GONE else View.VISIBLE
    view.post { clampAndApply() }
  }

  private fun clampAndApply() {
    val view = root ?: return
    if (view.width <= 0 || view.height <= 0) return
    val metrics = service.resources.displayMetrics
    params.x = params.x.coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
    params.y = params.y.coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
    runCatching { windowManager.updateViewLayout(view, params) }
  }

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()

  private fun roundedBackground(color: Int, radius: Int, strokeColor: Int): GradientDrawable =
    GradientDrawable().apply {
      shape = GradientDrawable.RECTANGLE
      setColor(color)
      cornerRadius = radius.toFloat()
      if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
    }

  private class CircularImageView(context: Context) : ImageView(context) {
    private val clip = Path()

    override fun onDraw(canvas: android.graphics.Canvas) {
      val save = canvas.save()
      clip.reset()
      clip.addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
      canvas.clipPath(clip)
      super.onDraw(canvas)
      canvas.restoreToCount(save)
    }
  }

  private companion object {
    const val TAG = "TappetDevice"
    const val PREFS = "agent_overlay"
    const val KEY_X = "x"
    const val KEY_Y = "y"
    val BUBBLE_COLOR = Color.argb(238, 17, 24, 42)
    val STROKE_COLOR = Color.argb(96, 137, 174, 255)
    val HEADER_COLOR = Color.rgb(166, 196, 255)
    val BODY_COLOR = Color.rgb(222, 229, 247)
    val TITLE_COLOR = Color.WHITE
    val ERROR_COLOR = Color.rgb(255, 157, 157)
  }
}

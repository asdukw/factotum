package com.zoneu.app.device

import kotlin.math.roundToInt

/**
 * 视觉路径的「帧」：一次截图的产物，也是**感知与执行之间唯一的坐标系**。
 *
 * 为什么需要它：交给模型的截图被缩到最长边 2048（1080×2344 的屏 → 943×2047），而手势要的是
 * **屏幕像素**。模型看图给出的坐标落在**图像像素**里，两者之间以前没有任何声明的换算 ——
 * 模型连屏幕尺寸都看不到（插件的工具结果只交 `summary` 文本，payload 里的尺寸它读不到），
 * 于是按图给的坐标会系统性偏约 12.6%，而且不报错。
 *
 * 现在把换算钉在这一个纯函数里：模型只说图像像素，执行层用 [toScreen] 换算成屏幕像素再下发。
 * 与 ROM 无关，可直接 JVM 单测（见 `ScreenFrameTest`）。
 *
 * 注意这不是「定位能力」：它消掉的是**确定性**的换算错误，消不掉「模型能不能在图上找到目标」
 * 这个能力问题。首点命中率要靠真机实测，不能靠这份契约保证。
 */
data class ScreenFrame(
  /** 帧 id，服务内单调递增（`f1`、`f2`…）。坐标只对**具体一帧**有效，界面变了就得重新取帧。 */
  val id: String,
  /** 模型实际看到的那张图的像素尺寸（缩放后）。 */
  val imageWidth: Int,
  val imageHeight: Int,
  /**
   * 缩放**前**捕获位图的尺寸 —— 这才是真实屏幕尺寸。
   *
   * 不用 `displayMetrics`：分屏、系统栏、显示缩放都会让它与真实捕获尺寸对不上，
   * 而这里要的是「我这张图对应的那块屏幕到底多大」。
   */
  val screenWidth: Int,
  val screenHeight: Int,
  /**
   * 图像原点在屏幕中的偏移。`takeScreenshot(DEFAULT_DISPLAY)` 截的是整屏（含状态栏、导航栏），
   * 控件树的 `getBoundsInScreen` 也在同一原点，所以今天恒为 0；留着给将来「按窗口裁剪截图」填真值。
   */
  val offsetX: Int = 0,
  val offsetY: Int = 0,
) {
  /** 图像像素坐标是否落在这帧里。越界的 bbox 直接拒，不 clamp —— clamp 会让模型以为点对了。 */
  fun contains(x: Int, y: Int): Boolean = x in 0..imageWidth && y in 0..imageHeight

  /**
   * 图像像素 → 屏幕像素。两轴独立换算，天然兼容等比缩放与将来的裁剪 / letterbox。
   *
   * 上界夹到 `screenWidth - 1` / `screenHeight - 1`：右下角那个坐标（`imageWidth, imageHeight`）
   * 换算出来正好等于屏幕宽高，而合法像素最大是宽高减一。
   */
  fun toScreen(x: Int, y: Int): Pair<Int, Int> {
    val screenX = (offsetX + x.toDouble() * screenWidth / imageWidth).roundToInt()
    val screenY = (offsetY + y.toDouble() * screenHeight / imageHeight).roundToInt()
    return screenX.coerceIn(0, screenWidth - 1) to screenY.coerceIn(0, screenHeight - 1)
  }

  /** 图像像素 bbox 的中心（整数除法），再换算到屏幕。执行层点击用的就是它。 */
  fun bboxCenterOnScreen(x1: Int, y1: Int, x2: Int, y2: Int): Pair<Int, Int> =
    toScreen((x1 + x2) / 2, (y1 + y2) / 2)

  /** 给模型看的一行图例。**模型只看得见文本**，所以屏幕尺寸必须出现在这里，不能只放 payload。 */
  fun legend(): String =
    "帧 $id｜图像 $imageWidth×$imageHeight px｜屏幕 $screenWidth×$screenHeight px｜" +
      "坐标一律用本帧图像像素，左上(0,0) 右下($imageWidth,$imageHeight)"

  /** 图像像素 bbox 是否整块都在帧内。 */
  fun containsBox(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
    contains(x1, y1) && contains(x2, y2) && x1 <= x2 && y1 <= y2
}

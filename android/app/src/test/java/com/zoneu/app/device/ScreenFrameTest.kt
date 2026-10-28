package com.zoneu.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧换算：图像像素 → 屏幕像素。
 *
 * 这是视觉路径唯一的换算点，而它以前**根本不存在** —— 模型按图给的坐标被直接当成屏幕像素下发，
 * 在 1080×2344 的机器上系统性偏约 12.6%（截图缩到最长边 2048 → 943×2047），而且不报错。
 * 这些用例把那条偏差钉死。
 */
class ScreenFrameTest {
  /** 一加 15T 的真实几何：屏 1080×2344，截图缩到最长边 2048 后是 943×2047。 */
  private val frame =
    ScreenFrame(
      id = "f1",
      imageWidth = 943,
      imageHeight = 2047,
      screenWidth = 1080,
      screenHeight = 2344,
    )

  @Test
  fun `左上角对左上角`() {
    assertEquals(0 to 0, frame.toScreen(0, 0))
  }

  @Test
  fun `右下角换算后被夹在屏幕内`() {
    // 943×2047 恰好换算出 1080×2344 —— 而合法像素最大是宽高减一。
    assertEquals(1079 to 2343, frame.toScreen(943, 2047))
  }

  @Test
  fun `中心换算按缩放比放大`() {
    assertEquals(539 to 1171, frame.toScreen(471, 1023))
  }

  @Test
  fun `不做换算就会偏出一个按钮的距离`() {
    val (screenX, screenY) = frame.toScreen(471, 1023)

    // 这就是改之前的行为：图像坐标被当成屏幕坐标直接点。
    val errorX = screenX - 471
    val errorY = screenY - 1023
    assertTrue("横向偏差 $errorX px 太小，用例失效了", errorX > 50)
    assertTrue("纵向偏差 $errorY px 太小，用例失效了", errorY > 100)
  }

  @Test
  fun `bbox 中心先取中心再换算`() {
    assertEquals(frame.toScreen(471, 1023), frame.bboxCenterOnScreen(400, 1000, 543, 1047))
  }

  @Test
  fun `越界的 bbox 判得出来`() {
    assertTrue(frame.containsBox(0, 0, 943, 2047))
    assertFalse("右下角越出图像", frame.containsBox(0, 0, 944, 2047))
    assertFalse("坐标反了", frame.containsBox(500, 500, 400, 400))
    assertFalse("负坐标", frame.containsBox(-1, 0, 100, 100))
  }

  @Test
  fun `offset 非零时按偏移平移`() {
    val cropped = frame.copy(offsetX = 20, offsetY = 30)

    assertEquals(20 to 30, cropped.toScreen(0, 0))
  }

  @Test
  fun `图例把图像尺寸与屏幕尺寸都写给模型`() {
    // 模型只看得见工具结果的文本 —— 屏幕尺寸不写在这里，它就永远拿不到。
    val legend = frame.legend()

    assertTrue(legend.contains("图像 943×2047"))
    assertTrue(legend.contains("屏幕 1080×2344"))
    assertTrue(legend.contains("f1"))
  }
}

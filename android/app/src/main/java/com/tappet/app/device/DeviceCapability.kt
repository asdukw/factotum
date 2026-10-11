package com.tappet.app.device

/**
 * 与 ROM / OEM 无关的设备操作能力清单。
 *
 * 这是 agent 执行层的「通用能力」：任何实现（Android 无障碍、将来的其它执行器）
 * 都按同一套能力对齐。探针期只做前四项；正式执行层会在此基础上长出更多能力
 * （启动 App / 返回 / 滑动 / 通知等），但接口形状不变。
 */
enum class DeviceCapability(
  val title: String,
  val goal: String,
) {
  READ_TREE("Q1 读屏", "读取当前界面的控件树，只看计数与类型分布，不落页面正文"),
  SCREENSHOT("Q2 截图", "对当前界面截图，检测是否被 FLAG_SECURE 挡成黑图或直接失败"),
  SET_TEXT("Q3 输入", "向当前界面的输入框写入测试串（绝不触发发送 / 搜索）"),
  TAP("Q4 手势", "用手势点击当前界面的输入框，验证焦点是否真的落位"),
}

enum class CapabilityStatus {
  RUNNING,
  OK,
  FAILED,
}

/** 一次能力实测结果。summary / details 只允许放结构信息（计数、包名、类名、坐标、错误码）。 */
data class CapabilityResult(
  val capability: DeviceCapability,
  val status: CapabilityStatus,
  val summary: String,
  val details: List<String> = emptyList(),
  val at: Long,
)

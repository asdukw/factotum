package com.zoneu.app.theme

import androidx.compose.ui.graphics.Color

// 参照 ChatGPT / DeepSeek 手机端的聊天气质：白底、中性灰、冷蓝强调。
// 全局不用 Material You 动态取色，避免品牌色随机漂移。
//
// 所有前景/背景组合都要过 WCAG 2.2 AA：正文 4.5:1，组件边界等非文本元素 3:1。
// 品牌蓝 #4D6BFE 在白底上只有 4.33:1，所以浅色主色压到 #445FF5（白底 5.0:1）；
// 深色主色本来就是提亮版，不受影响。
val ZoneuBlue = Color(0xFF445FF5)
val ZoneuBlueLight = Color(0xFF8FA4FF)

val LightBackground = Color(0xFFFDFDFF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF4F5F7)
val LightOnSurface = Color(0xFF17181C)

// 6F7683 在浅灰卡片上只有 4.19:1；压深一档，白底与两种灰底都过 4.5。
val LightOnSurfaceVariant = Color(0xFF646B78)

// outline 是「组件边界」（输入框、开关、未选中控件），要 3:1；
// 装饰性分割线走 outlineVariant，可以更淡。
val LightOutline = Color(0xFF828A98)
val LightOutlineVariant = Color(0xFFE5E7EC)
val LightUserBubble = Color(0xFFE9EDFB)
val LightUserBubbleText = Color(0xFF17181C)

// 语义色：成功 / 警告。M3 的 error 之外，界面上只有这两类额外状态。
val LightSuccess = Color(0xFF1B7F4B)
val LightWarning = Color(0xFF8A5A00)

val DarkBackground = Color(0xFF111216)
val DarkSurface = Color(0xFF17181D)
val DarkSurfaceVariant = Color(0xFF23252C)
val DarkOnSurface = Color(0xFFECEDEF)
val DarkOnSurfaceVariant = Color(0xFF9BA1AC)
val DarkOutline = Color(0xFF616979)
val DarkOutlineVariant = Color(0xFF31343D)
val DarkUserBubble = Color(0xFF2A3149)
val DarkUserBubbleText = Color(0xFFE7EAF6)

val DarkSuccess = Color(0xFF6FD79B)
val DarkWarning = Color(0xFFF2C46B)

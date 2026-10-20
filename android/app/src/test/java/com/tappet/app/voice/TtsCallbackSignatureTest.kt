package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 钉住 [samplesCallback] 的**编译产物形态**。
 *
 * sherpa-onnx 的 JNI 按名字与签名取回调方法：`invoke([F)Ljava/lang/Integer;`。
 * Kotlin 把 lambda 字面量编成 invokedynamic 之后，那个专用重载就没了 —— native 侧
 * `GetMethodID` 拿到 null，整个 App 以 `JNI DETECTED ERROR ... NoSuchMethodError` 闪退
 * （一加 15T 实测，2026-10-20）。这条约束读代码看不出来，只能靠断言编译产物来守。
 */
class TtsCallbackSignatureTest {
  @Test
  fun `采样回调带 JNI 按名字找的那个 invoke 重载`() {
    val callback = samplesCallback { it.size }

    val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)

    assertEquals(
      "返回类型必须是装箱的 Integer —— JNI 找的是 ([F)Ljava/lang/Integer;，不是 ([F)I",
      java.lang.Integer::class.java,
      method.returnType,
    )
  }
}

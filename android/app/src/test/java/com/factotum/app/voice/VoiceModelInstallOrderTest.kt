package com.factotum.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次安装要下哪几份」的纯 JVM 单测。
 *
 * 为什么要单独钉住：它决定了老用户点一次「补下」只拉缺的那 62MB。写反了**界面上看不出来** ——
 * 进度条照样走，只是流量莫名多花了 82MB，而且要等第二次下载失败才会有人怀疑。
 * 而这条判据完全不需要设备：给它一个「装没装」的函数就能验。
 */
class VoiceModelInstallOrderTest {
  private val paraformer = AsrModelCatalog.PARAFORMER_ZH_SMALL
  private val punct = PunctModelCatalog.CT_TRANSFORMER_ZH_EN

  @Test
  fun `全新安装时本体在前、伴生在后`() {
    // 顺序有意义：中途取消时留下的是「识别能用、标点还差一点」，反过来则是一份没人用得上的标点。
    val order = installOrder(paraformer) { false }

    assertEquals(listOf(paraformer.id, punct.id), order.map { it.id })
  }

  @Test
  fun `本体已装时只补伴生的那一份`() {
    // 老用户升级的情形：Paraformer 早就在了，只缺标点。
    // 这就是按钮上写「补下 61.7 MB」而不是「下载 143.7 MB」的判据。
    val order = installOrder(paraformer) { it.id == paraformer.id }

    assertEquals(listOf(punct.id), order.map { it.id })
  }

  @Test
  fun `都装好了就什么都不下`() {
    assertTrue(installOrder(paraformer) { true }.isEmpty())
  }

  @Test
  fun `伴生模型按它自己的种类查，不是本体的`() {
    // 标点装在 punct-models/ 下、识别装在 asr-models/ 下。拿本体那一类去查「装没装」
    // 永远查到没装 —— 于是每次点「补下」都重下一遍 62MB，而界面上一切正常。
    val seen = mutableListOf<String>()

    installOrder(paraformer) {
      seen += it.kind
      false
    }

    assertEquals(listOf(VoiceModelKinds.ASR, VoiceModelKinds.PUNCT), seen)
  }
}

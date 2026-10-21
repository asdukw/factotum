package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别模型目录的不变量。
 *
 * 目录里的字段是「照着真实归档写」的声明，写错的表现是「模型下好了但一说话就报错」——
 * 报错来自 native 层，堆栈指不到目录表这里。能在 JVM 上钉住的部分全部钉住：
 * 地址来源、文件名形态、标点陪伴关系。
 */
class AsrModelCatalogTest {
  @Test
  fun `目录里只有 paraformer`() {
    // 识别模型不做成可选（设置页里没有入口，见 AsrModelCatalog 的注释）。加第二条之前
    // 先想清楚「用户怎么知道自己装的是哪一条」—— 现在这个问题没有答案。
    assertEquals(listOf("paraformer_zh_small"), AsrModelCatalog.ALL.map { it.id })
  }

  @Test
  fun `地址来自 sherpa-onnx 的 asr-models release`() {
    // 不引入第二个下载域：与应用内更新、朗读模型同一个来源（GitHub）。
    AsrModelCatalog.ALL.forEach { model ->
      assertTrue(
        model.id,
        model.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"),
      )
      assertTrue(model.id, model.url.endsWith(".tar.bz2"))
    }
  }

  @Test
  fun `文件名都是纯文件名，不带路径`() {
    // 解包会把归档摊平到模型目录的根，配置里拼的是 `File(模型目录, 文件名)` ——
    // 带路径的文件名会在设备上变成「找不到文件」。
    AsrModelCatalog.ALL.forEach { model ->
      model.requiredFiles.forEach { name ->
        assertTrue("${model.id} 的 $name", !name.contains('/') && !name.contains('\\'))
      }
    }
  }

  @Test
  fun `必备文件与归档顶层目录都齐全`() {
    AsrModelCatalog.ALL.forEach { model ->
      assertTrue(model.id, model.requiredFiles.isNotEmpty())
      assertTrue(model.id, model.archiveRoot.isNotBlank())
      assertTrue(model.id, model.license.isNotBlank())
      assertTrue(model.id, model.licenseNote.isNotBlank())
    }
  }

  @Test
  fun `paraformer 用 int8 权重`() {
    // fp32 那份在手机上又大又慢；归档里两套都有，这条钉住「我们选的是 int8」。
    assertTrue(AsrModelCatalog.PARAFORMER_ZH_SMALL.modelFile.endsWith(".int8.onnx"))
  }

  @Test
  fun `paraformer 不产标点，标点模型当伴生一起装`() {
    // 标点不做成设置页里的一个选项：用户点麦克风，标点跟着识别模型一起下来。
    // 判据写反的表现是「下完还是没有标点」，而用户没有任何入口去补。
    assertEquals(PunctModelCatalog.ALL, AsrModelCatalog.PARAFORMER_ZH_SMALL.companions)
  }

  @Test
  fun `伴生模型的种类与本体不同，但自己带着种类`() {
    // 伴生模型装到**另一类**目录下（punct 不是识别模型的一部分）。所以 [VoiceModel.kind]
    // 必须由模型自己说，而不是沿用调用方传进来的那个。
    val companion = AsrModelCatalog.PARAFORMER_ZH_SMALL.companions.single()

    assertEquals(VoiceModelKinds.PUNCT, companion.kind)
    assertEquals(VoiceModelKinds.ASR, AsrModelCatalog.PARAFORMER_ZH_SMALL.kind)
  }
}

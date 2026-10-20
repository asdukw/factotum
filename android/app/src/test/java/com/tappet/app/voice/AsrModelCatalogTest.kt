package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别模型目录的不变量。
 *
 * 目录里的字段是「照着真实归档写」的声明，写错的表现是「模型下好了但一说话就报错」——
 * 报错来自 native 层，堆栈指不到目录表这里。能在 JVM 上钉住的部分全部钉住：
 * 地址来源、文件名形态、语种取值。
 */
class AsrModelCatalogTest {
  @Test
  fun `每个模型的 id 唯一`() {
    val ids = AsrModelCatalog.ALL.map { it.id }

    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `地址都来自 sherpa-onnx 的 asr-models release`() {
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
  fun `必备文件不为空，且归档顶层目录有名字`() {
    AsrModelCatalog.ALL.forEach { model ->
      assertTrue(model.id, model.requiredFiles.isNotEmpty())
      assertTrue(model.id, model.archiveRoot.isNotBlank())
      assertTrue(model.id, model.license.isNotBlank())
      assertTrue(model.id, model.licenseNote.isNotBlank())
    }
  }

  @Test
  fun `whisper 用 int8 权重`() {
    // 归档里 fp32 与 int8 两套都有，fp32 是它的三倍大、在手机上还更慢。
    // 这条钉住「我们选的是 int8」这个决定。
    val whisper = AsrModelCatalog.WHISPER_TINY.engine as AsrEngineSpec.Whisper

    assertTrue(whisper.encoderFile.endsWith(".int8.onnx"))
    assertTrue(whisper.decoderFile.endsWith(".int8.onnx"))
  }

  @Test
  fun `sense-voice 的语种取值合法`() {
    // 只认这几个（对应模型元数据里的 <|lang_*|> 记号）；给别的值不报错，
    // 会静默退化成模型自己的默认行为。
    val valid = setOf("auto", "zh", "en", "ja", "ko", "yue")

    val spec = AsrModelCatalog.SENSE_VOICE_INT8.engine as AsrEngineSpec.SenseVoice

    assertTrue(spec.language, spec.language in valid)
  }

  @Test
  fun `whisper 的语种留空，交给模型自己判`() {
    // 写死语种会把别的语言硬解成那个语言，而且不报错（真机上把英文样例解成了中文）。
    val whisper = AsrModelCatalog.WHISPER_TINY.engine as AsrEngineSpec.Whisper

    assertTrue(whisper.language.isEmpty())
  }

  @Test
  fun `默认模型是体积最小的那个`() {
    // 默认那条是「装完不心疼」的那条：82MB 的 paraformer。
    assertEquals(AsrModelCatalog.PARAFORMER_ZH_SMALL, AsrModelCatalog.ALL.first())
  }

  @Test
  fun `只有 paraformer 需要标点模型`() {
    // 这一格决定了「识别完要不要再过一遍标点模型」。写反的后果都不小：
    // 该补的没补 = 输入框里一整句没有断句的中文；不该补的补了 = 用中文标点规则去改
    // 一个多语言模型的输出，语言不对时会改坏。
    assertTrue(AsrModelCatalog.PARAFORMER_ZH_SMALL.engine.needsPunctuation)
    assertTrue(!AsrModelCatalog.SENSE_VOICE_INT8.engine.needsPunctuation)
    assertTrue(!AsrModelCatalog.WHISPER_TINY.engine.needsPunctuation)
  }

  @Test
  fun `需要标点的那档把标点模型当伴生一起装`() {
    // 标点不做成设置页里的一个选项：用户点了「下载 Paraformer」，标点跟着下来。
    // 判据必须与 needsPunctuation 一致 —— 两处不一致的表现是「下完还是没有标点」，
    // 而用户没有任何入口去补。
    assertEquals(
      PunctModelCatalog.ALL,
      AsrModelCatalog.PARAFORMER_ZH_SMALL.companions,
    )
    assertTrue(AsrModelCatalog.SENSE_VOICE_INT8.companions.isEmpty())
    assertTrue(AsrModelCatalog.WHISPER_TINY.companions.isEmpty())
  }

  @Test
  fun `伴生模型的种类与本体不同，但自己带着种类`() {
    // 伴生模型装到**另一类**目录下（punctuation 不是识别模型的一部分，两个识别模型将来
    // 可能共用一份）。所以 [VoiceModel.kind] 必须由模型自己说，而不是沿用调用方传进来的那个。
    val companion = AsrModelCatalog.PARAFORMER_ZH_SMALL.companions.single()

    assertEquals(VoiceModelKinds.PUNCT, companion.kind)
    assertEquals(VoiceModelKinds.ASR, AsrModelCatalog.PARAFORMER_ZH_SMALL.kind)
  }

  @Test
  fun `识别模型的种类都是 asr`() {
    // kind 是**存储身份**：写错的表现是「明明下过，界面却说没下」。
    AsrModelCatalog.ALL.forEach { model ->
      assertEquals(model.id, VoiceModelKinds.ASR, model.kind)
    }
  }
}

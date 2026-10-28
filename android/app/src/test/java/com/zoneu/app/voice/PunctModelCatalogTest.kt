package com.zoneu.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标点模型目录的不变量。与 [AsrModelCatalogTest] 同一类：目录里的字段是「照着真实归档写」
 * 的声明，写错的表现是「模型下好了但一说话就报错」，而报错来自 native 层，堆栈指不到这里。
 *
 * 归档的实际内容（`tar -tjf` 列过）：
 * ```
 * sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/
 *   model.int8.onnx      72MB
 *   tokens.json / config.yaml / README.md / ...
 * ```
 * `tokens.json` **不进 [PunctModel.requiredFiles]**：sherpa 的 CT-Transformer 从 ONNX
 * 元数据里读词表，Kotlin 侧只给权重路径一个字段。
 */
class PunctModelCatalogTest {
  @Test
  fun `地址来自 sherpa-onnx 的 punctuation-models release`() {
    // tag 与识别模型不同（punctuation-models 而不是 asr-models），但**域是同一个** ——
    // AGENTS.md 那条「不引入第二个下载域」管的是域，不是 tag。
    PunctModelCatalog.ALL.forEach { model ->
      assertTrue(
        model.id,
        model.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"),
      )
      assertTrue(model.id, model.url.endsWith(".tar.bz2"))
    }
  }

  @Test
  fun `用的是 int8 权重`() {
    // 归档里 fp32 与 int8 两套都有：fp32 解出来 281MB，int8 是 72MB。
    PunctModelCatalog.ALL.forEach { model ->
      assertTrue(model.id, model.modelFile.endsWith(".int8.onnx"))
    }
  }

  @Test
  fun `必备文件是纯文件名，归档顶层目录有名字`() {
    PunctModelCatalog.ALL.forEach { model ->
      assertTrue(model.id, model.requiredFiles.isNotEmpty())
      model.requiredFiles.forEach { name ->
        assertTrue("${model.id} 的 $name", !name.contains('/') && !name.contains('\\'))
      }
      assertTrue(model.id, model.archiveRoot.isNotBlank())
    }
  }

  @Test
  fun `授权写清楚了，而且不是空话`() {
    // 一个公开 App 的一键下载目录不该让用户替我们猜授权。这一条是 Apache-2.0，
    // **与另外两条 FunASR 模型许可不同** —— 别按「FunASR 的模型都是模型许可 1.1」想当然。
    val model = PunctModelCatalog.CT_TRANSFORMER_ZH_EN

    assertEquals("Apache-2.0", model.license)
    assertTrue(model.licenseNote.isNotBlank())
  }

  @Test
  fun `id 唯一，且只有一个模型时也算得上唯一`() {
    val ids = PunctModelCatalog.ALL.map { it.id }

    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `种类是 punct，而且自己不依赖别人`() {
    // kind 是**存储身份**（写错 = 设备上已下好的找不到），而 companions 为空是这条链的终点：
    // 标点模型要是也有伴生，安装就成了递归，而「装一次谁需要谁用」的前提就没了。
    PunctModelCatalog.ALL.forEach { model ->
      assertEquals(model.id, VoiceModelKinds.PUNCT, model.kind)
      assertTrue(model.id, model.companions.isEmpty())
    }
  }

  @Test
  fun `按 id 查得到，查不到的 id 回 null`() {
    assertEquals(PunctModelCatalog.CT_TRANSFORMER_ZH_EN, PunctModelCatalog.byId("punct_ct_transformer_zh_en"))
    assertEquals(null, PunctModelCatalog.byId(null))
    assertEquals(null, PunctModelCatalog.byId("不存在"))
  }
}

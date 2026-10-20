package com.tappet.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合成模型目录的不变量。
 *
 * 目录里的字段是「照着真实归档写」的声明，写错的表现是「模型下好了但一点朗读就报错」——
 * 报错来自 native 层，堆栈指不到目录表这里。能在 JVM 上钉住的部分全部钉住。
 */
class TtsModelCatalogTest {
  @Test
  fun `每个模型的 id 唯一`() {
    val ids = TtsModelCatalog.ALL.map { it.id }

    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `地址都来自 sherpa-onnx 的 tts-models release`() {
    // 不引入第二个下载域：与应用内更新、识别模型同一个来源（GitHub）。
    TtsModelCatalog.ALL.forEach { model ->
      assertTrue(
        model.id,
        model.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"),
      )
      assertTrue(model.id, model.url.endsWith(".tar.bz2"))
    }
  }

  @Test
  fun `文件名都是纯文件名，不带路径`() {
    // 解包会把归档摊平到模型目录的根，配置里拼的是 `File(模型目录, 文件名)` ——
    // 带路径的文件名会在设备上变成「找不到文件」。
    TtsModelCatalog.ALL.forEach { model ->
      model.requiredFiles.forEach { name ->
        assertTrue("${model.id} 的 $name", !name.contains('/') && !name.contains('\\'))
      }
    }
  }

  @Test
  fun `onnx 文件名跟着归档名走`() {
    // 导出脚本写死了 `filename = f"{name}.onnx"`（name = 源合集里的目录名），所以归档里是
    // `keqing.onnx` 而不是 `vits-zh-hf-keqing.onnx`。猜错的表现同样是「下好了但加载不了」。
    TtsModelCatalog.ALL.forEach { model ->
      val name = model.archiveRoot.removePrefix("vits-zh-hf-")

      assertEquals(model.id, "$name.onnx", model.modelFile)
    }
  }

  @Test
  fun `必备文件不为空，且归档顶层目录有名字`() {
    TtsModelCatalog.ALL.forEach { model ->
      assertTrue(model.id, model.requiredFiles.isNotEmpty())
      assertTrue(model.id, model.archiveRoot.isNotBlank())
      assertTrue(model.id, model.license.isNotBlank())
      assertTrue(model.id, model.licenseNote.isNotBlank())
    }
  }

  @Test
  fun `三个文本正则化 FST 都挂着`() {
    // 少挂一个不报错，只是电话号码 / 数字 / 日期会被逐字念出来。归档里一共就这四个
    // （外加 new_heteronym.fst，那个给词典用），这里钉住我们用的是哪三个。
    TtsModelCatalog.ALL.forEach { model ->
      assertEquals(model.id, listOf("phone.fst", "number.fst", "date.fst"), model.ruleFsts)
    }
  }

  @Test
  fun `角色音的 sid 逐个钉住`() {
    // 这四条是一个游戏一份多说话人模型，角色只是里面的一行；导出脚本把 sid 做成了 ONNX 的
    // 输入（`docs/voice.md` §4）。**填错不报错，只是读出别人的声音**，只有耳朵能发现 ——
    // 所以在这里钉住，改动必须是有意的。值来自源合集 `zomehwh/vits-models-genshin-bh3`
    // 的 info.json（与它的试听页用的是同一份）。
    assertEquals(115, TtsModelCatalog.KEQING.sid)
    assertEquals(193, TtsModelCatalog.THERESA.sid)
    assertEquals(193, TtsModelCatalog.BRONYA.sid)
    assertEquals(124, TtsModelCatalog.EULA.sid)
  }

  @Test
  fun `归档大小与下载地址对得上`() {
    // 界面上的「120.6MB」直接来自这里；写错只是显示不准，但顺手钉住，免得改地址时忘了改它。
    assertEquals(120_592_220L, TtsModelCatalog.KEQING.sizeBytes)
    assertEquals(120_596_617L, TtsModelCatalog.THERESA.sizeBytes)
    assertEquals(120_595_102L, TtsModelCatalog.BRONYA.sizeBytes)
    assertEquals(120_562_119L, TtsModelCatalog.EULA.sizeBytes)
  }
}

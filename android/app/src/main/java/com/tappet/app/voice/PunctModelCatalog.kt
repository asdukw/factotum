package com.tappet.app.voice

import android.content.Context

/**
 * 一个可下载的**标点模型** —— 识别结果的补标点，与识别模型是两件事。
 *
 * 为什么单列一类而不是塞进 [AsrModelCatalog]：它**不属于任何一个识别模型**。paraformer 那条
 * 需要它，sense-voice 自带标点，whisper 自己会吐逗号 —— 把它挂进某一个识别条目，换模型时
 * 用户就得重下一份 62MB 的同一个文件。所以它是「装一次、谁需要谁用」的东西。
 */
data class PunctModel(
  override val id: String,
  override val label: String,
  override val summary: String,
  override val url: String,
  override val sizeBytes: Long,
  override val archiveRoot: String,
  override val license: String,
  override val licenseNote: String,
  /** ONNX 权重在模型目录里的文件名。 */
  val modelFile: String,
) : VoiceModel {
  override val requiredFiles: List<String> get() = listOf(modelFile)
}

/**
 * 可选的标点模型目录。**目前只有一条**：CT-Transformer（中英），FunASR 的那套。
 *
 * 为什么需要它：paraformer-zh-small 的输出是**没有标点的裸文本**（真机实测：一整句听下来
 * 只有「啊 / 呢 / 嗯」这些语气词把它断开），而这句话是要发给 agent 的指令 —— 标点直接决定
 * 它怎么断句。sense-voice 与 whisper 不需要：前者的标点与数字正则化烘在模型里
 * （`useInverseTextNormalization`），后者自己会吐标点。
 *
 * **下载域与识别模型不是同一个 release tag**（`punctuation-models` 而不是 `asr-models`），
 * 但仍是同一个仓库、同一个域（github.com/k2-fsa/sherpa-onnx）—— AGENTS.md 那条
 * 「不引入第二个下载域」管的是域，不是 tag。
 *
 * 授权是 **Apache-2.0**（ModelScope 上 `iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch`
 * 的 License 字段），比另外两条 FunASR 模型许可更宽松 —— 上游 sherpa-onnx 的 README 与文档页
 * 都没写授权，这一条是从模型页的元数据确认的，别按「FunASR 的模型都是模型许可 1.1」想当然。
 */
object PunctModelCatalog {
  private const val RELEASE_BASE =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"

  val CT_TRANSFORMER_ZH_EN =
    PunctModel(
      id = "punct_ct_transformer_zh_en",
      label = "标点（中英）",
      summary = "62MB，给识别结果补上逗号句号 —— paraformer 那档本身不产标点",
      url = RELEASE_BASE + "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2",
      // 归档 61.7MB，解出来是 72MB 的 model.int8.onnx（fp32 那份 281MB，不收）。
      sizeBytes = 64_717_756L,
      archiveRoot = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8",
      license = "Apache-2.0",
      licenseNote =
        "上游是 ModelScope 的 iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch（Apache-2.0）。" +
          "模型卡的 License 字段写着 Apache License 2.0；sherpa-onnx 的文档页与归档里的 README 都没写授权。",
      modelFile = "model.int8.onnx",
    )

  val ALL: List<PunctModel> = listOf(CT_TRANSFORMER_ZH_EN)

  fun byId(id: String?): PunctModel? = ALL.firstOrNull { it.id == id }

  /**
   * 现在能用哪个标点模型。判据与识别那边同一条：**装没装**，不是「选没选」——
   * 只有一个模型还要用户去点一次「选中」，就是界面在自找麻烦。
   */
  fun installedId(context: Context): String? =
    ALL.firstOrNull { VoiceModelStore.isInstalled(context, VoiceModelKinds.PUNCT, it) }?.id
}

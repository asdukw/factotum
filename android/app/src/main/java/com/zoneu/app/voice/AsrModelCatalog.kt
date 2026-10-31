package com.zoneu.app.voice

/**
 * 本地**识别**模型 —— 目录里只有一条：[AsrModelCatalog.PARAFORMER_ZH_SMALL]。
 *
 * 为什么不做成可选：识别模型之间有准确率 / 速度 / 语言的取舍，但那不是用户该回答的问题。
 * 语音输入只服务一个场景 —— 把一句话变成发给 agent 的指令 —— 多一个选项就多一次
 * 「选错了却不知道」的机会。设置页因此**没有**识别模型的入口，模型在安装后自动下载
 * （见 [com.zoneu.app.ZoneuApplication]）。
 *
 * 「怎么下、怎么解、装到哪」在 [VoiceModel] 那一层（与朗读共用），这里多出的是**怎么用它**：
 * paraformer 是单文件 + 词表。换模型时连同 [companions]、`LocalRecognizer` 的配置与
 * `docs/voice.md` §3 一起改 —— 见那条目录项的注释。
 *
 * 每条都要照着**真实的归档**写（归档里的文件名、顶层目录名）。写错的表现是
 * 「模型下好了但一说话就报错」，而报错来自 native 层，堆栈指不到目录表这里。
 */
data class AsrModel(
  override val id: String,
  override val label: String,
  override val summary: String,
  override val url: String,
  override val sizeBytes: Long,
  override val archiveRoot: String,
  override val license: String,
  override val licenseNote: String,
  val modelFile: String,
  val tokensFile: String,
) : VoiceModel {
  override val kind: String get() = VoiceModelKinds.ASR

  override val requiredFiles: List<String> get() = listOf(modelFile, tokensFile)

  /**
   * 这一档不产标点，所以标点模型**跟着它一起装**。
   *
   * 「要不要标点」不是一个该由用户回答的问题：它只取决于识别模型选的是哪个，做成设置页里的
   * 一个选项等于把「哪档需要标点」这个内部知识推给用户，而他既不知道也不该知道。
   * 所以它是一条数据（[companions]），不是一个开关。
   */
  override val companions: List<VoiceModel> get() = PunctModelCatalog.ALL
}

/**
 * 识别模型目录。**只有 Paraformer（中文小）一条**。
 *
 * 地址是 k2-fsa/sherpa-onnx 的 `asr-models` release 资产，与朗读模型、应用内更新走
 * 同一个来源（GitHub），不引入第二个下载域。
 *
 * 为什么是它：中文准确率最好的一档、非自回归（手机上最快）、82MB；代价是它**不产标点**
 * （真机实测一整句只有语气词断开），所以标点模型（[PunctModelCatalog]，62MB）是它的伴生件。
 * 一加 15T 实测同一段 5.6 秒中文：62ms，输出可读。
 *
 * **不在这里放「授权不明」的模型**：FunASR 那条不是 OSI 标准许可，但条款是可自由使用 /
 * 修改 / 分发 + 必须署名并保留模型名（`MODEL_LICENSE` 1.1），所以能收 —— 界面把这句话
 * 原样显示出来。
 */
object AsrModelCatalog {
  private const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

  val PARAFORMER_ZH_SMALL =
    AsrModel(
      id = "paraformer_zh_small",
      label = "Paraformer 中文（小）",
      summary = "82MB，中文听写，手机上最快的一档；中英混说也认",
      url = RELEASE_BASE + "sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2",
      sizeBytes = 77_920_048L,
      archiveRoot = "sherpa-onnx-paraformer-zh-small-2024-03-09",
      license = "FunASR 模型开源许可 1.1",
      licenseNote =
        "上游是 ModelScope 的 crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx。" +
          "条款：可自由使用 / 修改 / 分发，必须署名并保留模型名；不是 OSI 标准许可。",
      // 归档里还有个 `am.mvn`，**不用管它**：那是给 FunASR 自己的推理用的，sherpa-onnx 的
      // paraformer 实现不读它（`csrc/offline-paraformer-model.cc` 里没有这个名字），
      // 归一化参数在导出 ONNX 时就烘进图里了。
      modelFile = "model.int8.onnx",
      tokensFile = "tokens.txt",
    )

  val ALL: List<AsrModel> = listOf(PARAFORMER_ZH_SMALL)

  fun byId(id: String?): AsrModel? = ALL.firstOrNull { it.id == id }
}

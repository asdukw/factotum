package com.tappet.app.voice

/**
 * 一个可下载的本地**合成**模型。
 *
 * 「怎么下、怎么解、装到哪」在 [VoiceModel] 那一层（与识别共用），这里只多出
 * **怎么用**：运行时按 [modelFile] / [tokensFile] / [lexiconFile] / [ruleFsts] / [sid] 拼
 * [com.k2fsa.sherpa.onnx.OfflineTtsConfig]。字段对不上时表现为「模型下好了但点朗读报错」，
 * 所以 [TtsModelCatalog] 里的每一条都要照着**真实的归档**写。
 */
data class TtsModel(
  override val id: String,
  override val label: String,
  override val summary: String,
  /** 下载地址（GitHub release 资产，与本仓库的更新检查同一个来源）。 */
  override val url: String,
  /** 归档大小，界面显示用。 */
  override val sizeBytes: Long,
  /** tar 里的顶层目录名；解包时剥掉。 */
  override val archiveRoot: String,
  val modelFile: String,
  val tokensFile: String,
  val lexiconFile: String,
  /** 文本正则化用的 FST（电话号码 / 数字 / 日期），按顺序传给引擎。 */
  val ruleFsts: List<String>,
  /**
   * 多说话人模型的说话人编号，传给 `OfflineTts.generate(sid=)`。
   *
   * **不能一律填 0**：这些社区音色是「一个游戏一份多说话人模型」，角色是里面的某一行
   * （导出脚本把 `sid` 做成了 ONNX 的输入，见 `docs/voice.md` §4）。填错的表现是
   * **不报错、声音是别人** —— 只有耳朵能发现。单说话人模型忽略这个值，填 0 即可。
   */
  val sid: Int = 0,
  override val license: String,
  override val licenseNote: String,
) : VoiceModel {
  /** 装好了的判据：模型、词表、词典与三个文本正则化 FST 都在。 */
  override val requiredFiles: List<String>
    get() = listOf(modelFile, tokensFile, lexiconFile) + ruleFsts
}

/**
 * 可选的本地模型目录：四条**游戏角色音色**，都是社区训练、k2-fsa 转成 ONNX 的 VITS。
 *
 * 挑选过程（2026-10-21）：目标是永雏塔菲 → 她的公开权重全是 Bert-VITS2（当前引擎跑不了）；
 * 换成《鸣潮》菲比 → 全网没有 TTS 权重（只有 RVC 变声）。最后落到「sherpa 官方已经转好的
 * 中文角色音」这一批，用户选了刻晴 / 德丽莎 / 理之律者 / 优菈。完整检索记录与取舍见
 * `docs/voice.md` §4。
 *
 * **加条目的标准**（别放宽）：地址是公开、稳定、直接可下的 GitHub release 资产，与本仓库的
 * 应用内更新同一个来源，不引入第二个下载域；授权必须说得清，且显示在下载按钮旁边。
 * 这四条**授权都不干净**（游戏角色音，版权方没授权），是用户明确知情后的选择 ——
 * 别把它当成先例去加更多来路不明的东西。
 */
object TtsModelCatalog {
  /** 原神·刻晴。`sid=115` 来自源合集 `zomehwh/vits-models-genshin-bh3` 的 info.json。 */
  val KEQING =
    TtsModel(
      id = "keqing",
      label = "刻晴（原神）",
      summary = "22.05kHz，中/日混读",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-keqing.tar.bz2",
      sizeBytes = 120_592_220L,
      archiveRoot = "vits-zh-hf-keqing",
      modelFile = "keqing.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      sid = 115,
      license = "未授权（同人模型）",
      licenseNote = "社区用《原神》的角色语音训练，米哈游未授权。自己听可以，商用不行。",
    )

  /** 崩坏 3·德丽莎。 */
  val THERESA =
    TtsModel(
      id = "theresa",
      label = "德丽莎（崩坏 3）",
      summary = "22.05kHz，中/日混读",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2",
      sizeBytes = 120_596_617L,
      archiveRoot = "vits-zh-hf-theresa",
      modelFile = "theresa.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      sid = 193,
      license = "未授权（同人模型）",
      licenseNote = "社区用《崩坏 3》的角色语音训练，米哈游未授权。自己听可以，商用不行。",
    )

  /** 崩坏 3·理之律者。 */
  val BRONYA =
    TtsModel(
      id = "bronya",
      label = "理之律者（崩坏 3）",
      summary = "22.05kHz，中/日混读",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-bronya.tar.bz2",
      sizeBytes = 120_595_102L,
      archiveRoot = "vits-zh-hf-bronya",
      modelFile = "bronya.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      sid = 193,
      license = "未授权（同人模型）",
      licenseNote = "社区用《崩坏 3》的角色语音训练，米哈游未授权。自己听可以，商用不行。",
    )

  /** 原神·优菈。 */
  val EULA =
    TtsModel(
      id = "eula",
      label = "优菈（原神）",
      summary = "22.05kHz，中/日混读",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-eula.tar.bz2",
      sizeBytes = 120_562_119L,
      archiveRoot = "vits-zh-hf-eula",
      modelFile = "eula.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      sid = 124,
      license = "未授权（同人模型）",
      licenseNote = "社区用《原神》的角色语音训练，米哈游未授权。自己听可以，商用不行。",
    )

  val ALL: List<TtsModel> = listOf(KEQING, THERESA, BRONYA, EULA)

  fun byId(id: String?): TtsModel? = ALL.firstOrNull { it.id == id }
}

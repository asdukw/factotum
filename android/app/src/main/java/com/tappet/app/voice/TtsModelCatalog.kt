package com.tappet.app.voice

/**
 * 一个可下载的本地**合成**模型。
 *
 * 「怎么下、怎么解、装到哪」在 [VoiceModel] 那一层（与识别共用），这里只多出
 * **怎么用**：运行时按 [modelFile] / [tokensFile] / [lexiconFile] / [ruleFsts] 拼
 * [com.k2fsa.sherpa.onnx.OfflineTtsConfig]。字段对不上时表现为「模型下好了但点朗读报错」，
 * 所以 [TtsModelCatalog] 里的每一条都要照着**真实的归档**写。
 */
data class TtsModel(
  override val id: String,
  override val label: String,
  val summary: String,
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
  override val license: String,
  override val licenseNote: String,
) : VoiceModel {
  /** 装好了的判据：模型、词表、词典与三个文本正则化 FST 都在。 */
  override val requiredFiles: List<String>
    get() = listOf(modelFile, tokensFile, lexiconFile) + ruleFsts
}

/**
 * 可选的本地模型目录。
 *
 * **这里只放「公开、稳定、直接可下」的条目**：地址是 k2-fsa/sherpa-onnx 的 `tts-models`
 * release 资产，与本仓库的应用内更新走同一个来源（GitHub），不引入第二个下载域。
 *
 * 挑选标准是「小 + 授权说得清」。目前两条都是 13MB 的 Piper int8 模型，只是音色与授权不同：
 * [CHAOWEN] 是 CC0（默认，可商用），[XIAO_YA] 的数据集只授权非商用。
 *
 * 不在这里放「授权不明」的模型（HF 上不少中文 VITS / Bert-VITS2 权重都是这种）——
 * 一个公开 App 的一键下载目录不该让用户替我们猜授权。
 *
 * **MeloTTS（MIT，中英混读）暂时没进来**，不是授权问题，是产物问题：它的 GitHub 归档里
 * `model.int8.onnx` 是个 133 字节的 **git-lfs 指针**（真身在 HF，53MB），归档里能用的只有
 * 170MB 的 fp32 —— 手机上又大又慢。要收它得改从 HF 下载，那会引入第二个下载域，
 * 而 HF 在墙内直连不通。等有别的来源再说。
 */
object TtsModelCatalog {
  val CHAOWEN =
    TtsModel(
      id = "chaowen",
      label = "超文（中文女声）",
      summary = "22.05kHz，体积小、出声快，适合日常朗读",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-chaowen-medium-int8.tar.bz2",
      sizeBytes = 14_016_000L,
      archiveRoot = "vits-piper-zh_CN-chaowen-medium-int8",
      modelFile = "zh_CN-chaowen-medium.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      license = "CC0",
      licenseNote = "训练数据来自 OHF-Voice/voice-datasets（CC0，公有领域），可商用。",
    )

  val XIAO_YA =
    TtsModel(
      id = "xiao_ya",
      label = "小雅（中文女声）",
      summary = "22.05kHz，与超文同源但音色不同",
      url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2",
      sizeBytes = 14_016_124L,
      archiveRoot = "vits-piper-zh_CN-xiao_ya-medium-int8",
      modelFile = "zh_CN-xiao_ya-medium.onnx",
      tokensFile = "tokens.txt",
      lexiconFile = "lexicon.txt",
      ruleFsts = listOf("phone.fst", "number.fst", "date.fst"),
      license = "非商用",
      licenseNote = "数据集 BZNSYP（data-baker）只授权非商用；自己用没问题，做商业产品不行。",
    )

  val ALL: List<TtsModel> = listOf(CHAOWEN, XIAO_YA)

  fun byId(id: String?): TtsModel? = ALL.firstOrNull { it.id == id }
}

package com.zoneu.app.voice

/**
 * 本地语音模型的**种类**。决定模型装在哪、下载归档的暂存文件叫什么。
 *
 * **字面量是存储身份**：改它 = 设备上已经下好的模型找不到（与 AGENTS.md 里那几组
 * prefs / Keystore 别名同一条规矩），表现出来只是「明明下过，界面却说没下」。
 */
object VoiceModelKinds {
  const val TTS = "tts"
  const val ASR = "asr"

  /**
   * 标点模型（[PunctModelCatalog]）。**与 [ASR] 分开**：它不是某一个识别模型的附属物，
   * 而是「装一次、谁需要谁用」的共享件 —— 见 [PunctModel] 的注释。
   */
  const val PUNCT = "punct"
}

/**
 * 一个可下载的本地语音模型 —— **合成与识别共用的一套**。
 *
 * 这个接口只描述「怎么把它下下来、解开、装到哪、装好了没有」，不描述它是干什么用的：
 * 合成要的是音色模型，识别要的是声学模型，两者的下载 / 解包 / 安装 / 删除是同一件事，
 * 所以 [VoiceModelStore] / [VoiceModelDownloader] / [VoiceModelInstaller] 只认这个接口。
 * 各自特有的字段（音色、语种、调用风格）留在各自的目录条目里。
 *
 * 全部字段都是「从 GitHub release 的 tar.bz2 解出来之后长什么样」的声明：解包时按
 * [archiveRoot] 剥掉顶层目录，[requiredFiles] 是「装好了」的判据。字段对不上时表现为
 * 「模型下好了但用起来报错」，所以每个目录条目都要照着**真实的归档**写。
 *
 * **授权要如实写、如实显示**：这些模型训练自不同来源的数据集，授权各不相同。
 * 界面把 [license] 显示在下载按钮旁边，而不是等用户去翻仓库。
 */
interface VoiceModel {
  val id: String
  val label: String

  /**
   * 装到哪一类目录下（[VoiceModelKinds] 的字面量）。**存储身份**：改它 = 设备上已经下好的
   * 模型找不到（与 AGENTS.md 里那几组 prefs / Keystore 别名同一条规矩）。
   */
  val kind: String

  /**
   * 装这个模型时**一起装**的伴生模型。
   *
   * 标点模型就是这样一个东西：它是 paraformer 那档的必需品（那档不产标点），但
   * 「要不要标点」不是一个该由用户回答的问题 —— 所以它不做成设置页里的一个选项，
   * 而是跟着识别模型一起下来。[VoiceModelInstaller] 按顺序装，**已经装好的跳过**，
   * 所以老用户升级之后只会补拉缺的那一份。
   */
  val companions: List<VoiceModel> get() = emptyList()

  /** 一行说明（体积 / 语言 / 速度这些取舍），下载日志与调试输出用。 */
  val summary: String

  /** 下载地址（GitHub release 资产，与本仓库的应用内更新同一个来源）。 */
  val url: String

  /** 归档大小，界面显示用。 */
  val sizeBytes: Long

  /** tar 里的顶层目录名；解包时剥掉。 */
  val archiveRoot: String

  /** 装好了的判据：这些文件（相对模型目录）都在。 */
  val requiredFiles: List<String>
  val license: String
  val licenseNote: String
}

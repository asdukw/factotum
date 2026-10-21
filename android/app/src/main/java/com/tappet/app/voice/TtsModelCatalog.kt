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
   * **不能一律填 0**：这类社区音色常是「一个游戏一份多说话人模型」，角色是里面的某一行
   * （导出脚本把 `sid` 做成了 ONNX 的输入，见 `docs/voice.md` §4）。填错的表现是
   * **不报错、声音是别人** —— 只有耳朵能发现。单说话人模型忽略这个值，填 0 即可。
   */
  val sid: Int = 0,
  override val license: String,
  override val licenseNote: String,
) : VoiceModel {
  override val kind: String get() = VoiceModelKinds.TTS

  /** 装好了的判据：模型、词表、词典与三个文本正则化 FST 都在。 */
  override val requiredFiles: List<String>
    get() = listOf(modelFile, tokensFile, lexiconFile) + ruleFsts
}

/**
 * 可选的本地朗读模型目录：**当前为空**。
 *
 * 原先的四条游戏角色音（刻晴 / 优菈 / 德丽莎 / 理之律者）真机听验后撤掉：音色与发音质量
 * 都不达标。本地朗读暂时停用，默认只走系统 TTS；[LocalTtsEngine] 与 [TtsModel] 这套代码
 * 保留着，重新启用只需要往 [ALL] 里加条目。候选调研与取舍见 `docs/voice.md` §4。
 *
 * **加条目的标准**（别放宽）：地址是公开、稳定、直接可下的 GitHub release 资产，与本仓库的
 * 应用内更新同一个来源，不引入第二个下载域；授权必须说得清，且显示在下载按钮旁边；
 * 多说话人模型的 `sid` 必须在真机上听过，确认是这个音色（填错不报错，只换声音）。
 */
object TtsModelCatalog {
  /** 当前不提供可下载的朗读模型。 */
  val ALL: List<TtsModel> = emptyList()

  fun byId(id: String?): TtsModel? = ALL.firstOrNull { it.id == id }
}

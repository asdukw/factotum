package com.tappet.app.voice

/**
 * 一个可下载的本地**识别**模型。
 *
 * 「怎么下、怎么解、装到哪」在 [VoiceModel] 那一层（与朗读共用），这里多出的是
 * **怎么用它**：[engine] 描述调用风格 —— 三种模型是三套完全不同的配置（paraformer 是
 * 单文件 + 词表，whisper 是编码器 + 解码器 + 语种，sense-voice 还要给语种与文本正则化），
 * 字段名与语义都不通用，所以做成密封类型而不是一堆可空字段。
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
  val engine: AsrEngineSpec,
) : VoiceModel {
  override val requiredFiles: List<String> get() = engine.requiredFiles
}

/**
 * 识别引擎的调用风格。密封类型：加一种模型 = 加一个分支，编译器会逼着
 * `LocalRecognizer` 把配置拼完。
 */
sealed interface AsrEngineSpec {
  /** 装好了的判据（相对模型目录的文件名）。 */
  val requiredFiles: List<String>

  /**
   * Paraformer：单文件 + 词表。中文，非自回归（一次前向出整句），手机上最快的一档。
   *
   * 归档里还有个 `am.mvn`，**不用管它**：那是给 FunASR 自己的推理用的，sherpa-onnx 的
   * paraformer 实现不读它（`csrc/offline-paraformer-model.cc` 里没有这个名字），
   * 归一化参数在导出 ONNX 时就烘进图里了。
   */
  data class Paraformer(
    val modelFile: String,
    val tokensFile: String,
  ) : AsrEngineSpec {
    override val requiredFiles: List<String> get() = listOf(modelFile, tokensFile)
  }

  /**
   * SenseVoice：单文件 + 词表 + 语种 + 是否做文本正则化（数字按阿拉伯数字写出来）。
   *
   * 语种只认 `auto` / `zh` / `en` / `ja` / `ko` / `yue`（对应模型元数据里那几个
   * `<|lang_*|>` 记号），给别的值不会报错，会退化成模型自己的默认行为。
   */
  data class SenseVoice(
    val modelFile: String,
    val tokensFile: String,
    val language: String,
    val inverseTextNormalization: Boolean,
  ) : AsrEngineSpec {
    override val requiredFiles: List<String> get() = listOf(modelFile, tokensFile)
  }

  /**
   * Whisper：编码器 + 解码器 + 词表 + 语种。多语言，但**自回归解码**（一个 token 一个 token
   * 往外吐），实测比上面两种慢几倍（一加 15T：6 秒音频 361ms，paraformer 是 62ms），
   * 而且音频越长差得越多 —— 这是选它时要知道的代价。
   *
   * [language] **留空 = 让它自己判语种**（sherpa 的原话：多语言模型不给语种就从音频里推断）。
   * 别写死 `zh`：真机上拿英文样例音频试过，写死之后它把整段英文硬解成中文
   * （输出「前來的大,在前來的大,…」这种），而且**不报错**。
   */
  data class Whisper(
    val encoderFile: String,
    val decoderFile: String,
    val tokensFile: String,
    val language: String,
  ) : AsrEngineSpec {
    override val requiredFiles: List<String> get() = listOf(encoderFile, decoderFile, tokensFile)
  }
}

/**
 * 可选的本地识别模型目录。
 *
 * **地址是 k2-fsa/sherpa-onnx 的 `asr-models` release 资产**，与朗读模型、应用内更新走
 * 同一个来源（GitHub），不引入第二个下载域。挑的都是归档里有 `test_wavs/` 的那几个 ——
 * 真机验证要用的中文样例音频就在里面（见 `docs/voice.md`）。
 *
 * 三个条目是**三种取舍**，默认 [PARAFORMER_ZH_SMALL]：
 *
 * | | 体积（解出来） | 语言 | 解码 | 授权 |
 * |---|---|---|---|---|
 * | paraformer-zh-small | 82MB | 中文（含中英混说） | 非自回归 | FunASR 模型许可 1.1 |
 * | sense-voice-int8 | 239MB | 中英日韩粤 | 非自回归 | FunASR 模型许可 1.1 |
 * | whisper-tiny | 103MB | 99 种（含中文） | **自回归** | MIT |
 *
 * **不在这里放「授权不明」的模型**：一个公开 App 的一键下载目录不该让用户替我们猜授权。
 * FunASR 那两条的授权不是 OSI 标准许可，但条款是可自由使用 / 修改 / 分发 + 必须署名并
 * 保留模型名（`MODEL_LICENSE` 1.1），所以能收 —— 界面把这句话原样显示出来。
 *
 * **Dolphin（Apache-2.0，104MB，多语言）验过但没收**：定位与 whisper-tiny 重叠，
 * 而它同样是 CTC 非自回归、更快，真要收多语言应该收它而不是 whisper-tiny。
 * 留着的理由是「与 whisper 同源」这个说法对用户更好懂，而且 whisper 的
 * 多语言覆盖面更广。想换成 Dolphin 的话改这一处即可（`asr-models` 归档里同名资产）。
 */
object AsrModelCatalog {
  /**
   * 模型归档的来源。**只此一处**：与应用内更新、朗读模型同一个下载域（GitHub），
   * 不引入第二个 —— 多一个域就多一份「墙内连不上」的风险。
   */
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
      engine =
        AsrEngineSpec.Paraformer(
          modelFile = "model.int8.onnx",
          tokensFile = "tokens.txt",
        ),
    )

  val SENSE_VOICE_INT8 =
    AsrModel(
      id = "sense_voice_int8",
      label = "SenseVoice（中英日韩粤）",
      summary = "239MB，中文准确率最好的一档，自带标点与数字正则化",
      url = RELEASE_BASE + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
      sizeBytes = 163_002_883L,
      archiveRoot = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
      license = "FunASR 模型开源许可 1.1",
      licenseNote =
        "上游是 FunAudioLLM/SenseVoice（iic/SenseVoiceSmall）。" +
          "条款：可自由使用 / 修改 / 分发，必须署名并保留模型名；不是 OSI 标准许可。",
      engine =
        AsrEngineSpec.SenseVoice(
          modelFile = "model.int8.onnx",
          tokensFile = "tokens.txt",
          // auto = 让它自己判语种。固定 zh 在纯中文上没差别，但中英混说时会漏掉英文那半句。
          language = "auto",
          // 数字按阿拉伯数字写出来（「三点五」→「3.5」）：模型认的是给模型看的文本，
          // 但这里出去的是要发给 agent 的指令，数字写成数字更不容易被它理解错。
          inverseTextNormalization = true,
        ),
    )

  val WHISPER_TINY =
    AsrModel(
      id = "whisper_tiny",
      label = "Whisper tiny（多语言）",
      summary = "103MB，99 种语言都认；但自回归解码，实测比另外两个慢好几倍",
      url = RELEASE_BASE + "sherpa-onnx-whisper-tiny.tar.bz2",
      sizeBytes = 116_204_861L,
      archiveRoot = "sherpa-onnx-whisper-tiny",
      license = "MIT",
      licenseNote = "OpenAI Whisper 的 tiny 权重（MIT）。归档里同时有 fp32 与 int8 两套，我们只用 int8。",
      engine =
        AsrEngineSpec.Whisper(
          encoderFile = "tiny-encoder.int8.onnx",
          decoderFile = "tiny-decoder.int8.onnx",
          tokensFile = "tiny-tokens.txt",
          // 留空 = 自动判语种。写死语种在这条路上是**会静默做错**的：见 AsrEngineSpec.Whisper
          // 的注释（真机实测把英文解成了中文）。
          language = "",
        ),
    )

  val ALL: List<AsrModel> = listOf(PARAFORMER_ZH_SMALL, SENSE_VOICE_INT8, WHISPER_TINY)

  fun byId(id: String?): AsrModel? = ALL.firstOrNull { it.id == id }
}

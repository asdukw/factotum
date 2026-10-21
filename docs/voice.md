# 语音：朗读（TTS）与语音输入（STT）

两个方向共用同一套**模型管线**（目录 / 下载 / 解 tar.bz2 / 安装）与同一个 native 库
（sherpa-onnx），但引擎、状态机、取舍各一套。§0–§5 是朗读，§6 起是语音输入。

## 0. 为什么是「系统 TTS 打底 + 本地引擎可选」

朗读这件能力有两种取法，代价完全不同：

| | 系统 TTS（`android.speech.tts`） | 本地开源引擎（sherpa-onnx + 中文模型） |
|---|---|---|
| 装完能用 | 是 | 要先下几十 MB 的模型 |
| 音色 | 随 ROM，ColorOS 上是可用的中文女声 | 好得多，且可选 |
| 联网 | 不联网 | 不联网 |
| 正文去哪 | 不出设备 | 不出设备 |
| 维护成本 | 零依赖 | 一个 native 依赖 + 模型下载与管理 |

两种取舍都成立，所以**做成用户的一个设置**而不是代码里的一个分支。默认系统 TTS：
一个不联网、不要流量、不要额外空间的方案，对「把回答念出来」这件事已经够用。

**都不走云端 TTS**（提供商的 `/audio/speech`）：那会把回复正文发给另一个服务，
而这个项目对正文的处理原则是「只进给模型的工具结果，不落日志、不落盘」
（AGENTS.md「安全与隐私红线」）。多一条出网路径要有明确理由，音色好还不是。

## 1. 分层

```
SpeakableText   纯 Kotlin：Markdown → 可朗读文本 + 按句切段（JVM 单测）
TtsEngine       接口：initialize / speak / stop / voices
SystemTtsEngine 设备自带引擎
LocalTtsEngine  本地引擎（见 §4）
VoiceReader     单例：唯一状态源、串行朗读、音频焦点
VoiceSettings   偏好：引擎 / 自动朗读 / 语速 / 音色
```

切分**不在引擎里**（在 `SpeakableText.chunks`，由 `VoiceReader` 驱动）：两个引擎的差异面
因此只剩 `maxChunk` 一个数字。

### 文件地图

| 位置 | 管什么 |
|---|---|
| `voice/SpeakableText.kt` | 纯 Kotlin：Markdown → 可朗读文本 + 按句切段（JVM 单测钉住） |
| `voice/TtsEngine.kt` | 朗读引擎接口：`initialize` / `speak` / `stop` / `voices` / `maxChunk` |
| `voice/SystemTtsEngine.kt` | 设备自带引擎（默认） |
| `voice/LocalTtsEngine.kt` | 本地引擎：sherpa-onnx 合成 + AudioTrack 流式播放 |
| `voice/VoiceModel.kt` | 合成与识别共用的模型抽象：怎么下、怎么解、装到哪 |
| `voice/VoiceModelStore\|Downloader\|Installer.kt` | 模型落盘 / 下载（复用更新那套续传）/ 单例安装状态 |
| `voice/SherpaArchive.kt` | 解 sherpa 的 `.tar.bz2`（剥顶层目录 + 挡路径穿越） |
| `voice/TtsModelCatalog.kt` / `AsrModelCatalog.kt` | 两侧的模型目录（地址 / 文件布局 / **授权**） |
| `voice/LocalRecognizer.kt` | 本地识别引擎：`OfflineRecognizer` + 与合成同一道闸门 |
| `voice/AudioCapture.kt` | 麦克风采集：16kHz 单声道 → `FloatArray` + RMS |
| `voice/VadSegmenter.kt` | 端点判定：sherpa-onnx 自带的 silero VAD（`assets/silero_vad.onnx`，629KB） |
| `voice/DictationEnd.kt` | 纯 Kotlin：会话的三个收尾条件（单测钉住） |
| `voice/RecognizedText.kt` | 纯 Kotlin：识别结果清理 + 回填输入框（单测钉住） |
| `voice/VoiceInput.kt` | 单例：语音输入的唯一状态源、按模型 id 缓存识别器 |
| `voice/VoiceSettings.kt` | 偏好：朗读引擎 / 自动朗读 / 语速 / 音色（**非密**） |
| `com/k2fsa/sherpa/onnx/*.kt` | **vendor** 的 JNI 绑定（`Tts` / `OfflineRecognizer` / `OfflineStream` / `Vad` / `FeatureConfig` / `WaveReader`）；与 `fetch-sherpa.ps1` 同 tag，别手改 |
| `ui/settings/VoiceScreen.kt` + `VoiceViewModel` | 设置页「语音」（只有朗读；识别模型没有入口） |
| `debug/.../VoiceDebugReceiver.kt` | **仅 debug**：把 WAV 喂给识别引擎 / 无 UI 装模型（`op ASR` / `INSTALL` / `MODELS`） |

## 2. 为什么不能把正文直接交给 TTS

模型写的是给人**看**的 Markdown。直接念出来：

- `**重点**` → 「星号星号重点星号星号」
- 代码围栏 → 把整段 base64 念完
- `[文档](https://example.com/a?b=1)` → 一串没人能听的 URL
- `✅ 已完成` → 「白色重型对勾 已完成」

所以朗读走的是与渲染**同一条解析**（`ui/chat/Markdown`），只是把块与行内标记摊平成文字。
代码块与分隔线整个丢掉 —— 读代码没有意义，而它往往占正文大半。

换行是**强制**断点：模型写的换行是有意义的（列表项、标题、表格行），合并成一段会让
「买菜 取快递 记得带伞」连成一口气，列表就读没了。

切段上限 320 字符（远低于 `getMaxSpeechInputLength()` 的 4000）：切得短才有得停 ——
一段读完才检查一次取消，长段会让「停止」要等好几秒才生效。

## 3. Android 11+ 的包可见性（**踩过，静默失败**）

`TextToSpeech` 靠一个隐式意图（`ACTION_TTS_SERVICE`）找引擎服务。Android 11 起包可见性
默认过滤，**清单里不声明就看不见任何 TTS 引擎**：

```
I AppsFilter: interaction: PackageSetting{com.tappet.app} ->
  PackageSetting{com.oplus.ttsaccessibilityengine} BLOCKED
```

现象是「点了朗读没反应」—— `OnInitListener` 直接回 ERROR，界面上只有一句几秒后自动消失的
提示。修法是 `<queries>` 里加：

```xml
<intent>
    <action android:name="android.intent.action.TTS_SERVICE" />
</intent>
```

`SystemIntentsManifestTest` 钉住这一条（与 `SET_ALARM` 权限同一条规矩：两处必须一致、
错了不报错的约束一律用单测钉住）。

**另一件必须做的事**：`initialize()` 加 5 秒超时。设备上一个引擎都没有时
（国产 ROM 常见：引擎装了但要用户先去系统设置里选一次）回调可能**永远不来**，
不设超时就是静默卡死。

## 4. 本地引擎（sherpa-onnx）

模型跑在 Tappet 自己的进程里，不依赖 ROM 装了什么引擎。四处：

| 位置 | 管什么 |
|---|---|
| `tools/fetch-sherpa.ps1` | 拉官方预编译的 `libsherpa-onnx-jni.so` → `jniLibs/<abi>/`（不入库） |
| `com/k2fsa/sherpa/onnx/Tts.kt` | **vendor** 的 Kotlin/JNI 绑定，与 `fetch-sherpa.ps1` 的版本必须同 tag |
| `voice/TtsModelCatalog.kt` | 可下载模型的目录（地址 / 文件布局 / **授权**） |
| `voice/TtsModelDownloader.kt` + `SherpaArchive.kt` | 下载（复用更新那套续传）、解 tar.bz2、原子换目录 |
| `voice/LocalTtsEngine.kt` | 合成 + 播放 |

**为什么是 static-link 变体**：官方有两个 Android 包。默认的 `sherpa-onnx-<ver>-android.tar.bz2`
是 `jni + c-api + cxx-api + libonnxruntime.so` 四个 .so（arm64 合计约 32MB）；`-static-link-onnxruntime`
只有一个 `libsherpa-onnx-jni.so`（24MB，AGP strip 后 23MB），还省掉「四个 .so 必须配套」。
它也不依赖 `libc++_shared.so`（动态段里查不到这个名字），所以与 libnode 那份 STL 无关。

**vendor 的文件只删了一个函数**：官方 `Tts.kt` 末尾的 `getOfflineTtsConfig(...)` 便捷函数
（20 个参数，给示例用的）删掉了，配置在 `LocalTtsEngine` 里自己拼。其余部分**不要手改**：
JNI 按 Kotlin 对象的字段名取值，少一个字段不报编译错，会在 native 层崩。

**模型目录 = 四条游戏角色音色**（2026-10-21）：刻晴 / 优菈（原神）、德丽莎 / 理之律者
（崩坏 3）。都是社区训练、k2-fsa 转成 ONNX 的 VITS，22.05kHz、中/日混读、每条 120MB。
原来那两条 13MB 的 Piper int8（`chaowen` CC0 / `xiao_ya` 非商用）已删除。

**为什么不是塔菲 / 菲比**（挑音色的完整过程，省得下次重查）：

| 目标 | 结论 |
|---|---|
| 永雏塔菲 | 公开权重**全是 Bert-VITS2** 的 `G_*.pth`（HF Spaces：`MelodyYuuka/Taffy-Bert`、`songdaooi/Taffy-Bert-VITS2`、`xiao666/Taffy-Bert-VITS2.0.2666`），另有 so-vits-svc 模型（`cynika/taffy`，是**变声**不是合成）。sherpa-onnx 的 `OfflineTtsModelConfig` 只有 vits / matcha / kokoro / kitten / zipvoice / pocket —— **没有 Bert-VITS2**，而且两者架构不同（它的文本编码器吃的是 BERT 特征，1024 维/字，不是 token id），**不能靠转换塞进 VITS 那条路** |
| 鸣潮·菲比 | **全网没有 TTS 权重**：HF 的 models / spaces / datasets 全量搜（phoebe / 菲比 / feibi / wuwa / wuthering / 鸣潮 / sovits / vits）、GitHub 仓库 + 代码搜索、k2-fsa 的 646 个 `tts-models` 资产 —— 都没有。只有一个 RVC 变声模型（`PGR-RVC/Wuthering_Waves_RVC_v2` 的 `JP/PhoebeJP_*`）和一个**空仓库**（`suannaige/GPT-Sovitsv4-Phoebe`，只有 README）。ModelScope 要登录搜不了、爱发电/网盘够不着，这两处是盲区 |
| 自建 Bert-VITS2 引擎的代价 | ONNX Runtime + 生成器自己导出 + BERT（1.3GB fp32）+ 可选 g2pW + Kotlin 侧中文文本前端；下载 0.7–2GB，每句 2–6 秒（现在 Piper 是 0.3 秒），**以周计** —— 用户判定「完全不值得」 |

### 4.1 `sid` 是**角色**，不是常量（2026-10-21）

这批模型是「**一个游戏一份多说话人模型**」，角色只是里面的某一行：

- 导出脚本把 `sid` 做成了 ONNX 的**输入**（`vits-zh-hf-models.py` 里 `torch.onnx.export` 的
  `input_names` 含 `sid`，元数据 `n_speakers=804`）—— 所以 `generate(sid=)` 选的是说话人。
- **填错不报错，只是读出别人的声音**，只有耳朵能发现。`TtsModel.sid` 因此是必填项，
  值来自源合集 `zomehwh/vits-models-genshin-bh3` 的 `pretrained_models/info.json`
  （它的试听页用的就是这一份）：刻晴 115 / 优菈 124 / 德丽莎 193 / 理之律者 193。
  `TtsModelCatalogTest` 逐个钉住。
- **改了音色要真机听一遍**：这一层没有别的判据（PC 上用 sherpa 的 Windows 版能合成、
  能证明「sid 确实换声音」，但证明不了「这一行就是那个角色」）。
- 归档里除了模型还有 `dict/`（jieba 分词）与四个 FST；我们挂 `phone.fst,number.fst,date.fst`
  三个（`new_heteronym.fst` 是给词典用的，不挂）。

**加条目的标准**（别放宽）：地址是公开、稳定、直接可下的 GitHub release 资产，与本仓库的
应用内更新同一个来源，不引入第二个下载域；授权必须说得清，且显示在下载按钮旁边。
**这四条授权都不干净**（游戏角色音，米哈游没授权）—— 那是用户明确知情后的选择，
别拿它当先例。
**MeloTTS 没进来**不是授权问题（它是 MIT），是产物问题：GitHub 归档里的 `model.int8.onnx`
是个 133 字节的 **git-lfs 指针**（真身在 HF，53MB），归档里能用的只有 170MB 的 fp32，
手机上又大又慢。要收它得改从 HF 下载，那会引入第二个下载域，而 HF 墙内直连不通。

**下载复用 `UpdateDownloader.fetchInto`**，不是重写一套：断点续传的三条细节（`If-Range`
而不是裸 `Range`、206 的 `Content-Length` 是剩余长度、416 要丢掉本地重来）没必要写第二遍。
归档留在 `cacheDir` 的 `.part` 里跨进程存活。

**装的过程是「解到 staging → rename 成正式目录」**：直接解到正式目录的话，解到一半被杀
会留下一个「看起来装好了」的目录，`isInstalled` 放行，然后在 native 层崩。
`isInstalled` 的判据是**关键文件都在**，不是「目录存在」。

**解包有两道闸**：剥掉归档的顶层目录（否则运行时拼不对路径）；**挡住路径穿越**
（tar 条目名可以是 `../../databases/tappet.db`，越界的条目直接跳过）。都用
`SherpaArchiveTest` 里自造的 tar.bz2 钉住了 —— 不需要设备，也不需要下真模型。

**合成与播放是流水的**：`generateWithCallback` 一边算一边往 AudioTrack 里写，
不是「算完一整段再播」（后者要等一两秒才出声）。停止靠回调里看一眼 `stopped` 标志
让 native 收手，外加 `pause + flush` 立刻静音。

**依赖代价**：`commons-compress`（+ commons-io / codec / lang3）。Android 的 `java.util.zip`
不认 bzip2，而这些模型只有 `.tar.bz2` 一种分发格式。四个纯 Java jar，Apache-2.0。

### 4.1 回调**必须**是匿名对象，不能写成 lambda（真机闪退，2026-10-20）

sherpa-onnx 的 JNI 按**名字与签名**取回调方法：

```cpp
GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")   // 参数 float[]，返回装箱的 Integer
```

Kotlin 2.x 默认把 lambda 字面量编成 invokedynamic，D8 再落成一个
`Xxx$$ExternalSyntheticLambda0`，那个类里**没有**这个专用重载（只有擦除后的
`invoke(Object)Object`）。于是 `GetMethodID` 返回 null，native 侧接着调 `NewFloatArray`
时撞上 pending exception，直接 SIGABRT：

```
Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
java.lang.NoSuchMethodError: no non-static method
"Lcom/tappet/app/voice/LocalTtsEngine$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"'
```

**整个 App 闪退，而堆栈里只有 sherpa 与协程的帧，一个字都指不到自己写的代码。**
`javap` 两种写法对比：

| 写法 | 编出来的 `invoke` |
|---|---|
| `{ samples -> ... }`（lambda 字面量） | 无类（indy）；D8 落成的类里只有 `invoke(Object)Object` |
| `object : (FloatArray) -> Int { override fun invoke(...) }` | `Integer invoke(float[])` + `Object invoke(Object)` ✅ |

所以 `LocalTtsEngine.samplesCallback` 是匿名对象，`TtsCallbackSignatureTest` 断言编译产物
里真的有那个重载（把实现换回 lambda，这条测试立刻 `NoSuchMethodException` —— 验过）。

**另一条踩过的**：`destroyIfIdle()` 里 `if (!released) return` 那一行不能省。少了它，
每次合成结束（`finally` 里无条件调它）都会把引擎释放掉，表现是「第一段读完就停了，
日志里一句『朗读中断』」。

## 5. 已知边界

- **不在后台继续读**。没有前台服务，App 退到后台后进程可能被冻结，朗读会卡住。
  与「只在用户点的时候联网」同一条原则：不做用户没要求的常驻。
- **焦点被抢走就停，不自动续读**。从半句话中间接上去比不读更让人困惑，用户再点一次即可。
- **一次只读一条**。点另一条 = 停掉当前的、开始新的。这也是 TTS 引擎本身的约束
  （一个引擎一个输出流），与其在下面排队，不如在上面就定义清楚。
- **正文不进日志**。`TappetVoice` 只记引擎名、段数、失败原因。

## 6. 语音输入：为什么只有本机识别这一条路

朗读那边是「系统引擎打底 + 本地可选」，语音输入这边**没有系统那一档**，不对称是刻意的：

Android 的 `SpeechRecognizer` 在多数 ROM 上把音频发给厂商的云（Google 的、ColorOS 的）。
而这条路上出去的是**用户的声音** —— 比屏幕正文更私密，也更容易被拿去做别的事。
朗读之所以能用系统引擎，是因为它的输入是**我们自己写的文本**、且系统 TTS 不联网；
两件事不是一回事，不能因为「朗读那样做了」就照搬。

同样的理由排除云端 ASR（各家 `/audio/transcriptions`）：它们确实比端侧准，
但那是把麦克风里的东西交给另一个服务。**多一条出网路径要有明确理由，「更准」还不是。**

**`OfflineRecognizer` 不是线程安全的**，而协程取消打断不了阻塞的 native 调用 ——
并发进去就是 `FORTIFY ... destroyed mutex` + 整个 App 闪退。合成那边是同一道闸门
（`LocalTtsEngine` 串行化），两边都别绕开。

推论：语音输入必须有识别模型（paraformer-zh-small + 标点模型，见 §8）。它**没有设置页入口** ——
模型随安装自动下载，没下完时点麦克风说的是「正在下载」而不是「不可用」。

**音频的边界**：只在本进程内存里活到识别完，不落盘、不进日志。
`LocalRecognizer` 只记样本长度、字符数与耗时；debug 广播要打印识别文本得显式
`--ez logPayload true`（那时打印的是你推上去的测试音频，不是用户说的话）。

## 7. 端点判定：silero VAD（2026-10-20 换掉了手写的能量法）

「说完了没有」用 **sherpa-onnx 自带的 silero VAD**：`voice/VadSegmenter.kt` +
`assets/silero_vad.onnx`（629KB，`tools/fetch-sherpa.ps1` 打进包，不让用户多下一样东西）。

**为什么把自己写的那套删了。** 第一版是算每帧 RMS 的自适应阈值（`EndpointDetector`），
在真机上被一个现象否掉：用户点开麦克风**什么都没说**，识别器照样「听」出一句像模像样的
话（实测：静音 5 秒 → 7~11 个字），而**调阈值堵不住** —— 收紧之后重测，安静房间里的
环境噪声一样越线，还是 7 个字。这正是 whisper 系模型众所周知的幻觉，公认的主修法就是
**VAD 前置过滤**，而 sherpa-onnx 恰好自带。换成 VAD 之后「静音喂出幻觉文本」**结构上
不可能**：环境噪声根本不会被切成段，也就没有东西可喂。

这不是拍脑袋定的，两处上游依据：

- **sherpa-onnx 官方发布「VAD + non-streaming speech recognition」的 Android APK**
  （<https://k2-fsa.github.io/sherpa/onnx/vad/apk-asr.html>），列表里每一个都是
  `silero_vad.onnx` + 一个非流式识别模型 —— 与本仓库这套是同一个形状。
- **openai/whisper discussion #1606「Hallucination on audio with no speech」**
  （<https://github.com/openai/whisper/discussions/1606>）：报告者的现象与这里遇到的
  一模一样，他用 prompt 压制**没用**（prompt 会被 decoder 当成上下文复读）。那里给的
  流水线是 `microphone stream -> VAD / silence gate -> only transcribe chunks that
  contain speech`，并点名 Silero VAD、以及「没有语音帧就返回空、**别调模型**」。

调用的是上游 `vad-with-non-streaming-asr.py` 那个循环：喂 512 样本窗口 →
`empty()` / `front()` / `pop()` 取走说完的段 → 段样本交给识别器。

三个阈值是**按自己的取舍给的值**，所以没用上游的 `getVadModelConfig` 工厂函数，写在自己这边：

| 值 | 含义 | 为什么不照抄上游默认 |
|---|---|---|
| `threshold = 0.5` | 「是说话」的概率门 | 就是上游默认。**不往下调** —— 调低会把环境音当说话，那正是要摆脱的失败模式 |
| `min_silence = 0.8s` | **这就是「说完了」** | 上游默认 0.25 秒是给「切文件」用的；短于 0.8 会在换气处断句 |
| `max_speech = 10s` | 一段最长切多长 | 上游默认 5 秒会把一句话从中间切开；切太长则界面上半天不出字 |

**`flush()` 不能省**（实测踩到）：段是在「后面跟了 0.8 秒静音」时才闭合的，所以
**正在说的最后一句永远悬着**。拿真实中文样例喂进去，切出的是 **0 段** —— 加上 `flush()`
才有。用户说完立刻按停（最常见的操作）是同一个情形。收尾顺序必须是
`recorder.stop()`（join 采集线程）→ `finish()`（flush + 取段）→ `release()`，
不能反 —— `Vad` 不是线程安全的。

**会话层面的三个收尾条件**在 `voice/DictationEnd.kt`，是**纯函数**（有单测）：

1. 到 90 秒硬上限；
2. **刚说完一段，且此刻没人在说话** = 用户说完了。后半个条件不能省 —— 一段也会因为切到
   `max_speech` 上限而吐出来，那时人还在说，当成「说完了」会把长句子从中间截断；
3. 一直没说话到 5 秒 → 「没听到说话」。

**实测**（一加 15T，`op VAD` 广播喂归档里的真实样例，见 §9）：

| 样例 | 输入样本 | 切出段数 | 段内样本 | 结果 |
|---|---|---|---|---|
| 0.wav（中文） | 89,784 | 1 | 84,384 | 与直接喂原始音频**逐字相同** |
| 1.wav（中文） | 82,449 | 1 | 80,288 | 正确 |
| 2-zh-en.wav（中英混说） | 129,600 | 1 | 123,808 | 正确 |
| 5-henan.wav（河南话） | 127,840 | 2 | 65,952 + 47,008 | 两段拼起来正确 |

段是**掐掉首尾静音**的（84,384 < 89,784），送进识别器的音频比录到的短 —— 这也是它更快更准的一部分原因。
VAD 本身的耗时可以忽略：5.6 秒音频 20~53ms。

切出多段时**每段各自识别、再把文本拼起来**（§10 的「按段识别」）：`5-henan.wav` 那两段是
河南话里两个独立的句子，分开识别本来就更合理；而一句话被 `max_speech` 从中间切开时，
分开识别会丢掉跨段上下文 —— 这是那个取值的代价，见 §10。

## 8. 识别模型：只有 Paraformer

**不做成可选**。识别模型之间的取舍（准确率 / 速度 / 语言 / 授权）不该由用户回答：语音输入
只服务一个场景 —— 把一句话变成发给 agent 的指令 —— 一个设置项只会多一次「选错了却不知道」。
所以设置页**没有**识别模型的入口，模型随安装自动下载（`TappetApplication`），点麦克风时
再确认一次（没下完就说「正在下载」，下载失败下次启动或再点麦克风会重试）。

| | 值 |
|---|---|
| 模型 | `sherpa-onnx-paraformer-zh-small-2024-03-09` 的 int8 权重 + 词表 |
| 归档 | 77.9MB，解出来 82MB |
| 语言 | 中文（中英混说也认） |
| 解码 | 非自回归（一次前向出整句），手机上最快的一档 |
| 授权 | FunASR 模型开源许可 1.1 —— 可自由使用 / 修改 / 分发，必须署名并保留模型名；不是 OSI 标准许可 |
| 来源 | k2-fsa/sherpa-onnx 的 `asr-models` release 资产（与朗读模型、应用内更新同一个域） |
| 伴生 | 标点模型 CT-Transformer（§8.1） |

**一加 15T 实测**（`adb` 广播喂 WAV，绕开麦克风，同一段 5.6 秒中文）：

| 模型 | 输出 | 解码耗时 |
|---|---|---|
| paraformer-zh-small | 对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯 | 62ms |
| （对照）whisper-tiny | 对我做了介,我想说的是,大家如果对我的究感趣呢 | 239ms |

whisper tiny 在英文样例上与归档里的参考文本**逐字一致**，中文这一档则明显不如 paraformer、
且慢几倍（自回归解码，音频越长差得越多）—— 这是选 paraformer 的依据。paraformer 的代价是
**不产标点**（上面那行输出只有语气词断开），所以标点模型是它的伴生件，见 §8.1。

**`modelType = "paraformer"` 要填**：sherpa 认这个值，填了能省掉「先猜一遍模型类型」
那一步（官方原话：不填会 load 两遍模型）。

**归档里的 `am.mvn` 不用管**：那是给 FunASR 自己的推理用的，sherpa-onnx 的 paraformer 实现
不读它（`csrc/offline-paraformer-model.cc` 里没有这个名字），归一化参数在导出 ONNX 时就
烘进图里了。

**识别器进程内只建一个**（`VoiceInput.engineFor`）：建一次要把上百 MB 的模型读进内存，
每次说话都重建就是每次等好几秒。

**结果里除了文本还有两个判定**：paraformer **两个都不填**（`lang` / `event` 是给带这两种
判定的模型留的）。两个字段都只进日志与判定，不进用户可见文本；日志里的记号先过
`normalizeMarker`。

`sense-voice-int8` 与 `whisper-tiny` 都曾经在目录里，现在**已移除**；旧安装留下的目录由
`TappetApplication.pruneLegacyModels` 在启动时清掉。

## 8.1 标点模型（跟着 paraformer 一起装）

paraformer-zh-small 的输出是**没有标点的裸文本**（真机实测：
「对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯」—— 一整句只有语气词把它
断开），而这句话是要发给 agent 的指令，标点直接决定它怎么断句。

| | 值 |
|---|---|
| 模型 | CT-Transformer（中英），`sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8` |
| 体积 | 归档 61.7MB，解出来 72MB 的 `model.int8.onnx`（fp32 那份 281MB，不收） |
| 授权 | **Apache-2.0**（ModelScope 上 `iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch` 的 License 字段） |
| 下载域 | 同一个仓库，但**另一个 release tag**（`punctuation-models`，不是 `asr-models`）—— 那条红线管的是域，不是 tag |

**设置页里没有它的位置** —— 「要不要标点」不是一个该由用户回答的问题，它只取决于识别模型
选的是哪个。所以它是一条数据（`AsrModel.companions` → `installOrder`），
`VoiceModelInstaller` 装本体时按顺序一起装，**已经装好的跳过**。三件配套的事：

- **仍然有自己的种类与目录**（`VoiceModelKinds.PUNCT`）：它是「装一次、谁需要谁用」的共享件。
  `VoiceModel.kind` 因此由模型自己声明，而不是沿用调用方传进来的那个 ——
  伴生模型装到的是**另一类**目录（判据写错的表现是「明明装过却每次都重下 62MB」）。
- **它是改善，不是前置条件**：没装、引擎起不来、补出来是空的 —— 任何一种都只是原样返回，
  语音输入照常可用。所以 `LocalPunctuator` 的失败处理与 `LocalRecognizer` 不同：那边失败
  等于这次输入没成，这边只是少几个逗号。
- **标点是在段拼起来之后补一次**，不是逐段补：标点模型要看到完整的句子才知道该在哪断，
  逐段补会在段边界上塞进句号，而那里往往正是一句话的中间。

**native 侧不用重新编译**：`Java_com_k2fsa_sherpa_onnx_OfflinePunctuation_*` 这些符号
**本来就在** `libsherpa-onnx-jni.so` 里（`strings` 扫过），所以只差一层 vendor 的 Kotlin 声明
（`com/k2fsa/sherpa/onnx/OfflinePunctuation.kt`）。流式识别（`OnlineRecognizer`）同理。

## 8.2 两个「看着能用、其实是死旋钮」的字段（v1.13.8 源码确认）

`OfflineRecognizerConfig` 里有两个字段，直觉上正好对上本仓库的两个已知失败模式，
**但它们对 paraformer 不生效**。写在这里是为了别再加回来：

| 字段 | 为什么不生效 |
|---|---|
| `decodingMethod = "modified_beam_search"` | paraformer 实现里是 `if (decoding_method == "greedy_search") { ... } else { SHERPA_ONNX_EXIT(-1); }`（`offline-recognizer-paraformer-impl.h`）。填别的**不是退化成贪心，是直接退出进程** —— 内嵌 Node 与宿主同进程，退出就是 App 崩。beam search 只有 transducer 系有 |
| `blankPenalty` | 只有 transducer 用（它被塞进 `offline-recognizer-transducer-impl.h` 的两个解码器）。paraformer 这条路上一个字都没读它 |

反过来，**`ruleFsts` / `ruleFars` / `hr` 是活的**：它们在基类
（`offline-recognizer-impl.cc` 的 `Init`）里统一构造，对所有模型生效。要补 ITN 或同音字
替换走那三个字段，不要碰上面两个。

## 9. 真机怎么验（不用开口）

识别这条路上，「模型下没下好」「native 引擎起不起得来」「paraformer 的配置拼得对不对」
都能在没有人的情况下验，只有「麦克风收到的是不是人声」需要用户开口。
入口是 debug 广播（仅 debug 构建）：

```powershell
# 看识别模型与标点模型的状态（装没装 / 占多大 / 还差多少；没有正文）
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op MODELS"

# 走和启动时完全相同的那条链路装模型（下载 → 解包 → 原子换目录）
# 识别模型与标点模型都走这一条，给目录里的 id 即可（标点那条是 punct_ct_transformer_zh_en）
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op INSTALL --es model paraformer_zh_small"

# 把一段 WAV 喂给识别引擎；--ez logPayload true 才打印文本
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op ASR --es wav <设备上的路径> --ez logPayload true"

# 把一段 WAV 推过 **VAD**，再走一遍「VAD 认过的段 → **逐段**识别 → 拼起来 → 补标点」的真实路径
# 打的是结构：input / segments / speech_samples / 每段的样本数与它自己的 lang / event；文本同样要 logPayload
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op VAD --es wav <设备上的路径> --ez logPayload true"

# 只验标点模型：不经过识别、不经过麦克风，给一段文本看它补出什么
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op PUNCT --es text '对我做了介绍啊那么我想说的是呢' --ez logPayload true"
```

样例音频**就在装好的模型目录里**（`files/asr-models/paraformer_zh_small/test_wavs/`），不必从 PC 推：
App 读自己的私有目录没有障碍，所以直接给绝对路径即可 ——
`--es wav /data/user/0/com.tappet.app/files/asr-models/paraformer_zh_small/test_wavs/0.wav`。
（`adb push` 那条路仍然要按下面第 1 点走，那是**别的进程**的文件读不到。）

`op VAD` 是**换掉手写端点判定之后加的**：那条路原来只能靠人对着手机说话才能验，
而样例音频是真实中文语音，喂进去就能回答「VAD 认不认得出人声」「切成了几段」
「切出来的段交给识别器还出不出字」。**这条探针第一次跑就抓到了 `flush()` 缺失**
（真实语音切出 0 段）—— 没有它，这个 bug 会一路走到用户手上，表现是「说完立刻按停，话没了」。

**`op VAD` 按段识别，与真实路径一致**：早先它把切出来的段拼成一条音频再解码，
而 `VoiceInput` 早就改成逐段做了 —— 探针要是照旧写法，它验的就不是用户遇到的东西。
这条规矩（**探针必须复现真实调用形态**）与 Termux 那边「脚本要按 `bash -lc <文本>` 跑」
是同一条。

`op PUNCT` 单独存在是因为标点这条路有**三个独立的失败点**：模型没装、native 起不来、
补出来的标点不对 —— 混在一次识别里看日志分不清是哪一个。

样例音频就在模型归档里（`test_wavs/`），`adb push` 上去即可。**两个坑**：

1. **WAV 必须放进 App 私有目录**。`/data/local/tmp` 与 `/sdcard/Android/data/<包名>/`
   下 adb 推的文件，App 都读不到（实测 `File.canRead()=false`，报
   `Failed to read wave file`）—— 前者是 SELinux，后者是 shell 建的文件属主不对。
   可用的写法是先推到 `/data/local/tmp`，再由 shell 侧 pipe 进 run-as：
   `adb shell "cat /data/local/tmp/x.wav | run-as com.tappet.app sh -c 'cat > files/asr/x.wav'"`
   （**别**从宿主侧直接 `cat file | adb shell ...`：会截断成几十字节）。
2. **先 `monkey` 把 App 拉到前台再广播**：ColorOS 会 defer 后台广播，
   现象是广播回 `result=0` 但日志里什么都没有。

**只能由人验的那一段**：聊天页点麦克风 → 说一句话 → 看电平条动、停顿后自动结束、
文本出现在输入框。VAD 在真机上的手感（0.8 秒算说完是不是太急、10 秒切段会不会在句中断开）
也只能这样感受。**静音那一路已经能自动验**：点麦克风不说话，应当只出「没听到说话」、
输入框里一个字都不该有。

## 10. 已知边界

- **不是流式**：说完才出字，不是边说边出。要流式得换 streaming 模型（zipformer 那类）。
  native 侧不用重编（`OnlineRecognizer` 的 JNI 就在现有的 .so 里），代价在模型：
  流式只有 transducer 系，中文准确率通常不如 paraformer，还要多下一个模型。
  顺带一提，**hotwords（让模型认识 App 名 / 联系人名）也只支持 transducer** —— 对一个要听
  「打开某某 App」的 agent，这一条可能比流式本身更值，但两者绑在同一个决定上。
- **按段识别，段与段之间没有上下文**：VAD 每吐出一段就送去识别（边录边出结果），
  收尾只需等最后那一段。代价是一句话被 `max_speech`（10 秒）从中间切开时，两半各自解码，
  边界上可能多一个断句。早先的写法是把整场音频拼起来一次解码（上下文连贯），
  但连续说话到 90 秒上限时就是**一次 decode 90 秒音频**：延迟与内存全压在最后一下。
- **一次一段**：不做连续听写 —— 输入框里装的是**一条指令**，不是聊天记录。
- **不做说话人区分、不做时间戳**：这是「把一句话变成指令」，不是转写会议。
  （要这些的是 PC 侧的 WhisperX 那类工具，不是 App 里的事。`SpeechSegment.start` 那条
  时间轴在绑定里是现成的，但这条路上用不到。）
- **识别结果只进输入框，不自动发送**：用户要能改。追加而不是覆盖，连说两段也接得上。
- **引擎常驻内存**（82MB；标点模型 72MB 另算）：进程内只建一个，直到进程结束。
- **识别模型随安装自动下载**，设置页里没有入口（见 §8）。**踩过**：早先做成设置页里可选，
  于是出现「模型都装着、文件也齐，点麦克风却说还没下载」—— 判据一多，界面就会和事实对不上。
- **标点跟着识别模型一起装**（61.7MB，设置页里没有它单独的入口）：paraformer 那档出来的
  是没有断句的一整句，标点模型补上逗号句号。见 §8.1。
- **停顿 0.8 秒算说完**（VAD 的 `min_silence`）：换气久一点会被截断。再按一次麦克风
  接着说就行（结果**追加**到输入框，不覆盖）。
- **不采集、不上传**：没有云端 ASR，音频不出设备、不落盘。
- **「边说边出」目前没有**：按段识别让**总**等待时间变短（前几段在录音期间就识别完了），
  但界面上仍然是「说完 → 一次性出文本」—— 输入框里不会先出现半句话。要那个效果是流式的事。

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

**模型目录只有两条，都是 13MB 的 Piper int8**：
`chaowen`（CC0，默认）与 `xiao_ya`（数据集 BZNSYP，**非商用**）。选择标准是「小 + 授权说得清」。
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

推论：语音输入必须先下模型（82–239MB，见 §8），没下就是「点了麦克风告诉你还没下」。

**音频的边界**：只在本进程内存里活到识别完，不落盘、不进日志。
`LocalRecognizer` 只记样本长度、字符数与耗时；debug 广播要打印识别文本得显式
`--ez logPayload true`（那时打印的是你推上去的测试音频，不是用户说的话）。

## 7. 端点判定：能量法，以及它的代价

「说完了没有」用 `voice/EndpointDetector.kt`：算每一帧（32ms）的 RMS，高于阈值算说话，
连续静音 1.2 秒算说完。**不用模型**（sherpa 自带 silero VAD，准得多）的理由是
v1 不想多一条下载与一套失败模式 —— 代价在下面写着，**下一步就是它**。

阈值自适应，规矩是**快降慢升**：

- **降要快**（这一帧比估计值低就立刻跟下去）：环境安静下来是瞬间的事，不跟下去就会
  把安静当成语音、一直录到 60 秒上限。
- **升要慢**（每帧最多涨 0.00002）：用户**点完麦克风立刻开口**时，前几帧就是他自己的声音。
  估计值要是跟着冲上去，阈值会顶到他的音量之上 —— 现象是「说了半天它说没听到」。
  这条有单测钉住（`EndpointDetectorTest`）。

噪声底**跨段保留**（`VoiceInput` 把上一段的估计值传给下一段）：第一段在嘈杂环境里
可能不准，第二段开始就准了。

三个结束条件：说够了且静音 1.2 秒（正常）、一直没人说话 5 秒（`NoSpeech`）、
说太久 60 秒（`TooLong`）。手动停止键一直在，是这三种之外的兜底。

**它的失效场景**（如实写）：

| 场景 | 现象 |
|---|---|
| 环境噪声超过绝对下限（0.008） | 第一段可能被当成「说话然后说完」，送一段噪声给模型 → 「没听清」。第二段起噪声底学会了就正常 |
| 一直大声连着说 20 秒以上 | 噪声底的慢升会追上说话音量，可能在句中提前结束。**说完按一下停**最稳 |
| 换气停顿超过 1.2 秒 | 会被截断。再按一次麦克风接着说是可行的（结果**追加**到输入框，不覆盖） |

## 8. 识别模型（三条）与真机实测

地址都是 k2-fsa/sherpa-onnx 的 `asr-models` release 资产（与朗读模型、应用内更新同一个
下载域）。三条是三种取舍，默认 paraformer：

| | 解出来 | 语言 | 解码 | 授权 |
|---|---|---|---|---|
| paraformer-zh-small | 82MB | 中文（含中英混说） | 非自回归 | FunASR 模型许可 1.1 |
| sense-voice-int8 | 239MB | 中英日韩粤 | 非自回归 | FunASR 模型许可 1.1 |
| whisper-tiny | 103MB | 99 种（含中文） | **自回归** | MIT |

FunASR 那两条的授权**不是 OSI 标准许可**：条款是可自由使用 / 修改 / 分发 +
**必须署名并保留模型名**。界面把这句话原样显示在下载按钮旁边。

**一加 15T 实测**（`adb` 广播喂 WAV，绕开麦克风，同一段 5.6 秒中文）：

| 模型 | 输出 | 解码耗时 |
|---|---|---|
| paraformer-zh-small | 对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢嗯 | 62ms |
| whisper-tiny | 对我做了介,我想说的是,大家如果对我的究感趣呢 | 239ms |
| sense-voice-int8 | 开饭时间早上9点至下午5点。（另一段音频，带标点与阿拉伯数字） | 123ms |

whisper-tiny 在英文样例上与归档里的参考文本**逐字一致**（`test_wavs/trans.txt`），
中文这一档则明显不如另外两个，且慢几倍 —— 音频越长差得越多（自回归解码）。

**踩过的坑：whisper 的语种写死会静默做错。** 一开始给它写 `language = "zh"`，
英文音频被硬解成中文（输出「前來的大,在前來的大,…」），**不报错**。
sherpa 的原话是「多语言模型不给语种就从音频里推断」，所以这里留空。
`AsrModelCatalogTest` 钉住了「whisper 的语种必须为空」。

**Dolphin（Apache-2.0，104MB，多语言）验过但没收**：定位与 whisper-tiny 重叠，
而它是 CTC 非自回归、更快。留着 whisper 的理由是「与 whisper 同源」对用户更好懂、
覆盖面也更广；真要换，改 `AsrModelCatalog` 一处即可。

## 9. 真机怎么验（不用开口）

识别这条路上，「模型下没下好」「native 引擎起不起得来」「三种模型的配置拼得对不对」
都能在没有人的情况下验，只有「麦克风收到的是不是人声」需要用户开口。
入口是 debug 广播（仅 debug 构建）：

```powershell
# 看目录里每个模型的状态（没有正文）
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op MODELS"

# 走和设置页完全相同的那条链路装模型（下载 → 解包 → 原子换目录）
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op INSTALL --es model paraformer_zh_small"

# 把一段 WAV 喂给识别引擎；--ez logPayload true 才打印文本
adb shell "am broadcast -n com.tappet.app/.voice.VoiceDebugReceiver -a com.tappet.app.debug.VOICE --es op ASR --es wav <设备上的路径> --es model paraformer_zh_small --ez logPayload true"
```

样例音频就在模型归档里（`test_wavs/`），`adb push` 上去即可。**两个坑**：

1. **WAV 必须放进 App 私有目录**。`/data/local/tmp` 与 `/sdcard/Android/data/<包名>/`
   下 adb 推的文件，App 都读不到（实测 `File.canRead()=false`，报
   `Failed to read wave file`）—— 前者是 SELinux，后者是 shell 建的文件属主不对。
   可用的写法是先推到 `/data/local/tmp`，再由 shell 侧 pipe 进 run-as：
   `adb shell "cat /data/local/tmp/x.wav | run-as com.tappet.app sh -c 'cat > files/asr/x.wav'"`
   （**别**从宿主侧直接 `cat file | adb shell ...`：会截断成几十字节）。
2. **先 `monkey` 把 App 拉到前台再广播**：ColorOS 会 defer 后台广播，
   现象是广播回 `result=0` 但日志里什么都没有。

**只能由人验的那一段**：聊天页点麦克风 → 说一句话 → 看电平条动、尾静音后自动结束、
文本出现在输入框。噪声底的自适应与 1.2 秒截断的手感也只能这样感受。

## 10. 已知边界

- **不是流式**：说完才出字，不是边说边出。要流式得换 streaming 模型（zipformer 那类）。
- **一次一段**：不做连续听写 —— 输入框里装的是**一条指令**，不是聊天记录。
- **不做说话人区分、不做时间戳**：这是「把一句话变成指令」，不是转写会议。
  （要这些的是 PC 侧的 WhisperX 那类工具，不是 App 里的事。）
- **识别结果只进输入框，不自动发送**：用户要能改。追加而不是覆盖，连说两段也接得上。
- **引擎常驻内存**（82–239MB）：按模型 id 缓存，换模型 / 删模型时才释放。
- **嘈杂环境第一段可能不准**（见 §7 的表）；**换气超过 1.2 秒会被截断**。
- **不采集、不上传**：没有云端 ASR，音频不出设备、不落盘。

# 朗读（TTS）

把助手的回复读出来。**只做出声这一侧**：语音输入（STT）不在这一版里。

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
| `voice/TtsModelDownloader.kt` + `TtsArchive.kt` | 下载（复用更新那套续传）、解 tar.bz2、原子换目录 |
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
`TtsArchiveTest` 里自造的 tar.bz2 钉住了 —— 不需要设备，也不需要下真模型。

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

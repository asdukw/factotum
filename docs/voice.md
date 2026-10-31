# 语音：朗读与语音输入

两个方向走两条不同的路：朗读用 Fish Audio 云端合成，语音输入完全在本机（sherpa-onnx +
paraformer）。改 `voice/`、设置页语音或模型下载之前先读本页。

## 1. 分层与文件

| 位置 | 职责 |
|---|---|
| `voice/SpeakableText.kt` | 纯 Kotlin：Markdown → 可朗读文本，按句切段 |
| `voice/TtsEngine.kt` | 朗读引擎接口：`initialize` / `speak` / `stop` / `lastError` / `maxChunk` |
| `voice/FishTtsEngine.kt` | Fish Audio 合成 + 播放；请求体与错误文案是纯函数 |
| `voice/VoiceSecretStore.kt` | Fish API key（`SecretVault`，Keystore 封装）+ revision |
| `voice/FishVoices.kt` | 预置音色目录（`reference_id`） |
| `voice/VoiceReader.kt` | 单例状态机：切段、串行播放、音频焦点、错误 |
| `voice/VoiceSettings.kt` | 偏好：自动朗读 / 语速（非密） |
| `voice/VoiceModel*.kt` / `SherpaArchive.kt` / `AsrModelCatalog.kt` / `PunctModelCatalog.kt` | 识别与标点模型的下载、解包、原子安装 |
| `voice/LocalRecognizer.kt` / `LocalPunctuator.kt` / `VadSegmenter.kt` / `AudioCapture.kt` / `VoiceInput.kt` | 语音输入链路 |
| `com/k2fsa/sherpa/onnx/*.kt` | vendored JNI 绑定；与 `fetch-sherpa.ps1` 同 tag，别手改 |
| `debug/.../VoiceDebugReceiver.kt` | 仅 debug：`op ASR` / `VAD` / `PUNCT` / `INSTALL` / `MODELS` |

## 2. 朗读：Fish Audio

一次 `speak` = 一次 `POST /v1/tts`（同步，60s 超时）+ 一次本地播放。合成结果只存在于
`cacheDir`，播完立即删除，不落库。

- **key 只存 `SecretVault`**：不进 BuildConfig、不进日志、不进 `runtime-config.json`。
- **model 走 HTTP header**，写进 JSON body 会被忽略并静默按付费模型计费；`FishTtsEngineTest`
  钉住「body 里没有 model」。免费模型是 `s2.1-pro-free`。
- **切段在引擎外**：`SpeakableText.chunks` 按句切，上限 320 字符。切得短，第一声快，取消粒度细。
- **句间不空等**：播放第 N 段时并行合成第 N+1 段（`TtsEngine.prefetch`，单槽 `Deferred`），
  请求带 `latency: "low"`。

### 为什么先摊平 Markdown

模型写的是给人看的 Markdown。直接念会把 `**重点**`、代码围栏、链接 URL、emoji 念成噪声。
朗读复用与渲染同一条解析，只把块与行内标记摊平成文字；代码块和分隔线整体丢弃。
换行是强制断点（列表项、标题、表格行不能被合并成一段）。

### 失败

失败一律落到 `TtsEngine.lastError` 并显示在聊天页错误条，不静默；不自动重试。

| 情况 | 文案方向 |
|---|---|
| 没配 key | 提示去设置 → 语音 → Fish Audio |
| 401 | key 无效或过期 |
| 402 | 余额不足 |
| 429 | 限流，稍后重试 |
| 503 | 服务暂时不可用 |
| 其它非 2xx | HTTP 状态码 + 服务端 message |
| 连不上 / 超时 | 异常类名 |

### 边界

- 不在后台继续读；退到后台可能被系统冻结。
- 音频焦点被抢就停，不自动续读。
- 一次只读一条；点另一条会停掉当前的。
- 正文不进日志：`ZoneuVoice` 只记引擎名、段数、HTTP 状态码、失败原因。
- 断网就不读并说明原因，没有本地兜底。

## 3. 语音输入

只有本机识别这一条路。Android 的 `SpeechRecognizer` 在多数 ROM 上把音频发给厂商云；
多一条出网路径需要有明确理由，「更准」不算。音频只在本进程内存里活到识别完，不落盘、不进日志。

### VAD

端点判定用 sherpa-onnx 自带的 silero VAD（`voice/VadSegmenter.kt` +
`assets/silero_vad.onnx`，由 `fetch-sherpa.ps1` 打进包）。

| 值 | 含义 |
|---|---|
| `threshold = 0.5` | 「是说话」的概率门；不往下调 |
| `min_silence = 0.8s` | 判定「说完了」 |
| `max_speech = 10s` | 单段上限 |

没有 VAD 时，静音也会被识别成一句幻觉文本，调阈值堵不住；VAD 前置过滤让噪声根本不成段。

`flush()` 不能省：段要等后面跟 0.8 秒静音才闭合，正在说的最后一句永远悬着。
收尾顺序固定为 `recorder.stop()` → `finish()` → `release()`，`Vad` 不是线程安全的。

会话收尾条件在 `voice/DictationEnd.kt`（纯函数）：90 秒硬上限；刚说完一段且此刻没人在说话；
一直没说话到 5 秒。第二个条件不能省，否则 `max_speech` 切出的段会被误判为说完。

### 识别与标点

| | 值 |
|---|---|
| 识别模型 | `sherpa-onnx-paraformer-zh-small-2024-03-09` int8（归档 77.9MB） |
| 标点模型 | CT-Transformer 中英 int8（归档 61.7MB，Apache-2.0） |
| 解码 | paraformer 非自回归；`modelType = "paraformer"` 必填 |
| 语言 | 中文，中英混说也认 |

- **不做成可选**：语音输入只服务「把一句话变成指令」这一个场景，设置页没有模型入口。
  模型随安装自动下载；没下完时点麦克风提示「正在下载」，失败后下次启动或再点麦克风重试。
- **标点跟着识别模型装**：`AsrModel.companions` → `installOrder`，已装好的跳过。
  标点是改善不是前置条件：没装、引擎起不来、补出来为空，都原样返回。
- **标点在段拼起来之后补一次**，不逐段补。
- 识别器进程内只建一个（`VoiceInput.engineFor`）；`OfflineRecognizer` 不是线程安全的，
  并发调用会 `FORTIFY ... destroyed mutex` 闪退，必须串行化。
- `decodingMethod` 和 `blankPenalty` 对 paraformer 无效：前者非 `greedy_search` 会直接
  `SHERPA_ONNX_EXIT(-1)`，后者只有 transducer 读。要 ITN / 同音字替换用
  `ruleFsts` / `ruleFars` / `hr`。

### 边界

- 不是流式：说完才出字。
- 按段识别，段与段之间没有上下文；一句话被 `max_speech` 切开时边界可能多一个断句。
- 一次一段，不做连续听写、说话人区分、时间戳。
- 识别结果只进输入框，不自动发送；多次结果追加而不是覆盖。
- 引擎常驻内存（识别 82MB、标点 72MB），直到进程结束。

## 4. 验证

识别与标点不需要真人开口，样例音频在模型目录的 `test_wavs/`：

```powershell
adb shell "am broadcast -n com.zoneu.app/.voice.VoiceDebugReceiver -a com.zoneu.app.debug.VOICE --es op MODELS"
adb shell "am broadcast -n com.zoneu.app/.voice.VoiceDebugReceiver -a com.zoneu.app.debug.VOICE --es op VAD --es wav /data/user/0/com.zoneu.app/files/asr-models/paraformer_zh_small/test_wavs/0.wav --ez logPayload true"
adb shell "am broadcast -n com.zoneu.app/.voice.VoiceDebugReceiver -a com.zoneu.app.debug.VOICE --es op PUNCT --es text '对我做了介绍啊那么我想说的是呢' --ez logPayload true"
```

`op VAD` 走与真实路径相同的「VAD 分段 → 逐段识别 → 拼起来 → 补标点」，只打结构；
文本要 `--ez logPayload true`。探针必须复现真实调用形态，不能简化成另一条链路。

只能由人验的一段：点麦克风说话，看电平、停顿自动结束、文本进输入框；静音时只应出
「没听到说话」。朗读用真 key 点「试听」与聊天页喇叭验证，`logcat -s ZoneuVoice` 不含正文。

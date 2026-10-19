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

## 4. 本地引擎

待接：sherpa-onnx（预编译 Android 库）+ 中文 VITS 模型，模型在设置页里按需下载。
`LocalTtsEngine` 现在是占位实现，如实报「还没下载模型 / 推理还没接上」——
比静默退回系统引擎好：用户明确选了本地引擎，就不该在背后被换成另一个声音。

## 5. 已知边界

- **不在后台继续读**。没有前台服务，App 退到后台后进程可能被冻结，朗读会卡住。
  与「只在用户点的时候联网」同一条原则：不做用户没要求的常驻。
- **焦点被抢走就停，不自动续读**。从半句话中间接上去比不读更让人困惑，用户再点一次即可。
- **一次只读一条**。点另一条 = 停掉当前的、开始新的。这也是 TTS 引擎本身的约束
  （一个引擎一个输出流），与其在下面排队，不如在上面就定义清楚。
- **正文不进日志**。`TappetVoice` 只记引擎名、段数、失败原因。

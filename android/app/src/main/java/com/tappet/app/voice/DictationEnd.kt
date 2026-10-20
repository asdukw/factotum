package com.tappet.app.voice

/**
 * 「这次语音输入该收尾了没有」—— **纯函数**，不碰 Android、不碰音频设备。
 *
 * 判定「哪一段是人在说话」交给 VAD（[VadSegmenter]）；这里只管**会话**层面的收尾条件。
 * 分开的理由是这两件事的验证方式完全不同：VAD 只能真机验，而这三个条件喂几个数就能验，
 * 没有理由留到真机上靠嘴发现。
 */
internal sealed interface DictationEnd {
  /** 说完了一段，而且人已经不说话了。 */
  data object Silence : DictationEnd

  /** 从头到尾没听到人说话（误触麦克风、或者一直没开口）。 */
  data object NoSpeech : DictationEnd

  /** 到时长上限，剩下的不录了。 */
  data object TooLong : DictationEnd
}

/**
 * 该不该收尾。[segmentJustCompleted] 是「这一帧刚好吐出一段」。
 *
 * 三条规则，顺序即优先级：
 *
 * 1. 到 [MAX_SESSION_MS] 就停 —— 硬上限，免得用户忘了按停把整段对话都录进去。
 * 2. **刚说完一段，而且此刻没人在说话** = 用户说完了。`speechDetected` 这一半不能省：
 *    一段也会因为切到 `maxSpeechDuration` 上限而吐出来，那时人还在说，把它当成「说完了」
 *    会把长句子从中间截断（[VadSegmenter] 的类注释里写了这个区分）。
 * 3. 一直没听到人说话，到 [NO_SPEECH_TIMEOUT_MS] 放弃。
 *
 * 注意第 3 条用的是「有没有吐出过段」，不是「VAD 有没有检测到过说话」：短于
 * `minSpeechDuration` 的一声咳嗽不算段，也就不该让用户对着手机干等。
 */
internal fun dictationEnd(
  elapsedMs: Long,
  speechDetected: Boolean,
  heardSpeech: Boolean,
  segmentJustCompleted: Boolean,
): DictationEnd? =
  when {
    elapsedMs >= MAX_SESSION_MS -> DictationEnd.TooLong
    segmentJustCompleted && !speechDetected -> DictationEnd.Silence
    !heardSpeech && elapsedMs >= NO_SPEECH_TIMEOUT_MS -> DictationEnd.NoSpeech
    else -> null
  }

/** 一直没说话时放弃的时间。 */
internal const val NO_SPEECH_TIMEOUT_MS = 5_000L

/** 一次语音输入的硬上限。 */
internal const val MAX_SESSION_MS = 90_000L
